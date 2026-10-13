package com.barberease.controllers;

import com.barberease.models.Queue;
import com.barberease.security.UserPrincipal;
import com.barberease.services.QueueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/queue")
public class QueueController {

    @Autowired
    private QueueService queueService;

    @Autowired
    private com.barberease.repositories.AppointmentRepository appointmentRepository;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    private String getAuthenticatedUserType() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getType();
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            return Long.parseLong((String) value);
        }
        throw new IllegalArgumentException("Invalid ID format");
    }

    @PostMapping("/join")
    public ResponseEntity<Map<String, Object>> join(@RequestBody Map<String, Object> request) {
        Long customerId = getAuthenticatedCustomerId();
        Long serviceId = parseId(request.get("service_id"));
        Long chairId = parseId(request.get("chair_id"));
        String timeSlot = (String) request.get("time_slot");
        String appointmentDate = (String) request.get("appointment_date");

        Queue joined = queueService.addToQueue(customerId, serviceId, chairId, timeSlot, appointmentDate);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Joined waiting list successfully");
        body.put("data", joined);
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getActive(
            @RequestParam(name = "chair_id", required = false) Long chairId,
            @RequestParam(name = "all", required = false, defaultValue = "false") boolean includeAll) {
        List<Queue> active = queueService.getActiveQueue(chairId, includeAll);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", active);
        return ResponseEntity.ok(body);
    }

    @Autowired
    private com.barberease.services.AppointmentService appointmentService;

    @GetMapping("/waiting")
    public ResponseEntity<Map<String, Object>> getWaiting(
            @RequestParam(name = "date", required = false) String dateStr) {
        java.time.LocalDate date = (dateStr != null && !dateStr.trim().isEmpty()) ? java.time.LocalDate.parse(dateStr.trim()) : java.time.LocalDate.now();
        List<Queue> waiting = queueService.getWaitingQueue(date);
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        int virtualPos = 1;

        List<Map<String, Object>> enrichedChairs = appointmentService.getEnrichedChairs(date);

        for (int i = 0; i < waiting.size(); i++) {
            Queue q = waiting.get(i);
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("position", i + 1);
            m.put("token_number", q.getTokenNumber());
            m.put("status", q.getStatus());
            String name = q.getCustomer() != null ? q.getCustomer().getName() : "Customer";
            m.put("customer_name", name);
            m.put("service_name", q.getService() != null ? q.getService().getName() : "Haircut");
            
            String requestedSlot = "Next Available";
            if (q.getTokenNumber() != null) {
                com.barberease.models.Appointment appt = appointmentRepository.findByTokenNumber(q.getTokenNumber()).stream().findFirst().orElse(null);
                if (appt != null && appt.getTimeSlot() != null) {
                    requestedSlot = appt.getTimeSlot();
                }
            }
            m.put("requested_slot", requestedSlot);
            m.put("chair_id", q.getChair() != null ? q.getChair().getId() : null);
            m.put("chair_name", q.getChair() != null ? q.getChair().getName() : null);
            boolean isSeated = (q.getChair() != null);
            m.put("waiting_type", isSeated ? "waiting_chair" : "waiting_list");
            m.put("is_seated_waiting", isSeated);
            if (!isSeated) {
                m.put("virtual_position", virtualPos++);
            }
            m.put("created_at", q.getCreatedAt() != null ? q.getCreatedAt().toString() : null);

            String estimatedTime = requestedSlot;
            if (q.getChair() != null) {
                for (Map<String, Object> ec : enrichedChairs) {
                    if (q.getChair().getId().equals(ec.get("id"))) {
                        String t = (String) ec.get("time");
                        if (t != null && !"---".equals(t)) {
                            estimatedTime = t;
                        }
                        break;
                    }
                }
            }
            m.put("estimated_time", estimatedTime);

            list.add(m);
        }

        // Ensure all waiting appointments for this date are represented in the waiting list
        List<com.barberease.models.Appointment> dateAppts = appointmentRepository.findByAppointmentDateOrderByTimeSlotAsc(date);
        for (com.barberease.models.Appointment a : dateAppts) {
            if (a.getStatus() != null && java.util.Arrays.asList("pending", "confirmed", "waiting").contains(a.getStatus().toLowerCase())) {
                boolean isWaitSeat = a.getChair() != null && a.getChair().getChairNumber() >= 3 && a.getChair().getChairNumber() <= 6;
                boolean isUnseated = a.getChair() == null;
                if (isWaitSeat || isUnseated) {
                    boolean alreadyInList = list.stream().anyMatch(entry -> {
                        Object t = entry.get("token_number");
                        return t != null && t.equals(a.getTokenNumber());
                    });
                    if (!alreadyInList) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id", a.getId());
                        m.put("position", list.size() + 1);
                        m.put("token_number", a.getTokenNumber() != null ? a.getTokenNumber() : "T---");
                        m.put("status", "waiting");
                        String name = a.getCustomer() != null ? a.getCustomer().getName() : "Customer";
                        m.put("customer_name", name);
                        m.put("service_name", a.getService() != null ? a.getService().getName() : "Haircut");
                        m.put("requested_slot", a.getTimeSlot() != null ? a.getTimeSlot() : "Next Available");
                        m.put("chair_id", isWaitSeat ? a.getChair().getId() : null);
                        m.put("chair_name", isWaitSeat ? ("Waiting Chair " + (a.getChair().getChairNumber() - 2)) : null);
                        m.put("waiting_type", isWaitSeat ? "waiting_chair" : "waiting_list");
                        m.put("is_seated_waiting", isWaitSeat);
                        if (!isWaitSeat) {
                            m.put("virtual_position", virtualPos++);
                        }
                        m.put("created_at", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
                        m.put("estimated_time", a.getTimeSlot() != null ? a.getTimeSlot() : "Next Available");
                        list.add(m);
                    }
                }
            }
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("count", list.size());
        body.put("data", list);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/my-status")
    public ResponseEntity<Map<String, Object>> getMyStatus() {
        Long customerId = getAuthenticatedCustomerId();
        Queue status = queueService.getCustomerQueueStatus(customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", status);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/call-next")
    public ResponseEntity<Map<String, Object>> callNext() {
        Map<String, Object> data = queueService.callNextCustomer();
        if (data == null) {
            Map<String, Object> body = new HashMap<>();
            body.put("success", false);
            body.put("message", "No waiting customers or no chairs currently available");
            return ResponseEntity.badRequest().body(body);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Called next customer");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/call")
    public ResponseEntity<Map<String, Object>> callCustomer(
            @PathVariable Long id,
            @RequestParam(value = "chair_id", required = false) Long chairId) {
        Map<String, Object> data = queueService.callQueueEntry(id, chairId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer called to station");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/seat")
    public ResponseEntity<Map<String, Object>> seatCustomer(@PathVariable Long id) {
        Map<String, Object> data = queueService.seatQueueEntry(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Customer seated and service started");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<Map<String, Object>> completeCustomer(@PathVariable Long id) {
        Map<String, Object> data = queueService.completeQueueEntry(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Service completed");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable Long id) {
        Long customerId = null;
        if ("customer".equals(getAuthenticatedUserType())) {
            customerId = getAuthenticatedCustomerId();
        }

        Queue cancelled = queueService.cancelQueueEntry(id, customerId);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Queue entry cancelled");
        body.put("data", cancelled);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = queueService.getQueueStats();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", stats);
        return ResponseEntity.ok(body);
    }
}
