package com.gatekeeper.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventCheckinBucketRepository extends JpaRepository<EventCheckinBucketEntity, EventBucketKey> {
    List<EventCheckinBucketEntity> findByIdEventIdOrderByIdBucketStartAsc(UUID eventId);
}

