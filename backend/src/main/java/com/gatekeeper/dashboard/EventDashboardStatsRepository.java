package com.gatekeeper.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventDashboardStatsRepository extends JpaRepository<EventDashboardStatsEntity, UUID> {
}

