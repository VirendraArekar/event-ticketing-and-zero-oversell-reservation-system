package com.gatekeeper.order;

import java.time.Instant;
import java.util.UUID;

public interface PaymentProvider {

    String providerName();

    PaymentResult processConfirmation(OrderEntity order, UUID actorUserId, Instant now);

    enum PaymentDecision {
        CAPTURED,
        PENDING_PAYMENT
    }

    record PaymentResult(
            PaymentDecision decision,
            Instant paymentDeadlineAt,
            String referenceCode,
            String notes
    ) {
    }
}

