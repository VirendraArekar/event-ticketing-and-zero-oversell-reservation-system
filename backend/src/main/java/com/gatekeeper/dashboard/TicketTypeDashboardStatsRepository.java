package com.gatekeeper.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketTypeDashboardStatsRepository extends JpaRepository<TicketTypeDashboardStatsEntity, UUID> {
    List<TicketTypeDashboardStatsEntity> findByEventIdOrderBySoldCountDescRevenueCentsDesc(UUID eventId);
}

