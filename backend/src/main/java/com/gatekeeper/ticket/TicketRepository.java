package com.gatekeeper.ticket;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<TicketEntity, UUID> {

    Page<TicketEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<TicketEntity> findByUserIdAndEventIdOrderByCreatedAtDesc(UUID userId, UUID eventId, Pageable pageable);

    Page<TicketEntity> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    List<TicketEntity> findByOrderId(UUID orderId);

    Optional<TicketEntity> findByTicketCode(String ticketCode);

    boolean existsByOrderIdAndStatus(UUID orderId, TicketStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tickets
            SET status = 'CHECKED_IN',
                checked_in_at = :now,
                checked_in_by = :staffUserId,
                updated_at = :now
            WHERE id = :ticketId
              AND event_id = :eventId
              AND status = 'VALID'
            """, nativeQuery = true)
    int markCheckedInAtomically(
            @Param("ticketId") String ticketId,
            @Param("eventId") String eventId,
            @Param("staffUserId") String staffUserId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tickets
            SET status = 'CANCELLED',
                updated_at = :now
            WHERE order_id = :orderId
              AND status = 'VALID'
            """, nativeQuery = true)
    int cancelValidTicketsForOrder(
            @Param("orderId") String orderId,
            @Param("now") Instant now
    );
}

