package com.barberease.repositories;

import com.barberease.models.Chair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ChairRepository extends JpaRepository<Chair, Long> {
    List<Chair> findByIsActiveTrueOrderByChairNumberAsc();
    List<Chair> findByStatusAndIsActiveTrueOrderByChairNumberAsc(String status);
    List<Chair> findByChairTypeAndIsActiveTrueOrderByChairNumberAsc(String chairType);
    List<Chair> findByChairTypeAndStatusAndIsActiveTrueOrderByChairNumberAsc(String chairType, String status);
    List<Chair> findByStatusAndReservedAtBefore(String status, LocalDateTime cutoff);
    java.util.Optional<Chair> findByChairNumber(int chairNumber);
}
