package com.gatekeeper.waitlist;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.persistence.TransactionAfterCommit;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.event.EventStatus;
import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import com.gatekeeper.event.TicketTypeEntity;
import com.gatekeeper.event.TicketTypeRepository;
import com.gatekeeper.messaging.DomainEvents;
import com.gatekeeper.messaging.KafkaTopics;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.order.InventoryService;
import com.gatekeeper.order.LedgerEntryType;
import com.gatekeeper.order.OfflinePaymentProvider;
import com.gatekeeper.order.OrderEntity;
import com.gatekeeper.order.OrderItemEntity;
import com.gatekeeper.order.OrderItemRepository;
import com.gatekeeper.order.OrderRepository;
import com.gatekeeper.order.OrderStatus;
import com.gatekeeper.order.PaymentLedgerEntity;
import com.gatekeeper.order.PaymentLedgerRepository;
import com.gatekeeper.redis.EventCacheService;
import com.gatekeeper.redis.RedisDistributedLockService;
import com.gatekeeper.redis.RedisInventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class WaitlistService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistService.class);

    private final WaitlistRepository waitlistRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final TicketInventoryRepository ticketInventoryRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final OutboxService outboxService;
    private final RedisDistributedLockService lockService;
    private final RedisInventoryService redisInventoryService;
    private final EventCacheService eventCacheService;
    private final EventAuthorizationService authorizationService;
    private final TransactionTemplate transactionTemplate;
    private final long holdDurationSeconds;

    public WaitlistService(
            WaitlistRepository waitlistRepository,
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository,
            TicketInventoryRepository ticketInventoryRepository,
            InventoryService inventoryService,
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentLedgerRepository ledgerRepository,
            OutboxService outboxService,
            RedisDistributedLockService lockService,
            RedisInventoryService redisInventoryService,
            EventCacheService eventCacheService,
            EventAuthorizationService authorizationService,
            TransactionTemplate transactionTemplate,
            @Value("${gatekeeper.booking.hold-duration-seconds:600}") long holdDurationSeconds
    ) {
        this.waitlistRepository = waitlistRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.ticketInventoryRepository = ticketInventoryRepository;
        this.inventoryService = inventoryService;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.ledgerRepository = ledgerRepository;
        this.outboxService = outboxService;
        this.lockService = lockService;
        this.redisInventoryService = redisInventoryService;
        this.eventCacheService = eventCacheService;
        this.authorizationService = authorizationService;
        this.transactionTemplate = transactionTemplate;
        this.holdDurationSeconds = holdDurationSeconds;
    }

    @Transactional
    public WaitlistDtos.WaitlistEntryResponse joinWaitlist(
            UUID eventId,
            GateKeeperPrincipal principal,
            WaitlistDtos.JoinWaitlistRequest request
    ) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ConflictException("Waitlist is only available for published events");
        }
        TicketTypeEntity tt = ticketTypeRepository.findByIdAndEventId(request.ticketTypeId(), eventId)
                .orElseThrow(() -> new NotFoundException("Ticket type not found for this event"));
        if (request.quantity() > tt.getPerOrderLimit()) {
            throw new BadRequestException("Quantity exceeds per-order limit (" + tt.getPerOrderLimit() + ")");
        }

        TicketInventoryEntity inv = ticketInventoryRepository.findById(tt.getId())
                .orElseThrow(() -> new NotFoundException("Ticket inventory not found"));
        if (inv.getAvailable() > 0) {
            throw new ConflictException("Seats are currently available for " + tt.getName() + "; please book directly");
        }

        if (waitlistRepository.existsByUserIdAndTicketTypeIdAndStatusIn(
                principal.userId(), tt.getId(), List.of(WaitlistStatus.WAITING, WaitlistStatus.OFFERED))) {
            throw new ConflictException("You are already on the waitlist for " + tt.getName());
        }

        Instant now = Instant.now();
        WaitlistEntryEntity entry = new WaitlistEntryEntity(
                UUID.randomUUID(),
                eventId,
                tt.getId(),
                principal.userId(),
                request.quantity(),
                now
        );
        waitlistRepository.save(entry);
        return toDto(entry, event, tt);
    }

    @Transactional(readOnly = true)
    public PageResponse<WaitlistDtos.WaitlistEntryResponse> listMyWaitlist(
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        Page<WaitlistEntryEntity> res = waitlistRepository.findByUserIdOrderByCreatedAtDesc(
                principal.userId(), PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 50)));
        return PageResponse.from(mapPage(res));
    }

    @Transactional(readOnly = true)
    public PageResponse<WaitlistDtos.WaitlistEntryResponse> listEventWaitlist(
            UUID eventId,
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        Page<WaitlistEntryEntity> res = waitlistRepository.findByEventIdOrderByCreatedAtAsc(
                eventId, PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 50)));
        return PageResponse.from(mapPage(res));
    }

    @Transactional
    public void cancelWaitlistEntry(UUID entryId, GateKeeperPrincipal principal) {
        WaitlistEntryEntity entry = waitlistRepository.findById(entryId)
                .orElseThrow(() -> new NotFoundException("Waitlist entry not found"));
        if (!entry.getUserId().equals(principal.userId())) {
            throw new ForbiddenException("You can only cancel your own waitlist entries");
        }
        if (entry.getStatus() != WaitlistStatus.WAITING) {
            throw new ConflictException("Only WAITING waitlist entries can be cancelled");
        }
        entry.transitionStatus(WaitlistStatus.CANCELLED, Instant.now());
        waitlistRepository.save(entry);
    }

    @Transactional
    public void markOfferClaimed(UUID orderId, Instant now) {
        waitlistRepository.findByOfferOrderId(orderId).ifPresent(entry -> {
            if (entry.getStatus() == WaitlistStatus.OFFERED) {
                entry.transitionStatus(WaitlistStatus.CLAIMED, now);
                waitlistRepository.save(entry);
            }
        });
    }

    @Transactional
    public void markOfferClosed(UUID orderId, WaitlistStatus newStatus, Instant now) {
        waitlistRepository.findByOfferOrderId(orderId).ifPresent(entry -> {
            if (entry.getStatus() == WaitlistStatus.OFFERED) {
                entry.transitionStatus(newStatus, now);
                waitlistRepository.save(entry);
            }
        });
    }

    public void promoteForTicketType(UUID eventId, UUID ticketTypeId) {
        String lockKey = "gatekeeper:lock:waitlist:" + ticketTypeId;
        Optional<String> lockToken = lockService.tryAcquire(lockKey, Duration.ofSeconds(10));
        if (lockToken.isEmpty()) {
            return;
        }
        try {
            transactionTemplate.executeWithoutResult(status -> promoteInsideTx(eventId, ticketTypeId));
        } finally {
            lockService.release(lockKey, lockToken.get());
        }
    }

    private void promoteInsideTx(UUID eventId, UUID ticketTypeId) {
        EventEntity event = eventRepository.findById(eventId).orElse(null);
        TicketTypeEntity tt = ticketTypeRepository.findByIdAndEventId(ticketTypeId, eventId).orElse(null);
        if (event == null || tt == null || event.getStatus() != EventStatus.PUBLISHED) {
            return;
        }
        Instant now = Instant.now();
        if (now.isAfter(tt.getSalesEnd()) || !event.getEndTime().isAfter(now)) {
            return;
        }

        List<WaitlistEntryEntity> waiting = waitlistRepository
                .findByTicketTypeIdAndStatusOrderByCreatedAtAsc(ticketTypeId, WaitlistStatus.WAITING);
        if (waiting.isEmpty()) {
            return;
        }

        for (WaitlistEntryEntity entry : waiting) {
            TicketInventoryEntity inv = ticketInventoryRepository.findById(ticketTypeId).orElse(null);
            if (inv == null || inv.getAvailable() <= 0) {
                break;
            }
            if (entry.getQuantity() > inv.getAvailable()) {
                continue;
            }

            try {
                inventoryService.reserveSeatsInMySql(
                        List.of(new InventoryService.SeatRequest(tt.getId(), tt.getName(), entry.getQuantity())),
                        now
                );
            } catch (ConflictException ex) {
                continue;
            }

            UUID orderId = UUID.randomUUID();
            Instant expiresAt = now.plusSeconds(holdDurationSeconds);
            long totalCents = Math.multiplyExact(tt.getPriceCents(), (long) entry.getQuantity());
            OrderEntity order = new OrderEntity(
                    orderId,
                    entry.getUserId(),
                    eventId,
                    OrderStatus.HELD,
                    totalCents,
                    tt.getCurrency(),
                    "waitlist-offer-" + entry.getId(),
                    expiresAt,
                    now
            );
            orderRepository.save(order);

            orderItemRepository.save(new OrderItemEntity(
                    UUID.randomUUID(),
                    orderId,
                    tt.getId(),
                    entry.getQuantity(),
                    tt.getPriceCents(),
                    totalCents
            ));

            ledgerRepository.save(new PaymentLedgerEntity(
                    UUID.randomUUID(),
                    orderId,
                    eventId,
                    OfflinePaymentProvider.PROVIDER_NAME,
                    LedgerEntryType.INITIATED,
                    totalCents,
                    tt.getCurrency(),
                    "WL-" + orderId.toString().substring(0, 8).toUpperCase(),
                    "Waitlist hold offer granted for " + holdDurationSeconds + "s",
                    entry.getUserId(),
                    now
            ));

            entry.markOffered(orderId, now, expiresAt);
            waitlistRepository.save(entry);

            outboxService.enqueue(
                    KafkaTopics.WAITLIST_EVENTS,
                    eventId,
                    "WaitlistOffered",
                    new DomainEvents.WaitlistOfferedPayload(
                            entry.getId(),
                            eventId,
                            tt.getId(),
                            tt.getName(),
                            entry.getUserId(),
                            orderId,
                            entry.getQuantity(),
                            expiresAt
                    )
            );

            TransactionAfterCommit.run(() -> {
                redisInventoryService.syncTicketTypeFromMySql(tt.getId());
                eventCacheService.evictEvent(eventId);
            });
            log.info("Promoted waitlist entry {} for ticketType={} -> order={}", entry.getId(), tt.getId(), orderId);
        }
    }

    private Page<WaitlistDtos.WaitlistEntryResponse> mapPage(Page<WaitlistEntryEntity> page) {
        List<WaitlistEntryEntity> entries = page.getContent();
        if (entries.isEmpty()) {
            return page.map(e -> null);
        }
        Map<UUID, EventEntity> events = eventRepository.findAllById(
                        entries.stream().map(WaitlistEntryEntity::getEventId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(EventEntity::getId, Function.identity()));
        Map<UUID, TicketTypeEntity> types = ticketTypeRepository.findAllById(
                        entries.stream().map(WaitlistEntryEntity::getTicketTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));
        return page.map(e -> toDto(e, events.get(e.getEventId()), types.get(e.getTicketTypeId())));
    }

    private WaitlistDtos.WaitlistEntryResponse toDto(
            WaitlistEntryEntity entry,
            EventEntity event,
            TicketTypeEntity tt
    ) {
        long position = entry.getStatus() == WaitlistStatus.WAITING
                ? waitlistRepository.countByTicketTypeIdAndStatusAndCreatedAtBefore(
                        entry.getTicketTypeId(), WaitlistStatus.WAITING, entry.getCreatedAt()) + 1L
                : 0L;
        return new WaitlistDtos.WaitlistEntryResponse(
                entry.getId(),
                entry.getEventId(),
                event != null ? event.getTitle() : "",
                entry.getTicketTypeId(),
                tt != null ? tt.getName() : "",
                entry.getUserId(),
                entry.getQuantity(),
                entry.getStatus(),
                position,
                entry.getOfferOrderId(),
                entry.getOfferedAt(),
                entry.getOfferExpiresAt(),
                entry.getCreatedAt()
        );
    }
}

