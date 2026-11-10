package com.gatekeeper.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketTypeRepository extends JpaRepository<TicketTypeEntity, UUID> {

    List<TicketTypeEntity> findByEventIdOrderByPriceCentsAscCreatedAtAsc(UUID eventId);

    Optional<TicketTypeEntity> findByIdAndEventId(UUID id, UUID eventId);

    @Query("SELECT COALESCE(SUM(t.capacity), 0) FROM TicketTypeEntity t WHERE t.eventId = :eventId")
    int sumCapacityByEventId(@Param("eventId") UUID eventId);

    boolean existsByEventIdAndNameIgnoreCase(UUID eventId, String name);
}

