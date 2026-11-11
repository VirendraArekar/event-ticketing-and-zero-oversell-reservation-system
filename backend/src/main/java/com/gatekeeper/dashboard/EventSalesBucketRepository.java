package com.gatekeeper.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventSalesBucketRepository extends JpaRepository<EventSalesBucketEntity, EventBucketKey> {
    List<EventSalesBucketEntity> findByIdEventIdOrderByIdBucketStartAsc(UUID eventId);
}

