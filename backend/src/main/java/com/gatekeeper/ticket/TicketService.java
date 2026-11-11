package com.gatekeeper.ticket;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.UserEntity;
import com.gatekeeper.auth.UserRepository;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.event.TicketTypeEntity;
import com.gatekeeper.event.TicketTypeRepository;
import com.gatekeeper.order.OrderEntity;
import com.gatekeeper.order.OrderItemEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TicketService {

    private static final char[] CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();

    private final TicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final UserRepository userRepository;
    private final QrTokenService qrTokenService;
    private final QrCodeImageService qrCodeImageService;
    private final TicketPdfService ticketPdfService;
    private final long tokenGraceSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public TicketService(
            TicketRepository ticketRepository,
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository,
            UserRepository userRepository,
            QrTokenService qrTokenService,
            QrCodeImageService qrCodeImageService,
            TicketPdfService ticketPdfService,
            @Value("${gatekeeper.qr.token-grace-seconds:86400}") long tokenGraceSeconds
    ) {
        this.ticketRepository = ticketRepository;
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.userRepository = userRepository;
        this.qrTokenService = qrTokenService;
        this.qrCodeImageService = qrCodeImageService;
        this.ticketPdfService = ticketPdfService;
        this.tokenGraceSeconds = tokenGraceSeconds;
    }

    @Transactional
    public List<TicketEntity> issueTicketsForConfirmedOrder(
            OrderEntity order,
            EventEntity event,
            List<OrderItemEntity> items,
            Instant now
    ) {
        List<TicketEntity> existing = ticketRepository.findByOrderId(order.getId());
        if (!existing.isEmpty()) {
            return existing;
        }

        Instant tokenExpiry = event.getEndTime().plusSeconds(tokenGraceSeconds);
        List<TicketEntity> issued = new ArrayList<>();

        for (OrderItemEntity item : items) {
            for (int i = 0; i < item.getQuantity(); i++) {
                UUID ticketId = UUID.randomUUID();
                String ticketCode = generateUniqueTicketCode();
                String qrToken = qrTokenService.signToken(ticketId, event.getId(), tokenExpiry);
                issued.add(new TicketEntity(
                        ticketId,
                        order.getId(),
                        item.getId(),
                        event.getId(),
                        item.getTicketTypeId(),
                        order.getUserId(),
                        ticketCode,
                        qrToken,
                        now
                ));
            }
        }
        return ticketRepository.saveAll(issued);
    }

    @Transactional
    public void cancelTicketsForRefundedOrder(UUID orderId, Instant now) {
        if (ticketRepository.existsByOrderIdAndStatus(orderId, TicketStatus.CHECKED_IN)) {
            throw new ConflictException("Cannot cancel and refund an order after a ticket has already been checked in");
        }
        ticketRepository.cancelValidTicketsForOrder(orderId.toString(), now);
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketDtos.TicketResponse> listMyTickets(
            GateKeeperPrincipal principal,
            UUID eventId,
            int page,
            int size
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(size, 1), 50));
        Page<TicketEntity> ticketPage = eventId != null
                ? ticketRepository.findByUserIdAndEventIdOrderByCreatedAtDesc(principal.userId(), eventId, pageable)
                : ticketRepository.findByUserIdOrderByCreatedAtDesc(principal.userId(), pageable);
        return PageResponse.from(mapPage(ticketPage));
    }

    @Transactional(readOnly = true)
    public TicketDtos.TicketResponse getMyTicket(UUID ticketId, GateKeeperPrincipal principal) {
        TicketEntity ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        EventEntity event = eventRepository.findById(ticket.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (!ticket.getUserId().equals(principal.userId()) && !event.getOrganizerId().equals(principal.userId())) {
            throw new ForbiddenException("You can only view your own tickets");
        }
        TicketTypeEntity tt = ticketTypeRepository.findById(ticket.getTicketTypeId())
                .orElseThrow(() -> new NotFoundException("Ticket type not found"));
        UserEntity attendee = userRepository.findById(ticket.getUserId()).orElse(null);
        return toResponse(ticket, event, tt, attendee != null ? attendee.getFullName() : "");
    }

    @Transactional(readOnly = true)
    public byte[] getTicketQrPng(UUID ticketId, GateKeeperPrincipal principal) {
        TicketDtos.TicketResponse ticket = getMyTicket(ticketId, principal);
        return qrCodeImageService.generatePng(ticket.qrToken(), 320);
    }

    @Transactional(readOnly = true)
    public TicketPdfPayload getTicketPdf(UUID ticketId, GateKeeperPrincipal principal) {
        TicketDtos.TicketResponse ticket = getMyTicket(ticketId, principal);
        byte[] pdfBytes = ticketPdfService.renderTicketPdf(
                ticket.eventTitle(),
                ticket.venueName(),
                ticket.venueAddress(),
                ticket.eventStartTime(),
                ticket.timezone(),
                ticket.ticketTypeName(),
                ticket.attendeeName(),
                ticket.ticketCode(),
                ticket.qrToken(),
                ticket.status()
        );
        return new TicketPdfPayload(ticket.ticketCode(), pdfBytes);
    }

    public record TicketPdfPayload(String ticketCode, byte[] bytes) {
    }

    private Page<TicketDtos.TicketResponse> mapPage(Page<TicketEntity> page) {
        List<TicketEntity> tickets = page.getContent();
        if (tickets.isEmpty()) {
            return page.map(t -> null);
        }
        Map<UUID, EventEntity> events = eventRepository.findAllById(
                        tickets.stream().map(TicketEntity::getEventId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(EventEntity::getId, Function.identity()));
        Map<UUID, TicketTypeEntity> types = ticketTypeRepository.findAllById(
                        tickets.stream().map(TicketEntity::getTicketTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TicketTypeEntity::getId, Function.identity()));
        Map<UUID, UserEntity> users = userRepository.findAllById(
                        tickets.stream().map(TicketEntity::getUserId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        return page.map(t -> {
            EventEntity event = events.get(t.getEventId());
            TicketTypeEntity tt = types.get(t.getTicketTypeId());
            UserEntity user = users.get(t.getUserId());
            return toResponse(t, event, tt, user != null ? user.getFullName() : "");
        });
    }

    private TicketDtos.TicketResponse toResponse(
            TicketEntity ticket,
            EventEntity event,
            TicketTypeEntity tt,
            String attendeeName
    ) {
        return new TicketDtos.TicketResponse(
                ticket.getId(),
                ticket.getOrderId(),
                ticket.getEventId(),
                event != null ? event.getTitle() : "",
                event != null ? event.getVenueName() : "",
                event != null ? event.getVenueAddress() : "",
                event != null ? event.getStartTime() : null,
                event != null ? event.getEndTime() : null,
                event != null ? event.getTimezone() : "UTC",
                ticket.getTicketTypeId(),
                tt != null ? tt.getName() : "",
                ticket.getUserId(),
                attendeeName,
                ticket.getTicketCode(),
                ticket.getQrToken(),
                ticket.getStatus(),
                ticket.getCheckedInAt(),
                ticket.getCheckedInBy(),
                ticket.getCreatedAt()
        );
    }

    private String generateUniqueTicketCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder sb = new StringBuilder("GK-");
            for (int i = 0; i < 12; i++) {
                sb.append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.length)]);
            }
            String candidate = sb.toString();
            if (ticketRepository.findByTicketCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        return "GK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 14).toUpperCase();
    }
}

