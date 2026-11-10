package com.gatekeeper.order;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders/hold")
    public ResponseEntity<OrderDtos.OrderResponse> placeHold(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Waiting-Room-Token", required = false) String waitingRoomToken,
            @Valid @RequestBody OrderDtos.PlaceHoldRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.placeHold(principal, idempotencyKey, waitingRoomToken, request));
    }

    @PostMapping("/orders/{orderId}/confirm")
    public ResponseEntity<OrderDtos.OrderResponse> confirmOrder(@PathVariable UUID orderId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.confirmOrder(orderId, principal));
    }

    @PostMapping("/orders/{orderId}/cancel")
    public ResponseEntity<OrderDtos.OrderResponse> cancelOrder(@PathVariable UUID orderId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.cancelOrder(orderId, principal));
    }

    @GetMapping("/orders")
    public ResponseEntity<PageResponse<OrderDtos.OrderResponse>> listMyOrders(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.listMyOrders(principal, page, size));
    }

    @GetMapping("/orders/{orderId}")
    public ResponseEntity<OrderDtos.OrderResponse> getMyOrder(@PathVariable UUID orderId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.getMyOrder(orderId, principal));
    }

    @GetMapping("/organizer/events/{eventId}/orders")
    public ResponseEntity<PageResponse<OrderDtos.OrderResponse>> listEventOrders(
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.listEventOrders(eventId, principal, page, size));
    }

    @PostMapping("/organizer/events/{eventId}/orders/{orderId}/mark-paid")
    public ResponseEntity<OrderDtos.OrderResponse> markOrderPaid(
            @PathVariable UUID eventId,
            @PathVariable UUID orderId,
            @Valid @RequestBody(required = false) OrderDtos.MarkPaidRequest request
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(orderService.markOrderPaid(eventId, orderId, principal, request));
    }
}

