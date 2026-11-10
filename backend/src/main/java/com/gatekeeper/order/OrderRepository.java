package com.gatekeeper.order;

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

public interface OrderRepository extends JpaRepository<OrderEntity, UUID> {

    Page<OrderEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<OrderEntity> findByEventIdOrderByCreatedAtDesc(UUID eventId, Pageable pageable);

    Optional<OrderEntity> findByIdAndUserId(UUID id, UUID userId);

    @Query("""
            SELECT o.id FROM OrderEntity o
            WHERE o.status = com.gatekeeper.order.OrderStatus.HELD
              AND o.holdExpiresAt <= :cutoff
            ORDER BY o.holdExpiresAt ASC
            """)
    List<UUID> findExpiredHoldOrderIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
            SELECT o.id FROM OrderEntity o
            WHERE o.status = com.gatekeeper.order.OrderStatus.PENDING_PAYMENT
              AND o.paymentDeadlineAt <= :cutoff
            ORDER BY o.paymentDeadlineAt ASC
            """)
    List<UUID> findExpiredPaymentOrderIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Query("""
            SELECT o FROM OrderEntity o
            WHERE o.status = com.gatekeeper.order.OrderStatus.HELD
              AND o.holdExpiresAt > :now
              AND o.holdExpiresAt <= :warnCutoff
            """)
    List<OrderEntity> findHoldsExpiringSoon(@Param("now") Instant now, @Param("warnCutoff") Instant warnCutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE orders
            SET status = :targetStatus,
                hold_expires_at = NULL,
                payment_deadline_at = NULL,
                updated_at = :now,
                version = version + 1
            WHERE id = :orderId
              AND status = :expectedStatus
            """, nativeQuery = true)
    int compareAndSwapStatus(
            @Param("orderId") String orderId,
            @Param("expectedStatus") String expectedStatus,
            @Param("targetStatus") String targetStatus,
            @Param("now") Instant now
    );

    @Query("""
            SELECT DISTINCT o.userId FROM OrderEntity o
            WHERE o.eventId = :eventId
              AND o.status IN (com.gatekeeper.order.OrderStatus.CONFIRMED, com.gatekeeper.order.OrderStatus.PENDING_PAYMENT)
            """)
    List<UUID> findActiveAttendeeUserIdsForEvent(@Param("eventId") UUID eventId);
}

