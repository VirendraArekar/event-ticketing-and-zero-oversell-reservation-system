package com.gatekeeper.messaging;

import com.gatekeeper.notification.NotificationService;
import com.gatekeeper.notification.NotificationType;
import com.gatekeeper.order.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final ConsumerDeduplicationService deduplicationService;
    private final NotificationService notificationService;
    private final OrderRepository orderRepository;
    private final JsonMapper jsonMapper;

    public NotificationConsumer(
            ConsumerDeduplicationService deduplicationService,
            NotificationService notificationService,
            OrderRepository orderRepository,
            JsonMapper jsonMapper
    ) {
        this.deduplicationService = deduplicationService;
        this.notificationService = notificationService;
        this.orderRepository = orderRepository;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(
            topics = {
                    KafkaTopics.ORDER_EVENTS,
                    KafkaTopics.CHECKIN_EVENTS,
                    KafkaTopics.WAITLIST_EVENTS,
                    KafkaTopics.EVENT_LIFECYCLE_EVENTS
            },
            groupId = KafkaTopics.GROUP_NOTIFICATIONS
    )
    @Transactional
    public void onMessage(String rawPayload) {
        DomainEvents.Envelope envelope = jsonMapper.readValue(rawPayload, DomainEvents.Envelope.class);
        if (!deduplicationService.tryClaimEvent(KafkaTopics.GROUP_NOTIFICATIONS, envelope.messageId())) {
            log.debug("Duplicate notification event skipped: {}", envelope.messageId());
            return;
        }

        Instant now = envelope.occurredAt() != null ? envelope.occurredAt() : Instant.now();

        switch (envelope.eventType()) {
            case "OrderConfirmed" -> {
                DomainEvents.OrderConfirmedPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.OrderConfirmedPayload.class);
                notificationService.createNotification(
                        p.userId(),
                        p.eventId(),
                        NotificationType.ORDER_CONFIRMED,
                        "Order Confirmed: " + p.eventTitle(),
                        "Your order is confirmed and your tickets are ready to view and download.",
                        p.orderId(),
                        now
                );
            }
            case "OrderPendingPayment" -> {
                DomainEvents.OrderPendingPaymentPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.OrderPendingPaymentPayload.class);
                notificationService.createNotification(
                        p.userId(),
                        p.eventId(),
                        NotificationType.PAYMENT_PENDING,
                        "Payment Pending: " + p.eventTitle(),
                        "Your seats are reserved. Complete offline payment before " + p.paymentDeadlineAt() + ".",
                        p.orderId(),
                        now
                );
            }
            case "HoldExpiring" -> {
                DomainEvents.HoldExpiringPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.HoldExpiringPayload.class);
                notificationService.createNotification(
                        p.userId(),
                        p.eventId(),
                        NotificationType.HOLD_EXPIRING,
                        "Hold Expiring Soon: " + p.eventTitle(),
                        "Your seat hold expires at " + p.holdExpiresAt() + ". Confirm your order soon to keep your seats.",
                        p.orderId(),
                        now
                );
            }
            case "WaitlistOffered" -> {
                DomainEvents.WaitlistOfferedPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.WaitlistOfferedPayload.class);
                notificationService.createNotification(
                        p.userId(),
                        p.eventId(),
                        NotificationType.WAITLIST_AVAILABLE,
                        "Waitlist Seats Available: " + p.ticketTypeName(),
                        p.quantity() + " " + p.ticketTypeName() + " seat(s) are now held for you until "
                                + p.offerExpiresAt() + ". Confirm your order before the hold expires.",
                        p.offerOrderId(),
                        now
                );
            }
            case "TicketCheckedIn" -> {
                DomainEvents.TicketCheckedInPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.TicketCheckedInPayload.class);
                notificationService.createNotification(
                        p.userId(),
                        p.eventId(),
                        NotificationType.CHECKIN_RECORDED,
                        "Checked In: " + p.eventTitle(),
                        "Ticket " + p.ticketCode() + " was checked in at " + p.checkedInAt() + ".",
                        p.ticketId(),
                        now
                );
            }
            case "EventUpdated", "EventCancelled" -> {
                DomainEvents.EventUpdatedOrCancelledPayload p = jsonMapper.treeToValue(
                        envelope.data(), DomainEvents.EventUpdatedOrCancelledPayload.class);
                List<UUID> attendeeIds = orderRepository.findActiveAttendeeUserIdsForEvent(p.eventId());
                NotificationType type = p.cancelled()
                        ? NotificationType.EVENT_CANCELLED
                        : NotificationType.EVENT_UPDATED;
                String title = p.cancelled()
                        ? "Event Cancelled: " + p.title()
                        : "Event Updated: " + p.title();
                String message = p.cancelled()
                        ? p.title() + " has been cancelled by the organizer."
                        : p.title() + " details have been updated by the organizer.";
                for (UUID userId : attendeeIds) {
                    notificationService.createNotification(userId, p.eventId(), type, title, message, p.eventId(), now);
                }
            }
            default -> log.debug("NotificationConsumer ignored eventType={}", envelope.eventType());
        }
    }
}

