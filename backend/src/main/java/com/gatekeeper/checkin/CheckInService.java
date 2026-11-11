package com.gatekeeper.checkin;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.UserEntity;
import com.gatekeeper.auth.UserRepository;
import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventStatus;
import com.gatekeeper.event.TicketTypeEntity;
import com.gatekeeper.event.TicketTypeRepository;
import com.gatekeeper.messaging.DomainEvents;
import com.gatekeeper.messaging.KafkaTopics;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.ticket.QrTokenService;
import com.gatekeeper.ticket.TicketEntity;
import com.gatekeeper.ticket.TicketRepository;
import com.gatekeeper.ticket.TicketStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class CheckInService {

    private final EventAuthorizationService authorizationService;
    private final QrTokenService qrTokenService;
    private final TicketRepository ticketRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserRepository userRepository;
    private final CheckInLogRepository checkInLogRepository;
    private final CheckInAuditService auditService;
    private final OutboxService outboxService;
    private final TransactionTemplate transactionTemplate;
    private final long earlyWindowMinutes;
    private final long lateWindowMinutes;

    public CheckInService(
            EventAuthorizationService authorizationService,
            QrTokenService qrTokenService,
            TicketRepository ticketRepository,
            TicketTypeRepository ticketTypeRepository,
            UserRepository userRepository,
            CheckInLogRepository checkInLogRepository,
            CheckInAuditService auditService,
            OutboxService outboxService,
            TransactionTemplate transactionTemplate,
            @Value("${gatekeeper.checkin.early-window-minutes:240}") long earlyWindowMinutes,
            @Value("${gatekeeper.checkin.late-window-minutes:120}") long lateWindowMinutes
    ) {
        this.authorizationService = authorizationService;
        this.qrTokenService = qrTokenService;
        this.ticketRepository = ticketRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userRepository = userRepository;
        this.checkInLogRepository = checkInLogRepository;
        this.auditService = auditService;
        this.outboxService = outboxService;
        this.transactionTemplate = transactionTemplate;
        this.earlyWindowMinutes = earlyWindowMinutes;
        this.lateWindowMinutes = lateWindowMinutes;
    }

    public CheckInDtos.CheckInResponse checkIn(
            UUID eventId,
            GateKeeperPrincipal staffPrincipal,
            CheckInDtos.CheckInRequest request
    ) {
        EventEntity event = authorizationService.requireStaffAssignedToEvent(eventId, staffPrincipal);

        boolean hasQr = request.qrToken() != null && !request.qrToken().isBlank();
        boolean hasCode = request.ticketCode() != null && !request.ticketCode().isBlank();
        if (!hasQr && !hasCode) {
            throw new BadRequestException("Either qrToken or ticketCode must be provided");
        }

        Instant now = Instant.now();
        TicketEntity ticket;

        if (hasQr) {
            QrTokenService.VerifiedQrPayload verified = qrTokenService.verifyToken(request.qrToken())
                    .orElseThrow(() -> new BadRequestException("Invalid or forged QR ticket signature"));
            ticket = ticketRepository.findById(verified.ticketId())
                    .orElseThrow(() -> new NotFoundException("Ticket not found"));
            if (!verified.eventId().equals(eventId) || !ticket.getEventId().equals(eventId)) {
                auditService.recordAttempt(
                        ticket.getId(), eventId, staffPrincipal.userId(),
                        CheckInOutcome.INVALID_EVENT, now, "Ticket belongs to a different event"
                );
                throw new BadRequestException("Ticket is not valid for this event");
            }
            if (now.isAfter(verified.expiresAt())) {
                auditService.recordAttempt(
                        ticket.getId(), eventId, staffPrincipal.userId(),
                        CheckInOutcome.INVALID_WINDOW, now, "QR token expired"
                );
                throw new ConflictException("Ticket QR token has expired");
            }
        } else {
            String code = request.ticketCode().trim().toUpperCase(Locale.ROOT);
            ticket = ticketRepository.findByTicketCode(code)
                    .orElseThrow(() -> new NotFoundException("Ticket not found for code: " + code));
            if (!ticket.getEventId().equals(eventId)) {
                auditService.recordAttempt(
                        ticket.getId(), eventId, staffPrincipal.userId(),
                        CheckInOutcome.INVALID_EVENT, now, "Manual ticket code belongs to a different event"
                );
                throw new BadRequestException("Ticket is not valid for this event");
            }
            QrTokenService.VerifiedQrPayload verified = qrTokenService.verifyToken(ticket.getQrToken())
                    .orElseThrow(() -> new BadRequestException("Stored ticket signature failed verification"));
            if (now.isAfter(verified.expiresAt())) {
                auditService.recordAttempt(
                        ticket.getId(), eventId, staffPrincipal.userId(),
                        CheckInOutcome.INVALID_WINDOW, now, "Ticket QR token expired"
                );
                throw new ConflictException("Ticket has expired");
            }
        }

        if (event.getStatus() != EventStatus.PUBLISHED) {
            auditService.recordAttempt(
                    ticket.getId(), eventId, staffPrincipal.userId(),
                    CheckInOutcome.INVALID_STATUS, now, "Event status is " + event.getStatus()
            );
            throw new ConflictException("Check-in is only permitted when event is PUBLISHED");
        }

        Instant windowStart = event.getStartTime().minusSeconds(earlyWindowMinutes * 60L);
        Instant windowEnd = event.getEndTime().plusSeconds(lateWindowMinutes * 60L);
        if (now.isBefore(windowStart)) {
            auditService.recordAttempt(
                    ticket.getId(), eventId, staffPrincipal.userId(),
                    CheckInOutcome.INVALID_WINDOW, now, "Check-in window opens at " + windowStart
            );
            throw new ConflictException("Check-in window is not open yet (opens at " + windowStart + ")");
        }
        if (now.isAfter(windowEnd)) {
            auditService.recordAttempt(
                    ticket.getId(), eventId, staffPrincipal.userId(),
                    CheckInOutcome.INVALID_WINDOW, now, "Check-in window closed at " + windowEnd
            );
            throw new ConflictException("Check-in window has closed for this event");
        }

        if (ticket.getStatus() == TicketStatus.CANCELLED) {
            auditService.recordAttempt(
                    ticket.getId(), eventId, staffPrincipal.userId(),
                    CheckInOutcome.INVALID_STATUS, now, "Ticket cancelled"
            );
            throw new ConflictException("Ticket has been cancelled");
        }

        Integer updatedCount = transactionTemplate.execute(status -> {
            int rows = ticketRepository.markCheckedInAtomically(
                    ticket.getId().toString(),
                    eventId.toString(),
                    staffPrincipal.userId().toString(),
                    now
            );
            if (rows == 1) {
                outboxService.enqueue(
                        KafkaTopics.CHECKIN_EVENTS,
                        eventId,
                        "TicketCheckedIn",
                        new DomainEvents.TicketCheckedInPayload(
                                ticket.getId(),
                                eventId,
                                ticket.getTicketTypeId(),
                                ticket.getUserId(),
                                event.getTitle(),
                                ticket.getTicketCode(),
                                now
                        )
                );
            }
            return rows;
        });
        int updated = updatedCount != null ? updatedCount : 0;

        if (updated == 0) {
            TicketEntity reloaded = ticketRepository.findById(ticket.getId()).orElseThrow();
            if (reloaded.getStatus() == TicketStatus.CHECKED_IN) {
                String priorStaffName = reloaded.getCheckedInBy() != null
                        ? userRepository.findById(reloaded.getCheckedInBy())
                        .map(UserEntity::getFullName)
                        .orElse(reloaded.getCheckedInBy().toString())
                        : "staff";
                String msg = "Already checked in at " + reloaded.getCheckedInAt() + " by " + priorStaffName;
                auditService.recordAttempt(
                        ticket.getId(), eventId, staffPrincipal.userId(),
                        CheckInOutcome.DUPLICATE_REJECTED, now, msg
                );
                throw new ConflictException(msg);
            }
            auditService.recordAttempt(
                    ticket.getId(), eventId, staffPrincipal.userId(),
                    CheckInOutcome.INVALID_STATUS, now, "Status is " + reloaded.getStatus()
            );
            throw new ConflictException("Ticket cannot be checked in (status: " + reloaded.getStatus() + ")");
        }

        auditService.recordAttempt(
                ticket.getId(), eventId, staffPrincipal.userId(),
                CheckInOutcome.SUCCESS, now, "Checked in successfully"
        );

        TicketTypeEntity tt = ticketTypeRepository.findById(ticket.getTicketTypeId()).orElse(null);
        UserEntity attendee = userRepository.findById(ticket.getUserId()).orElse(null);

        return new CheckInDtos.CheckInResponse(
                ticket.getId(),
                eventId,
                ticket.getTicketCode(),
                tt != null ? tt.getName() : "",
                attendee != null ? attendee.getFullName() : "",
                TicketStatus.CHECKED_IN.name(),
                now,
                staffPrincipal.userId(),
                staffPrincipal.fullName()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<CheckInDtos.CheckInLogResponse> listRecentLogs(
            UUID eventId,
            GateKeeperPrincipal staffPrincipal,
            int page,
            int size
    ) {
        authorizationService.requireStaffAssignedToEvent(eventId, staffPrincipal);
        Page<CheckInLogEntity> logs = checkInLogRepository.findByEventIdOrderByScannedAtDesc(
                eventId, PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 50)));
        return PageResponse.from(logs.map(l -> new CheckInDtos.CheckInLogResponse(
                l.getId(),
                l.getTicketId(),
                l.getEventId(),
                l.getStaffUserId(),
                l.getOutcome(),
                l.getScannedAt(),
                l.getNotes()
        )));
    }
}

