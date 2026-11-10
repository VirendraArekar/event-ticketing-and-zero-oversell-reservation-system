package com.gatekeeper.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OrderItemRepository extends JpaRepository<OrderItemEntity, UUID> {

    List<OrderItemEntity> findByOrderIdOrderByTicketTypeIdAsc(UUID orderId);

    List<OrderItemEntity> findByOrderIdIn(Collection<UUID> orderIds);
}

