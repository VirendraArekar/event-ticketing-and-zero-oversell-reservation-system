package com.gatekeeper.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PaymentLedgerRepository extends JpaRepository<PaymentLedgerEntity, UUID> {

    List<PaymentLedgerEntity> findByOrderIdOrderByCreatedAtAsc(UUID orderId);
}

