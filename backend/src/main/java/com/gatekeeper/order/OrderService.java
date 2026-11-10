package com.gatekeeper.order;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.GoneException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.redis.WaitingRoomService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class OrderService {

    private final OrderTransactionService txService;
    private final IdempotencyService idempotencyService;
    private final OrderRepository orderRepository;
    private final EventAuthorizationService authorizationService;
    private final WaitingRoomService waitingRoomService;

    public OrderService(
            OrderTransactionService txService,
            IdempotencyService idempotencyService,
            OrderRepository orderRepository,
            EventAuthorizationService authorizationService,
            WaitingRoomService waitingRoomService
    ) {
        this.txService = txService;
        this.idempotencyService = idempotencyService;
        this.orderRepository = orderRepository;
        this.authorizationService = authorizationService;
        this.waitingRoomService = waitingRoomService;
    }

    public OrderDtos.OrderResponse placeHold(
            GateKeeperPrincipal principal,
            String idempotencyKey,
            String waitingRoomToken,
            OrderDtos.PlaceHoldRequest request
    ) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new BadRequestException("Idempotency-Key header is required (1-128 chars)");
        }
        String normalizedKey = idempotencyKey.trim();
        String requestHash = idempotencyService.hashRequest(principal.userId(), request);

        Optional<OrderDtos.OrderResponse> cached = idempotencyService.findExisting(
                normalizedKey, principal.userId(), requestHash);
        if (cached.isPresent()) {
            return cached.get();
        }

        waitingRoomService.validateAdmissionForBooking(request.eventId(), principal.userId(), waitingRoomToken);

        try {
            OrderDtos.OrderResponse created = txService.createHoldOrder(
                    principal.userId(), normalizedKey, requestHash, request);
            idempotencyService.cacheInRedis(normalizedKey, principal.userId(), requestHash, created);
            return created;
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate request with identical Idempotency-Key lost the race; return the winner.
            Optional<OrderDtos.OrderResponse> winner = waitForWinningIdempotencyRecord(
                    normalizedKey, principal.userId(), requestHash);
            if (winner.isPresent()) {
                return winner.get();
            }
            throw new ConflictException("Duplicate idempotent booking request conflict; please retry");
        }
    }

    public OrderDtos.OrderResponse confirmOrder(UUID orderId, GateKeeperPrincipal principal) {
        try {
            return txService.confirmOrder(orderId, principal);
        } catch (GoneException expiredEx) {
            txService.expireOrderIfDue(orderId, Instant.now());
            throw expiredEx;
        }
    }

    public OrderDtos.OrderResponse markOrderPaid(
            UUID eventId,
            UUID orderId,
            GateKeeperPrincipal principal,
            OrderDtos.MarkPaidRequest request
    ) {
        try {
            return txService.markOrderPaid(eventId, orderId, principal, request);
        } catch (GoneException expiredEx) {
            txService.expireOrderIfDue(orderId, Instant.now());
            throw expiredEx;
        }
    }

    public OrderDtos.OrderResponse cancelOrder(UUID orderId, GateKeeperPrincipal principal) {
        return txService.cancelOrder(orderId, principal);
    }

    @Transactional(readOnly = true)
    public OrderDtos.OrderResponse getMyOrder(UUID orderId, GateKeeperPrincipal principal) {
        OrderEntity order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found"));
        if (!order.getUserId().equals(principal.userId())) {
            throw new ForbiddenException("You can only view your own orders");
        }
        return txService.loadOrderResponse(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDtos.OrderResponse> listMyOrders(
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        Page<OrderEntity> orders = orderRepository.findByUserIdOrderByCreatedAtDesc(principal.userId(), pageable);
        return PageResponse.from(orders.map(txService::loadOrderResponse));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderDtos.OrderResponse> listEventOrders(
            UUID eventId,
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size));
        Page<OrderEntity> orders = orderRepository.findByEventIdOrderByCreatedAtDesc(eventId, pageable);
        return PageResponse.from(orders.map(txService::loadOrderResponse));
    }

    private Optional<OrderDtos.OrderResponse> waitForWinningIdempotencyRecord(
            String key,
            UUID userId,
            String requestHash
    ) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<OrderDtos.OrderResponse> existing = idempotencyService.findExisting(key, userId, requestHash);
            if (existing.isPresent()) {
                return existing;
            }
            try {
                Thread.sleep(30L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }
}

