package com.barberease.controllers;

import com.barberease.models.Chair;
import com.barberease.security.UserPrincipal;
import com.barberease.services.ChairService;
import com.barberease.websocket.BarberWebSocketHandler;
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
@RequestMapping("/api/chairs")
public class ChairController {

    @Autowired
    private ChairService chairService;

    @Autowired
    private com.barberease.services.AppointmentService appointmentService;

    @Autowired
    private com.barberease.repositories.AppointmentRepository appointmentRepository;

    @Autowired
    private com.barberease.repositories.QueueRepository queueRepository;

    @Autowired
    private BarberWebSocketHandler webSocketHandler;

    private Long getAuthenticatedCustomerId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw new IllegalArgumentException("Authentication required");
        }
        return ((UserPrincipal) auth.getPrincipal()).getId();
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAll(@RequestParam(required = false) String date) {
        java.time.LocalDate queryDate = java.time.LocalDate.now();
        if (date != null && !date.trim().isEmpty()) {
            try {
                queryDate = java.time.LocalDate.parse(date.trim());
            } catch (Exception ignored) {}
        }

        // Auto-promote waiting customers into available main seats when their slot is current/imminent.
        // The promotion logic guards against future bookings (only promotes if slot ≤ now+20min).
        if (queryDate.equals(java.time.LocalDate.now())) {
            appointmentService.autoPromoteIfMainSeatsAvailable(queryDate);
        }
        List<Map<String, Object>> enriched = appointmentService.getEnrichedChairs(queryDate);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("count", enriched.size());
        body.put("data", enriched);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/available")
    public ResponseEntity<Map<String, Object>> getAvailable() {
        List<Chair> chairs = chairService.getAvailableChairs();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", chairs);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Integer> stats = chairService.getChairStats();

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", stats);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        Chair chair = chairService.getChairById(id);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("data", chair);
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Chair chair) {
        Chair created = chairService.createChair(chair);
        webSocketHandler.broadcast("chair-update");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Chair created");
        body.put("data", created);
        return new ResponseEntity<>(body, HttpStatus.CREATED);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable Long id, @RequestBody Map<String, String> request) {
        String status = request.get("status");
        Chair chair = chairService.updateChairStatus(id, status);
        webSocketHandler.broadcast("chair-update");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Chair status updated");
        body.put("data", chair);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/reserve")
    public ResponseEntity<Map<String, Object>> reserve(@PathVariable Long id) {
        Long customerId = getAuthenticatedCustomerId();
        Chair chair = chairService.reserveChair(id, customerId);
        webSocketHandler.broadcast("chair-update");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Chair reserved for 5 minutes");
        body.put("data", chair);
        return ResponseEntity.ok(body);
    }

    @PutMapping("/{id}/release")
    public ResponseEntity<Map<String, Object>> release(@PathVariable Long id) {
        Long customerId = getAuthenticatedCustomerId();
        Chair chair = chairService.releaseChair(id, customerId);
        webSocketHandler.broadcast("chair-update");

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Chair released successfully");
        body.put("data", chair);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/complete-service")
    public ResponseEntity<Map<String, Object>> completeService(
            @PathVariable Long id,
            @RequestParam(name = "date", required = false) String dateStr) {
        java.time.LocalDate date = (dateStr != null && !dateStr.trim().isEmpty()) ? java.time.LocalDate.parse(dateStr.trim()) : null;
        Chair chair = appointmentService.completeSeatService(id, date);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Seat service completed and waiting pipeline rotated");
        body.put("data", chair);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/reorganize")
    public ResponseEntity<Map<String, Object>> reorganize(
            @RequestParam(name = "date", required = false) String dateStr,
            @RequestParam(name = "slot", required = false) String slot) {
        java.time.LocalDate date = (dateStr != null && !dateStr.trim().isEmpty()) ? java.time.LocalDate.parse(dateStr.trim()) : java.time.LocalDate.now();
        appointmentService.rotateAndPromoteWaitingChairs(null, date, slot);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Waiting chairs reorganized and rotated successfully");
        return ResponseEntity.ok(body);
    }

    @PostMapping("/switch-waiting")
    public ResponseEntity<Map<String, Object>> switchWaiting(
            @RequestParam(name = "from", required = false, defaultValue = "2") Integer fromChair,
            @RequestParam(name = "to", required = false, defaultValue = "1") Integer toChair,
            @RequestParam(name = "date", required = false) String dateStr) {
        java.time.LocalDate date = (dateStr != null && !dateStr.trim().isEmpty()) ? java.time.LocalDate.parse(dateStr.trim()) : java.time.LocalDate.now();
        appointmentService.compactAndReorganizeWaitingChairs(date, fromChair, toChair);

        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("message", "Waiting seat " + fromChair + " switched to waiting seat " + toChair + " successfully");
        return ResponseEntity.ok(body);
    }
}
