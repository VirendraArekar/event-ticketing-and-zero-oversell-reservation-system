package com.gatekeeper.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface NotificationRepository extends JpaRepository<NotificationEntity, UUID> {

    Page<NotificationEntity> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    @Modifying
    @Query("UPDATE NotificationEntity n SET n.read = true WHERE n.userId = :userId AND n.read = false")
    int markAllReadForUser(@Param("userId") UUID userId);
}

