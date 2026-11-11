package com.gatekeeper.checkin;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class CheckInAuditService {

    private final CheckInLogRepository checkInLogRepository;

    public CheckInAuditService(CheckInLogRepository checkInLogRepository) {
        this.checkInLogRepository = checkInLogRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAttempt(
            UUID ticketId,
            UUID eventId,
            UUID staffUserId,
            CheckInOutcome outcome,
            Instant scannedAt,
            String notes
    ) {
        checkInLogRepository.save(new CheckInLogEntity(
                UUID.randomUUID(),
                ticketId,
                eventId,
                staffUserId,
                outcome,
                scannedAt,
                notes != null && notes.length() > 255 ? notes.substring(0, 255) : notes
        ));
    }
}

