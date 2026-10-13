package com.barberease.services;

import com.barberease.models.*;
import com.barberease.repositories.*;
import com.barberease.websocket.BarberWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

@Service
@Transactional
public class QueueService {

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private TokenRepository tokenRepository;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    @Autowired
    private AppointmentService appointmentService;

    public Queue addToQueue(Long customerId, Long serviceId) {
        return addToQueue(customerId, serviceId, null, null, null);
    }

    public Queue addToQueue(Long customerId, Long serviceId, Long chairId) {
        return addToQueue(customerId, serviceId, chairId, null, null);
    }

    public Queue addToQueue(Long customerId, Long serviceId, Long chairId, String timeSlot, String appointmentDateStr) {
        com.barberease.models.Service service = serviceRepository.findById(serviceId)
                .orElseThrow(() -> new IllegalArgumentException("Service not found or inactive"));

        if (!service.isActive()) {
            throw new IllegalArgumentException("Service not found or inactive");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        LocalDate targetDate = LocalDate.now();
        if (appointmentDateStr != null && !appointmentDateStr.trim().isEmpty()) {
            try {
                targetDate = LocalDate.parse(appointmentDateStr.trim());
            } catch (Exception ignored) {}
        }

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        Optional<Queue> existing = queueRepository.findByCustomerIdAndStatusIn(
                customerId, Arrays.asList("waiting", "called"));

        if (existing.isPresent()) {
            Queue existingEntry = existing.get();
            if (existingEntry.getCreatedAt() != null && existingEntry.getCreatedAt().isAfter(startOfDay)) {
                throw new IllegalStateException("You are already in today's waiting list (Token: " + existingEntry.getTokenNumber() + ")");
            } else {
                existingEntry.setStatus("completed");
                existingEntry.setCompletedAt(LocalDateTime.now());
                queueRepository.save(existingEntry);
            }
        }

        // Clean up any stale serving entries that are not actively in service
        Optional<Queue> servingQueue = queueRepository.findByCustomerIdAndStatusIn(
                customerId, Collections.singletonList("serving"));
        if (servingQueue.isPresent()) {
            Queue sq = servingQueue.get();
            if (sq.getChair() == null || sq.getChair().getReservedByCustomer() == null ||
                    !sq.getChair().getReservedByCustomer().getId().equals(customerId)) {
                sq.setStatus("completed");
                sq.setCompletedAt(LocalDateTime.now());
                queueRepository.save(sq);
            }
        }

        long countTotal = queueRepository.count();
        long tokenIndex = countTotal + 1;
        String tokenNumber = "Q-" + String.format("%03d", tokenIndex);

        int avgDuration = service.getDurationMinutes() > 0 ? service.getDurationMinutes() : 30;

        Chair assignedChair = null;
        if (chairId != null) {
            assignedChair = chairRepository.findById(chairId).orElse(null);
        } else {
            // Automatically allocate first free waiting chair (Chairs 3, 4, 5, 6)
            List<Chair> waitChairs = chairRepository.findByChairTypeAndIsActiveTrueOrderByChairNumberAsc("waiting");
            for (Chair wc : waitChairs) {
                if (wc.getChairNumber() >= 3 && wc.getChairNumber() <= 6) {
                    if ("available".equalsIgnoreCase(wc.getStatus()) || wc.getReservedByCustomer() == null) {
                        assignedChair = wc;
                        break;
                    }
                }
            }
        }

        if (assignedChair != null && targetDate.equals(LocalDate.now())) {
            assignedChair.setStatus("waiting");
            assignedChair.setReservedByCustomer(customer);
            assignedChair.setReservedAt(LocalDateTime.now());
            chairRepository.save(assignedChair);
        }

        // When joining the waiting list or queue, status is ALWAYS "waiting"!
        String queueStatus = "waiting";

        int position = assignedChair != null 
                ? (assignedChair.getChairNumber() <= 2 ? 0 : assignedChair.getChairNumber() - 2)
                : (int) queueRepository.countByStatusAndChairIsNull("waiting") + 1;
        int estimatedWait = Math.max(0, position * avgDuration);

        Queue queue = new Queue();
        queue.setCustomer(customer);
        queue.setService(service);
        queue.setChair(assignedChair);
        queue.setTokenNumber(tokenNumber);
        queue.setPosition(position);
        queue.setStatus(queueStatus);
        queue.setEstimatedWaitMinutes(estimatedWait);
        Queue saved = queueRepository.save(queue);

        // Create waiting appointment record
        Appointment queueAppt = new Appointment();
        queueAppt.setCustomer(customer);
        queueAppt.setService(service);
        queueAppt.setChair(assignedChair);
        queueAppt.setTokenNumber(tokenNumber);
        queueAppt.setAppointmentDate(targetDate);
        queueAppt.setTimeSlot(timeSlot != null && !timeSlot.trim().isEmpty() ? timeSlot : "Next Available");
        queueAppt.setStatus(assignedChair != null ? "confirmed" : "pending");
        int waitNum = assignedChair != null ? Math.max(1, assignedChair.getChairNumber() - 2) : 1;
        queueAppt.setNotes(assignedChair != null ? "(Waiting Chair " + waitNum + ")" : "(Queued: Waiting List #" + position + ")");
        Appointment savedAppt = appointmentRepository.save(queueAppt);

        Token token = new Token();
        token.setTokenNumber(tokenNumber);
        token.setCustomer(customer);
        token.setQueue(saved);
        token.setAppointment(savedAppt);
        token.setType("queue");
        token.setTokenDate(targetDate);
        token.setStatus("active");
        tokenRepository.save(token);

        recalculatePositions();

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");
        webSocketHandler.broadcast("slot-update");

        return saved;
    }

    public List<Queue> getActiveQueue() {
        return getActiveQueue(null, false);
    }

    public List<Queue> getActiveQueue(Long chairId) {
        return getActiveQueue(chairId, false);
    }

    public List<Queue> getActiveQueue(Long chairId, boolean includeCompleted) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<String> activeStatuses = includeCompleted
                ? Arrays.asList("waiting", "called", "serving", "completed")
                : Arrays.asList("waiting", "called", "serving");
        if (chairId != null) {
            return queueRepository.findByChairIdAndStatusInAndCreatedAtAfterOrderByPositionAsc(
                    chairId, activeStatuses, startOfDay);
        }
        return queueRepository.findByStatusInAndCreatedAtAfterOrderByPositionAsc(
                activeStatuses, startOfDay);
    }

    public List<Queue> getWaitingQueue() {
        return getWaitingQueue(null);
    }

    public List<Queue> getWaitingQueue(LocalDate date) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<Queue> allWaiting = queueRepository.findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
                Arrays.asList("waiting", "called"), startOfDay);

        if (date == null || date.equals(LocalDate.now())) {
            return allWaiting;
        }

        List<Queue> filtered = new ArrayList<>();
        for (Queue q : allWaiting) {
            if (q.getTokenNumber() != null) {
                List<Appointment> appts = appointmentRepository.findByTokenNumber(q.getTokenNumber());
                if (!appts.isEmpty()) {
                    Appointment a = appts.get(0);
                    if (date.equals(a.getAppointmentDate())) {
                        filtered.add(q);
                        continue;
                    }
                }
            }
            if (q.getCreatedAt() != null && q.getCreatedAt().toLocalDate().equals(date)) {
                filtered.add(q);
            }
        }
        return filtered.isEmpty() ? allWaiting : filtered;
    }

    public Queue getCustomerQueueStatus(Long customerId) {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        // 1. Look for active entries (waiting, called, serving)
        Optional<Queue> active = queueRepository.findByCustomerIdAndStatusIn(
                customerId, Arrays.asList("waiting", "called", "serving"));
        if (active.isPresent() && active.get().getCreatedAt() != null && active.get().getCreatedAt().isAfter(startOfDay)) {
            return active.get();
        }
        // 2. Look for recently completed entry (completed today within last 15 seconds) so customer sees the "Served" step
        List<Queue> completedToday = queueRepository.findAllByCustomerIdAndStatusIn(
                customerId, Arrays.asList("completed"));
        if (completedToday != null && !completedToday.isEmpty()) {
            return completedToday.stream()
                    .filter(q -> q.getCompletedAt() != null
                            && q.getCompletedAt().isAfter(startOfDay)
                            && q.getCompletedAt().isAfter(LocalDateTime.now().minusSeconds(15)))
                    .max(Comparator.comparing(Queue::getCompletedAt))
                    .orElse(null);
        }
        return null;
    }

    public Map<String, Object> callNextCustomer() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        // Pick today's earliest waiting customer (pure FIFO token order)
        List<Queue> todayWaiting = queueRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc("waiting", startOfDay);
        Queue next = null;
        if (!todayWaiting.isEmpty()) {
            next = todayWaiting.get(0);
        } else {
            Optional<Queue> nextOpt = queueRepository.findFirstByStatusOrderByPositionAsc("waiting");
            if (nextOpt.isPresent()) {
                next = nextOpt.get();
            }
        }
        if (next == null) {
            return null; // Normal case: queue is empty, no exception
        }

        return callQueueEntry(next.getId(), null);
    }

    public Map<String, Object> callQueueEntry(Long queueId, Long preferredChairId) {
        Optional<Queue> entryOpt = queueRepository.findById(queueId);
        if (entryOpt.isEmpty()) {
            return null;
        }
        Queue entry = entryOpt.get();

        if (!"waiting".equalsIgnoreCase(entry.getStatus())) {
            return null;
        }

        Chair chair = null;
        if (preferredChairId != null) {
            chair = chairRepository.findById(preferredChairId)
                    .filter(c -> "available".equals(c.getStatus()) && c.isActive())
                    .orElse(null);
        }
        if (chair == null) {
            chair = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                    .stream().findFirst()
                    .orElse(null);
        }
        if (chair == null) {
            // No chairs available right now - normal flow, no exception
            return null;
        }

        entry.setStatus("called");
        entry.setChair(chair);
        entry.setCalledAt(LocalDateTime.now());
        queueRepository.save(entry);

        chair.setStatus("occupied");
        chair.setReservedByCustomer(entry.getCustomer());
        chair.setReservedAt(LocalDateTime.now());
        chairRepository.save(chair);

        recalculatePositions();

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");

        Map<String, Object> result = new HashMap<>();
        result.put("queueEntry", entry);
        result.put("chair", chair);
        return result;
    }

    public Map<String, Object> seatQueueEntry(Long queueId) {
        Queue entry = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry not found"));

        if ("serving".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException("Customer is already seated and in service");
        }

        Chair chair = entry.getChair();
        if (chair == null) {
            chair = chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available")
                    .stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("No chairs available to seat customer"));
            entry.setChair(chair);
        }

        entry.setStatus("serving");
        entry.setServedAt(LocalDateTime.now());
        queueRepository.save(entry);

        chair.setStatus("occupied");
        chair.setReservedByCustomer(entry.getCustomer());
        chair.setReservedAt(LocalDateTime.now());
        chairRepository.save(chair);

        // Create appointment
        Appointment appointment = new Appointment();
        appointment.setCustomer(entry.getCustomer());
        appointment.setService(entry.getService());
        appointment.setChair(chair);
        appointment.setTokenNumber(entry.getTokenNumber());
        appointment.setAppointmentDate(LocalDate.now());

        String timeStr = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a"));
        appointment.setTimeSlot(timeStr);
        appointment.setStatus("in_progress");
        appointment.setStartedAt(LocalDateTime.now());
        Appointment savedAppointment = appointmentRepository.save(appointment);

        // Update token
        List<Token> tokens = tokenRepository.findByQueueId(entry.getId());
        if (tokens.isEmpty() && entry.getTokenNumber() != null) {
            tokens = tokenRepository.findByTokenNumber(entry.getTokenNumber());
        }
        for (Token token : tokens) {
            token.setAppointment(savedAppointment);
            token.setStatus("used");
            tokenRepository.save(token);
        }

        recalculatePositions();

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");

        Map<String, Object> result = new HashMap<>();
        result.put("queueEntry", entry);
        result.put("chair", chair);
        result.put("appointment", savedAppointment);
        return result;
    }

    public Map<String, Object> completeQueueEntry(Long queueId) {
        Queue entry = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry not found"));

        if ("completed".equalsIgnoreCase(entry.getStatus())) {
            throw new IllegalStateException("Queue entry is already completed");
        }

        entry.setStatus("completed");
        entry.setCompletedAt(LocalDateTime.now());
        queueRepository.save(entry);

        Chair freedChair = entry.getChair();
        if (freedChair != null) {
            freedChair.setStatus("available");
            freedChair.setReservedByCustomer(null);
            freedChair.setReservedAt(null);
            chairRepository.save(freedChair);
        }

        // Complete any related appointments
        if (entry.getTokenNumber() != null) {
            List<Appointment> appts = appointmentRepository.findByTokenNumber(entry.getTokenNumber());
            for (Appointment appt : appts) {
                if (!"completed".equalsIgnoreCase(appt.getStatus())) {
                    appt.setStatus("completed");
                    appt.setCompletedAt(LocalDateTime.now());
                    appointmentRepository.save(appt);
                }
            }
        }

        // Complete token
        List<Token> tokens = tokenRepository.findByQueueId(entry.getId());
        if (tokens.isEmpty() && entry.getTokenNumber() != null) {
            tokens = tokenRepository.findByTokenNumber(entry.getTokenNumber());
        }
        for (Token token : tokens) {
            token.setStatus("used");
            tokenRepository.save(token);
        }

        recalculatePositions();

        webSocketHandler.broadcast("chair-update");
        webSocketHandler.broadcast("queue-update");
        webSocketHandler.broadcast("appointment-update");

        // Auto-call next waiting customer or rotate pipeline if main chair is freed
        if (freedChair != null) {
            if (freedChair.getChairNumber() <= 2) {
                appointmentService.rotateAndPromoteWaitingChairs(freedChair.getId(), LocalDate.now(), null);
            } else {
                callNextCustomer();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("queueEntry", entry);
        result.put("message", "Service completed and chair freed");
        return result;
    }

    public Queue cancelQueueEntry(Long queueId, Long customerId) {
        Queue entry = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue entry not found"));

        if (customerId != null && !entry.getCustomer().getId().equals(customerId)) {
            throw new IllegalArgumentException("Access denied. You do not own this queue entry.");
        }

        if (Arrays.asList("completed", "cancelled").contains(entry.getStatus())) {
            // Already finished or cancelled - return gracefully without throwing error
            return entry;
        }

        entry.setStatus("cancelled");
        entry.setCompletedAt(LocalDateTime.now());
        Queue saved = queueRepository.save(entry);

        // Release chair if assigned
        if (entry.getChair() != null) {
            Chair chair = entry.getChair();
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            chairRepository.save(chair);
            webSocketHandler.broadcast("chair-update");
        }

        // Cancel token
        List<Token> cancelTokens = tokenRepository.findByQueueId(entry.getId());
        if (cancelTokens.isEmpty() && entry.getTokenNumber() != null) {
            cancelTokens = tokenRepository.findByTokenNumber(entry.getTokenNumber());
        }
        for (Token token : cancelTokens) {
            token.setStatus("cancelled");
            tokenRepository.save(token);
        }

        recalculatePositions();

        webSocketHandler.broadcast("queue-update");

        return saved;
    }

    public void recalculatePositions() {
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        List<Chair> chairs = chairRepository.findAll();
        for (Chair chair : chairs) {
            List<Queue> chairWaiting = queueRepository.findByChairIdAndStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                    chair.getId(), "waiting", startOfDay);
            for (int i = 0; i < chairWaiting.size(); i++) {
                Queue entry = chairWaiting.get(i);
                int newPos = i + 1;
                entry.setPosition(newPos);
                int dur = (entry.getService() != null && entry.getService().getDurationMinutes() > 0)
                        ? entry.getService().getDurationMinutes() : 30;
                entry.setEstimatedWaitMinutes(Math.max(0, (newPos - 1) * dur));
                queueRepository.save(entry);
            }
        }

        // Also handle queues without assigned chair
        List<Queue> unassigned = queueRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc("waiting", startOfDay);
        int unassignedPos = 1;
        for (Queue entry : unassigned) {
            if (entry.getChair() == null) {
                entry.setPosition(unassignedPos);
                int dur = (entry.getService() != null && entry.getService().getDurationMinutes() > 0)
                        ? entry.getService().getDurationMinutes() : 30;
                entry.setEstimatedWaitMinutes(Math.max(0, (unassignedPos - 1) * dur));
                queueRepository.save(entry);
                unassignedPos++;
            }
        }
    }

    public Map<String, Object> getQueueStats() {
        long waiting = queueRepository.countByStatus("waiting");
        long serving = queueRepository.countByStatus("serving");
        
        LocalDateTime cutoff = LocalDate.now().atStartOfDay();
        long todayCompleted = queueRepository.countByStatusAndCompletedAtAfter("completed", cutoff);

        Map<String, Object> stats = new HashMap<>();
        stats.put("waiting", waiting);
        stats.put("serving", serving);
        stats.put("todayCompleted", todayCompleted);
        return stats;
    }
}
