package com.gatekeeper.event;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStaffAssignmentRepository extends JpaRepository<EventStaffAssignmentEntity, UUID> {

    boolean existsByEventIdAndUserId(UUID eventId, UUID userId);

    Optional<EventStaffAssignmentEntity> findByEventIdAndUserId(UUID eventId, UUID userId);

    List<EventStaffAssignmentEntity> findByEventIdOrderByCreatedAtAsc(UUID eventId);

    List<EventStaffAssignmentEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);
}

