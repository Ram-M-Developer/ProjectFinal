package com.barberease.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chairs")
public class Chair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chair_number", nullable = false, unique = true)
    private int chairNumber;

    @Column(length = 50)
    private String name;

    @Column(nullable = false, length = 20)
    private String status = "available";

    @JsonProperty("chair_type")
    @Column(name = "chair_type", length = 20)
    private String chairType = "main"; // "main" or "waiting"

    @JsonProperty("is_active")
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "reserved_by")
    private Customer reservedByCustomer;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getChairNumber() {
        return chairNumber;
    }

    public void setChairNumber(int chairNumber) {
        this.chairNumber = chairNumber;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public LocalDateTime getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(LocalDateTime reservedAt) {
        this.reservedAt = reservedAt;
    }

    public Customer getReservedByCustomer() {
        return reservedByCustomer;
    }

    public void setReservedByCustomer(Customer reservedByCustomer) {
        this.reservedByCustomer = reservedByCustomer;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getChairType() {
        return chairType != null ? chairType : "main";
    }

    public void setChairType(String chairType) {
        this.chairType = chairType;
    }
}
