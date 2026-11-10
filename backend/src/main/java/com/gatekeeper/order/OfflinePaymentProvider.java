package com.gatekeeper.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class OfflinePaymentProvider implements PaymentProvider {

    public static final String PROVIDER_NAME = "OfflinePayment";

    private final long paymentDeadlineSeconds;

    public OfflinePaymentProvider(
            @Value("${gatekeeper.booking.payment-deadline-seconds:86400}") long paymentDeadlineSeconds
    ) {
        this.paymentDeadlineSeconds = paymentDeadlineSeconds;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public PaymentResult processConfirmation(OrderEntity order, UUID actorUserId, Instant now) {
        String shortRef = "OFF-" + order.getId().toString().substring(0, 8).toUpperCase();
        if (order.getTotalCents() == 0L) {
            return new PaymentResult(
                    PaymentDecision.CAPTURED,
                    null,
                    shortRef + "-FREE",
                    "Zero-cost order confirmed immediately via OfflinePayment"
            );
        }
        Instant deadline = now.plusSeconds(paymentDeadlineSeconds);
        return new PaymentResult(
                PaymentDecision.PENDING_PAYMENT,
                deadline,
                shortRef,
                "Awaiting offline payment verification by event organizer before deadline"
        );
    }
}

