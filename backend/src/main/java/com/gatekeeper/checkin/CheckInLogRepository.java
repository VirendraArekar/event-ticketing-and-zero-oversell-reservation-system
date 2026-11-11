package com.gatekeeper.checkin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CheckInLogRepository extends JpaRepository<CheckInLogEntity, UUID> {

    Page<CheckInLogEntity> findByEventIdOrderByScannedAtDesc(UUID eventId, Pageable pageable);
}

