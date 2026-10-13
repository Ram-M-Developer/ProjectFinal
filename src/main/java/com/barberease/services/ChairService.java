package com.barberease.services;

import com.barberease.models.Chair;
import com.barberease.models.Customer;
import com.barberease.repositories.ChairRepository;
import com.barberease.repositories.CustomerRepository;
import com.barberease.websocket.BarberWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import com.barberease.repositories.AppointmentRepository;
import com.barberease.repositories.QueueRepository;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class ChairService {

    @Autowired
    private ChairRepository chairRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private QueueRepository queueRepository;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    @Value("${app.reservation-timeout-ms}")
    private long reservationTimeoutMs;

    public void reconcileChairStatuses() {
        List<Chair> chairs = chairRepository.findAll();
        boolean changed = false;
        for (Chair chair : chairs) {
            boolean isWaitingChair = "waiting".equalsIgnoreCase(chair.getChairType()) || chair.getChairNumber() >= 3;
            if ("occupied".equals(chair.getStatus()) || "waiting".equals(chair.getStatus())) {
                java.time.LocalDate today = java.time.LocalDate.now();
                List<String> allowedApptStatuses = isWaitingChair 
                        ? Arrays.asList("in_progress", "confirmed", "waiting") 
                        : Arrays.asList("in_progress", "confirmed");
                List<String> allowedQueueStatuses = isWaitingChair 
                        ? Arrays.asList("serving", "called", "waiting") 
                        : Arrays.asList("serving", "called");

                boolean hasActiveAppt = appointmentRepository.findByChairId(chair.getId()).stream()
                        .anyMatch(a -> today.equals(a.getAppointmentDate()) && allowedApptStatuses.contains(a.getStatus()));
                boolean hasActiveQueue = queueRepository.findByChairIdAndStatusInOrderByPositionAsc(
                        chair.getId(), allowedQueueStatuses).stream()
                        .anyMatch(q -> q.getCreatedAt() != null && q.getCreatedAt().toLocalDate().equals(today));

                if (!hasActiveAppt && !hasActiveQueue) {
                    chair.setStatus("available");
                    chair.setReservedByCustomer(null);
                    chair.setReservedAt(null);
                    chairRepository.save(chair);
                    changed = true;
                }
            }
        }
        if (changed) {
            webSocketHandler.broadcast("chair-update");
        }
    }

    public List<Chair> getAllChairs() {
        reconcileChairStatuses();
        return chairRepository.findByIsActiveTrueOrderByChairNumberAsc();
    }

    public Chair getChairById(Long id) {
        return chairRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Chair not found"));
    }

    public List<Chair> getAvailableChairs() {
        reconcileChairStatuses();
        return chairRepository.findByStatusAndIsActiveTrueOrderByChairNumberAsc("available");
    }

    public Chair updateChairStatus(Long chairId, String newStatus) {
        Chair chair = getChairById(chairId);

        chair.setStatus(newStatus);
        if ("available".equals(newStatus)) {
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
        }

        return chairRepository.save(chair);
    }

    public Chair createChair(Chair data) {
        data.setStatus("available");
        data.setActive(true);
        if (data.getName() == null) {
            data.setName("Chair " + data.getChairNumber());
        }
        return chairRepository.save(data);
    }

    public Map<String, Integer> getChairStats() {
        List<Chair> chairs = getAllChairs();

        Map<String, Integer> stats = new HashMap<>();
        stats.put("total", chairs.size());
        stats.put("available", 0);
        stats.put("reserved", 0);
        stats.put("occupied", 0);
        stats.put("maintenance", 0);

        for (Chair chair : chairs) {
            String status = chair.getStatus();
            stats.put(status, stats.getOrDefault(status, 0) + 1);
        }

        return stats;
    }

    @Scheduled(fixedRate = 15000)
    public void cleanExpiredReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusNanos(reservationTimeoutMs * 1_000_000);
        List<Chair> expired = chairRepository.findByStatusAndReservedAtBefore("reserved", cutoff);

        if (!expired.isEmpty()) {
            for (Chair chair : expired) {
                chair.setStatus("available");
                chair.setReservedByCustomer(null);
                chair.setReservedAt(null);
                chairRepository.save(chair);
            }
            System.out.println("⏰ Cleaned up " + expired.size() + " expired chair reservations.");
            webSocketHandler.broadcast("chair-update");
        }

        reconcileChairStatuses();
    }

    public Chair reserveChair(Long chairId, Long customerId) {
        Chair chair = getChairById(chairId);
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found"));

        if (!chair.isActive() || "maintenance".equals(chair.getStatus())) {
            throw new IllegalStateException("Chair station is currently under maintenance.");
        }

        chair.setReservedByCustomer(customer);
        chair.setReservedAt(LocalDateTime.now());

        return chairRepository.save(chair);
    }

    public Chair releaseChair(Long chairId, Long customerId) {
        Chair chair = getChairById(chairId);

        if ("reserved".equals(chair.getStatus()) && 
                chair.getReservedByCustomer() != null && 
                chair.getReservedByCustomer().getId().equals(customerId)) {
            chair.setStatus("available");
            chair.setReservedByCustomer(null);
            chair.setReservedAt(null);
            return chairRepository.save(chair);
        }

        return chair;
    }
}
