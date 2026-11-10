package com.gatekeeper.order;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.GoneException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.persistence.TransactionAfterCommit;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.event.EventStatus;
import com.gatekeeper.event.TicketTypeEntity;
import com.gatekeeper.event.TicketTypeRepository;
import com.gatekeeper.messaging.DomainEvents;
import com.gatekeeper.messaging.KafkaTopics;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.redis.EventCacheService;
import com.gatekeeper.redis.RedisInventoryService;
import com.gatekeeper.ticket.TicketEntity;
import com.gatekeeper.ticket.TicketService;
import com.gatekeeper.waitlist.WaitlistRepository;
import com.gatekeeper.waitlist.WaitlistStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderTransactionService {

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentLedgerRepository ledgerRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final InventoryService inventoryService;
    private final IdempotencyService idempotencyService;
    private final PaymentProvider paymentProvider;
    private final EventAuthorizationService authorizationService;
    private final RedisInventoryService redisInventoryService;
    private final EventCacheService eventCacheService;
    private final TicketService ticketService;
    private final OutboxService outboxService;
    private final WaitlistRepository waitlistRepository;
    private final long holdDurationSeconds;

    public OrderTransactionService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            PaymentLedgerRepository ledgerRepository,
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository,
            InventoryService inventoryService,
            IdempotencyService idempotencyService,
            PaymentProvider paymentProvider,
            EventAuthorizationService authorizationService,
            RedisInventoryService redisInventoryService,
            EventCacheService eventCacheService,
            TicketService ticketService,
            OutboxService outboxService,
            WaitlistRepository waitlistRepository,
            @Value("${gatekeeper.booking.hold-duration-seconds:600}") long holdDurationSeconds
    ) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.ledgerRepository = ledgerRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.inventoryService = inventoryService;
        this.idempotencyService = idempotencyService;
        this.paymentProvider = paymentProvider;
        this.authorizationService = authorizationService;
        this.redisInventoryService = redisInventoryService;
        this.eventCacheService = eventCacheService;
        this.ticketService = ticketService;
        this.outboxService = outboxService;
        this.waitlistRepository = waitlistRepository;
        this.holdDurationSeconds = holdDurationSeconds;
    }

    @Transactional
    public OrderDtos.OrderResponse createHoldOrder(
            UUID userId,
            String idempotencyKey,
            String requestHash,
            OrderDtos.PlaceHoldRequest request
    ) {
        Instant now = Instant.now();
        EventEntity event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ConflictException("Event is not published for ticket bookings");
        }
        if (!event.getEndTime().isAfter(now)) {
            throw new ConflictException("Event has already ended");
        }

        Map<UUID, Integer> mergedQuantities = mergeQuantities(request.items());
        List<TicketTypeEntity> ticketTypes = new ArrayList<>();
        String currency = null;
        long totalCents = 0L;

        for (Map.Entry<UUID, Integer> entry : mergedQuantities.entrySet()) {
            UUID ttId = entry.getKey();
            int qty = entry.getValue();
            TicketTypeEntity tt = ticketTypeRepository.findByIdAndEventId(ttId, event.getId())
                    .orElseThrow(() -> new NotFoundException("Ticket type not found for this event: " + ttId));
            if (now.isBefore(tt.getSalesStart())) {
                throw new BadRequestException("Ticket sales have not started for " + tt.getName());
            }
            if (now.isAfter(tt.getSalesEnd())) {
                throw new BadRequestException("Ticket sales have ended for " + tt.getName());
            }
            if (qty > tt.getPerOrderLimit()) {
                throw new BadRequestException(
                        "Quantity " + qty + " exceeds per-order limit (" + tt.getPerOrderLimit() + ") for " + tt.getName()
                );
            }
            if (currency == null) {
                currency = tt.getCurrency();
            } else if (!currency.equals(tt.getCurrency())) {
                throw new BadRequestException("Cannot mix different currencies in one order");
            }
            ticketTypes.add(tt);
            totalCents += Math.multiplyExact(tt.getPriceCents(), (long) qty);
        }

        List<InventoryService.SeatRequest> seatRequests = ticketTypes.stream()
                .map(tt -> new InventoryService.SeatRequest(tt.getId(), tt.getName(), mergedQuantities.get(tt.getId())))
                .toList();

        RedisInventoryService.FrontDoorResult frontDoor = redisInventoryService.tryReserveFrontDoor(seatRequests);
        if (frontDoor.outcome() == RedisInventoryService.FrontDoorOutcome.REJECTED_SOLD_OUT) {
            String failedName = ticketTypes.stream()
                    .filter(t -> t.getId().equals(frontDoor.failedTicketTypeId()))
                    .map(TicketTypeEntity::getName)
                    .findFirst()
                    .orElse("Ticket");
            if (frontDoor.availableCount() <= 0) {
                throw new ConflictException(failedName + " is sold out");
            }
            throw new ConflictException("Only " + frontDoor.availableCount() + " left for " + failedName);
        }
        if (frontDoor.outcome() == RedisInventoryService.FrontDoorOutcome.RESERVED) {
            TransactionAfterCommit.onRollback(() -> redisInventoryService.rollbackFrontDoorReservation(seatRequests));
        }

        inventoryService.reserveSeatsInMySql(seatRequests, now);

        UUID orderId = UUID.randomUUID();
        Instant holdExpiresAt = now.plusSeconds(holdDurationSeconds);
        OrderEntity order = new OrderEntity(
                orderId,
                userId,
                event.getId(),
                OrderStatus.HELD,
                totalCents,
                currency != null ? currency : "USD",
                idempotencyKey,
                holdExpiresAt,
                now
        );
        orderRepository.save(order);

        List<OrderItemEntity> orderItems = new ArrayList<>();
        for (TicketTypeEntity tt : ticketTypes) {
            int qty = mergedQuantities.get(tt.getId());
            orderItems.add(new OrderItemEntity(
                    UUID.randomUUID(),
                    orderId,
                    tt.getId(),
                    qty,
                    tt.getPriceCents(),
                    Math.multiplyExact(tt.getPriceCents(), (long) qty)
            ));
        }
        orderItemRepository.saveAll(orderItems);

        PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                UUID.randomUUID(),
                orderId,
                event.getId(),
                paymentProvider.providerName(),
                LedgerEntryType.INITIATED,
                totalCents,
                order.getCurrency(),
                "HOLD-" + orderId.toString().substring(0, 8).toUpperCase(),
                "Seat hold created for " + holdDurationSeconds + "s",
                userId,
                now
        );
        ledgerRepository.save(ledger);

        OrderDtos.OrderResponse response = buildOrderResponse(
                order, event, orderItems, ticketTypes, List.of(ledger));
        idempotencyService.saveInDb(idempotencyKey, userId, requestHash, response, now);
        TransactionAfterCommit.run(() -> {
            redisInventoryService.recordHoldMarker(orderId, seatRequests);
            eventCacheService.evictEvent(event.getId());
        });
        return response;
    }

    @Transactional
    public OrderDtos.OrderResponse confirmOrder(UUID orderId, GateKeeperPrincipal principal) {
        Instant now = Instant.now();
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!order.getUserId().equals(principal.userId())) {
            throw new ForbiddenException("You can only confirm your own orders");
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            throw new GoneException("Your hold expired");
        }
        if (order.getStatus() != OrderStatus.HELD) {
            throw new ConflictException("Order in status " + order.getStatus() + " cannot be confirmed");
        }
        if (order.getHoldExpiresAt() != null && !order.getHoldExpiresAt().isAfter(now)) {
            throw new GoneException("Your hold expired");
        }

        EventEntity event = eventRepository.findById(order.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ConflictException("Event is no longer open for booking");
        }

        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByTicketTypeIdAsc(orderId);
        List<TicketTypeEntity> types = ticketTypeRepository.findAllById(
                items.stream().map(OrderItemEntity::getTicketTypeId).toList());
        Map<UUID, TicketTypeEntity> typeMap = types.stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        PaymentProvider.PaymentResult paymentResult = paymentProvider.processConfirmation(order, principal.userId(), now);

        if (paymentResult.decision() == PaymentProvider.PaymentDecision.CAPTURED) {
            List<InventoryService.SeatRequest> seatRequests = toSeatRequests(items, typeMap);
            inventoryService.confirmHeldSeatsInMySql(seatRequests, now);
            order.markConfirmed(now);
            orderRepository.saveAndFlush(order);
            List<TicketEntity> issuedTickets = ticketService.issueTicketsForConfirmedOrder(order, event, items, now);
            emitConfirmedOrderEvents(order, event, items, typeMap, issuedTickets, now);

            PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                    UUID.randomUUID(),
                    orderId,
                    event.getId(),
                    paymentProvider.providerName(),
                    LedgerEntryType.CAPTURED,
                    order.getTotalCents(),
                    order.getCurrency(),
                    paymentResult.referenceCode(),
                    paymentResult.notes(),
                    principal.userId(),
                    now
            );
            ledgerRepository.save(ledger);
        } else {
            Instant deadline = paymentResult.paymentDeadlineAt();
            if (event.getStartTime().isAfter(now.plusSeconds(300)) && event.getStartTime().isBefore(deadline)) {
                deadline = event.getStartTime();
            }
            order.markPendingPayment(deadline, now);
            orderRepository.saveAndFlush(order);
            updateLinkedWaitlistOffer(orderId, WaitlistStatus.CLAIMED, now);

            PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                    UUID.randomUUID(),
                    orderId,
                    event.getId(),
                    paymentProvider.providerName(),
                    LedgerEntryType.PENDING,
                    order.getTotalCents(),
                    order.getCurrency(),
                    paymentResult.referenceCode(),
                    paymentResult.notes(),
                    principal.userId(),
                    now
            );
            ledgerRepository.save(ledger);

            outboxService.enqueue(
                    KafkaTopics.ORDER_EVENTS,
                    event.getId(),
                    "OrderPendingPayment",
                    new DomainEvents.OrderPendingPaymentPayload(
                            orderId,
                            order.getUserId(),
                            event.getId(),
                            event.getTitle(),
                            order.getTotalCents(),
                            order.getCurrency(),
                            deadline
                    )
            );
        }

        List<PaymentLedgerEntity> allLedger = ledgerRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        TransactionAfterCommit.run(() -> {
            redisInventoryService.clearHoldMarker(orderId);
            eventCacheService.evictEvent(event.getId());
        });
        return buildOrderResponse(order, event, items, types, allLedger);
    }

    @Transactional
    public OrderDtos.OrderResponse markOrderPaid(
            UUID eventId,
            UUID orderId,
            GateKeeperPrincipal principal,
            OrderDtos.MarkPaidRequest request
    ) {
        EventEntity event = authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        Instant now = Instant.now();

        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!order.getEventId().equals(eventId)) {
            throw new NotFoundException("Order not found for this event");
        }
        if (order.getStatus() == OrderStatus.EXPIRED) {
            throw new GoneException("Payment deadline has expired");
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new ConflictException("Only PENDING_PAYMENT orders can be marked paid");
        }
        if (order.getPaymentDeadlineAt() != null && !order.getPaymentDeadlineAt().isAfter(now)) {
            throw new GoneException("Payment deadline has passed");
        }

        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByTicketTypeIdAsc(orderId);
        List<TicketTypeEntity> types = ticketTypeRepository.findAllById(
                items.stream().map(OrderItemEntity::getTicketTypeId).toList());
        Map<UUID, TicketTypeEntity> typeMap = types.stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        inventoryService.confirmHeldSeatsInMySql(toSeatRequests(items, typeMap), now);
        order.markConfirmed(now);
        orderRepository.saveAndFlush(order);
        List<TicketEntity> issuedTickets = ticketService.issueTicketsForConfirmedOrder(order, event, items, now);
        emitConfirmedOrderEvents(order, event, items, typeMap, issuedTickets, now);

        String ref = (request != null && request.referenceCode() != null && !request.referenceCode().isBlank())
                ? request.referenceCode().trim()
                : "OFF-PAID-" + orderId.toString().substring(0, 8).toUpperCase();
        String notes = (request != null && request.notes() != null && !request.notes().isBlank())
                ? request.notes().trim()
                : "Offline payment verified and marked paid by organizer";

        PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                UUID.randomUUID(),
                orderId,
                event.getId(),
                paymentProvider.providerName(),
                LedgerEntryType.CAPTURED,
                order.getTotalCents(),
                order.getCurrency(),
                ref,
                notes,
                principal.userId(),
                now
        );
        ledgerRepository.save(ledger);

        List<PaymentLedgerEntity> allLedger = ledgerRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        TransactionAfterCommit.run(() -> {
            redisInventoryService.clearHoldMarker(orderId);
            eventCacheService.evictEvent(event.getId());
        });
        return buildOrderResponse(order, event, items, types, allLedger);
    }

    @Transactional
    public OrderDtos.OrderResponse cancelOrder(UUID orderId, GateKeeperPrincipal principal) {
        Instant now = Instant.now();
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        EventEntity event = eventRepository.findById(order.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));

        boolean isOwner = order.getUserId().equals(principal.userId());
        boolean isOrganizer = event.getOrganizerId().equals(principal.userId());
        if (!isOwner && !isOrganizer) {
            throw new ForbiddenException("You do not have permission to cancel this order");
        }

        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByTicketTypeIdAsc(orderId);
        List<TicketTypeEntity> types = ticketTypeRepository.findAllById(
                items.stream().map(OrderItemEntity::getTicketTypeId).toList());
        Map<UUID, TicketTypeEntity> typeMap = types.stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));
        List<InventoryService.SeatRequest> seatRequests = toSeatRequests(items, typeMap);

        OrderStatus currentStatus = order.getStatus();
        LedgerEntryType ledgerType;
        String notes;

        if (currentStatus == OrderStatus.HELD || currentStatus == OrderStatus.PENDING_PAYMENT) {
            inventoryService.releaseHeldSeatsInMySql(seatRequests, now);
            order.markCancelled(OrderStatus.CANCELLED, now);
            ledgerType = LedgerEntryType.CANCELLED;
            notes = "Order cancelled and held seats released";
            updateLinkedWaitlistOffer(orderId, WaitlistStatus.CANCELLED, now);
            enqueueSeatsReleased(event.getId(), items, "ORDER_CANCELLED");
        } else if (currentStatus == OrderStatus.CONFIRMED) {
            if (!isOrganizer && event.getCancellationCutoffAt() != null
                    && now.isAfter(event.getCancellationCutoffAt())) {
                throw new ConflictException("Cancellation cutoff has passed for this event");
            }
            ticketService.cancelTicketsForRefundedOrder(orderId, now);
            inventoryService.refundSoldSeatsInMySql(seatRequests, now);
            order.markCancelled(OrderStatus.REFUNDED, now);
            ledgerType = LedgerEntryType.REFUNDED;
            notes = "Confirmed order cancelled and refunded; seats returned to inventory";
            outboxService.enqueue(
                    KafkaTopics.ORDER_EVENTS,
                    event.getId(),
                    "OrderRefunded",
                    new DomainEvents.OrderRefundedPayload(
                            orderId,
                            order.getUserId(),
                            event.getId(),
                            event.getTitle(),
                            order.getTotalCents(),
                            toItemMetrics(items, typeMap)
                    )
            );
            enqueueSeatsReleased(event.getId(), items, "ORDER_REFUNDED");
        } else {
            throw new ConflictException("Order in status " + currentStatus + " cannot be cancelled");
        }

        orderRepository.saveAndFlush(order);

        PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                UUID.randomUUID(),
                orderId,
                event.getId(),
                paymentProvider.providerName(),
                ledgerType,
                order.getTotalCents(),
                order.getCurrency(),
                "CXL-" + orderId.toString().substring(0, 8).toUpperCase(),
                notes,
                principal.userId(),
                now
        );
        ledgerRepository.save(ledger);

        List<PaymentLedgerEntity> allLedger = ledgerRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
        TransactionAfterCommit.run(() -> {
            redisInventoryService.clearHoldMarker(orderId);
            redisInventoryService.releaseSeatsInRedis(seatRequests);
            eventCacheService.evictEvent(event.getId());
        });
        return buildOrderResponse(order, event, items, types, allLedger);
    }

    @Transactional
    public boolean expireOrderIfDue(UUID orderId, Instant now) {
        OrderEntity order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        OrderStatus current = order.getStatus();
        boolean due = (current == OrderStatus.HELD
                && order.getHoldExpiresAt() != null
                && !order.getHoldExpiresAt().isAfter(now))
                || (current == OrderStatus.PENDING_PAYMENT
                && order.getPaymentDeadlineAt() != null
                && !order.getPaymentDeadlineAt().isAfter(now));
        if (!due) {
            return false;
        }

        int swapped = orderRepository.compareAndSwapStatus(
                orderId.toString(),
                current.name(),
                OrderStatus.EXPIRED.name(),
                now
        );
        if (swapped == 0) {
            return false;
        }

        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByTicketTypeIdAsc(orderId);
        List<TicketTypeEntity> types = ticketTypeRepository.findAllById(
                items.stream().map(OrderItemEntity::getTicketTypeId).toList());
        Map<UUID, TicketTypeEntity> typeMap = types.stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        List<InventoryService.SeatRequest> seatRequests = toSeatRequests(items, typeMap);
        inventoryService.releaseHeldSeatsInMySql(seatRequests, now);
        updateLinkedWaitlistOffer(orderId, WaitlistStatus.EXPIRED, now);
        enqueueSeatsReleased(order.getEventId(), items, "HOLD_EXPIRED");

        PaymentLedgerEntity ledger = new PaymentLedgerEntity(
                UUID.randomUUID(),
                orderId,
                order.getEventId(),
                paymentProvider.providerName(),
                LedgerEntryType.EXPIRED,
                order.getTotalCents(),
                order.getCurrency(),
                "EXP-" + orderId.toString().substring(0, 8).toUpperCase(),
                current == OrderStatus.HELD ? "Hold expired and seats released" : "Payment deadline expired and seats released",
                null,
                now
        );
        ledgerRepository.save(ledger);
        TransactionAfterCommit.run(() -> {
            redisInventoryService.clearHoldMarker(orderId);
            redisInventoryService.releaseSeatsInRedis(seatRequests);
            eventCacheService.evictEvent(order.getEventId());
        });
        return true;
    }

    public OrderDtos.OrderResponse loadOrderResponse(OrderEntity order) {
        EventEntity event = eventRepository.findById(order.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        List<OrderItemEntity> items = orderItemRepository.findByOrderIdOrderByTicketTypeIdAsc(order.getId());
        List<TicketTypeEntity> types = ticketTypeRepository.findAllById(
                items.stream().map(OrderItemEntity::getTicketTypeId).toList());
        List<PaymentLedgerEntity> ledger = ledgerRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
        return buildOrderResponse(order, event, items, types, ledger);
    }

    private void emitConfirmedOrderEvents(
            OrderEntity order,
            EventEntity event,
            List<OrderItemEntity> items,
            Map<UUID, TicketTypeEntity> typeMap,
            List<TicketEntity> issuedTickets,
            Instant now
    ) {
        updateLinkedWaitlistOffer(order.getId(), WaitlistStatus.CLAIMED, now);
        outboxService.enqueue(
                KafkaTopics.ORDER_EVENTS,
                event.getId(),
                "OrderConfirmed",
                new DomainEvents.OrderConfirmedPayload(
                        order.getId(),
                        order.getUserId(),
                        event.getId(),
                        event.getTitle(),
                        order.getTotalCents(),
                        order.getCurrency(),
                        now,
                        toItemMetrics(items, typeMap)
                )
        );
        outboxService.enqueue(
                KafkaTopics.TICKET_EVENTS,
                event.getId(),
                "TicketsIssued",
                new DomainEvents.TicketsIssuedPayload(
                        order.getId(),
                        order.getUserId(),
                        event.getId(),
                        issuedTickets.stream().map(TicketEntity::getId).toList()
                )
        );
    }

    private void enqueueSeatsReleased(UUID eventId, List<OrderItemEntity> items, String reason) {
        List<UUID> ticketTypeIds = items.stream()
                .map(OrderItemEntity::getTicketTypeId)
                .distinct()
                .toList();
        outboxService.enqueue(
                KafkaTopics.WAITLIST_EVENTS,
                eventId,
                "SeatsReleased",
                new DomainEvents.SeatsReleasedPayload(eventId, ticketTypeIds, reason)
        );
    }

    private void updateLinkedWaitlistOffer(UUID orderId, WaitlistStatus targetStatus, Instant now) {
        waitlistRepository.findByOfferOrderId(orderId).ifPresent(entry -> {
            if (entry.getStatus() == WaitlistStatus.OFFERED) {
                entry.transitionStatus(targetStatus, now);
                waitlistRepository.save(entry);
            }
        });
    }

    private List<DomainEvents.OrderItemMetric> toItemMetrics(
            List<OrderItemEntity> items,
            Map<UUID, TicketTypeEntity> typeMap
    ) {
        return items.stream().map(i -> {
            TicketTypeEntity tt = typeMap.get(i.getTicketTypeId());
            return new DomainEvents.OrderItemMetric(
                    i.getTicketTypeId(),
                    tt != null ? tt.getName() : "",
                    i.getQuantity(),
                    i.getLineTotalCents()
            );
        }).toList();
    }

    private List<InventoryService.SeatRequest> toSeatRequests(
            List<OrderItemEntity> items,
            Map<UUID, TicketTypeEntity> typeMap
    ) {
        return items.stream()
                .map(i -> {
                    TicketTypeEntity tt = typeMap.get(i.getTicketTypeId());
                    return new InventoryService.SeatRequest(
                            i.getTicketTypeId(),
                            tt != null ? tt.getName() : i.getTicketTypeId().toString(),
                            i.getQuantity()
                    );
                })
                .toList();
    }

    private Map<UUID, Integer> mergeQuantities(List<OrderDtos.HoldItemRequest> items) {
        Map<UUID, Integer> map = new LinkedHashMap<>();
        items.stream()
                .sorted((a, b) -> a.ticketTypeId().toString().compareTo(b.ticketTypeId().toString()))
                .forEach(item -> map.merge(item.ticketTypeId(), item.quantity(), Integer::sum));
        return map;
    }

    private OrderDtos.OrderResponse buildOrderResponse(
            OrderEntity order,
            EventEntity event,
            List<OrderItemEntity> items,
            List<TicketTypeEntity> types,
            List<PaymentLedgerEntity> ledger
    ) {
        Map<UUID, TicketTypeEntity> typeMap = types.stream()
                .collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));

        List<OrderDtos.OrderItemResponse> itemDtos = items.stream()
                .map(i -> {
                    TicketTypeEntity tt = typeMap.get(i.getTicketTypeId());
                    return new OrderDtos.OrderItemResponse(
                            i.getId(),
                            i.getTicketTypeId(),
                            tt != null ? tt.getName() : "",
                            i.getQuantity(),
                            i.getUnitPriceCents(),
                            i.getLineTotalCents()
                    );
                })
                .toList();

        List<OrderDtos.LedgerEntryResponse> ledgerDtos = ledger.stream()
                .map(l -> new OrderDtos.LedgerEntryResponse(
                        l.getId(),
                        l.getProvider(),
                        l.getEntryType(),
                        l.getAmountCents(),
                        l.getCurrency(),
                        l.getReferenceCode(),
                        l.getNotes(),
                        l.getActorUserId(),
                        l.getCreatedAt()
                ))
                .toList();

        return new OrderDtos.OrderResponse(
                order.getId(),
                order.getUserId(),
                order.getEventId(),
                event.getTitle(),
                order.getStatus(),
                order.getTotalCents(),
                order.getCurrency(),
                order.getHoldExpiresAt(),
                order.getPaymentDeadlineAt(),
                order.getConfirmedAt(),
                order.getCancelledAt(),
                order.getCreatedAt(),
                itemDtos,
                ledgerDtos
        );
    }
}

