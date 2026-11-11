package com.gatekeeper.waitlist;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistRepository extends JpaRepository<WaitlistEntryEntity, UUID> {

    boolean existsByUserIdAndTicketTypeIdAndStatusIn(UUID userId, UUID ticketTypeId, Collection<WaitlistStatus> statuses);

    List<WaitlistEntryEntity> findByTicketTypeIdAndStatusOrderByCreatedAtAsc(UUID ticketTypeId, WaitlistStatus status);

    Page<WaitlistEntryEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<WaitlistEntryEntity> findByEventIdOrderByCreatedAtAsc(UUID eventId, Pageable pageable);

    Optional<WaitlistEntryEntity> findByOfferOrderId(UUID offerOrderId);

    long countByTicketTypeIdAndStatusAndCreatedAtBefore(UUID ticketTypeId, WaitlistStatus status, java.time.Instant createdAt);
}

