package com.gatekeeper.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TicketInventoryRepository extends JpaRepository<TicketInventoryEntity, UUID> {

    List<TicketInventoryEntity> findByEventId(UUID eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket_inventory
            SET held = held + :qty, updated_at = :now
            WHERE ticket_type_id = :ticketTypeId
              AND (held + sold + :qty) <= total
            """, nativeQuery = true)
    int holdSeats(
            @Param("ticketTypeId") String ticketTypeId,
            @Param("qty") int qty,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket_inventory
            SET held = held - :qty, sold = sold + :qty, updated_at = :now
            WHERE ticket_type_id = :ticketTypeId
              AND held >= :qty
            """, nativeQuery = true)
    int confirmHoldToSold(
            @Param("ticketTypeId") String ticketTypeId,
            @Param("qty") int qty,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket_inventory
            SET held = held - :qty, updated_at = :now
            WHERE ticket_type_id = :ticketTypeId
              AND held >= :qty
            """, nativeQuery = true)
    int releaseHold(
            @Param("ticketTypeId") String ticketTypeId,
            @Param("qty") int qty,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ticket_inventory
            SET sold = sold - :qty, updated_at = :now
            WHERE ticket_type_id = :ticketTypeId
              AND sold >= :qty
            """, nativeQuery = true)
    int refundSoldSeats(
            @Param("ticketTypeId") String ticketTypeId,
            @Param("qty") int qty,
            @Param("now") Instant now
    );
}
