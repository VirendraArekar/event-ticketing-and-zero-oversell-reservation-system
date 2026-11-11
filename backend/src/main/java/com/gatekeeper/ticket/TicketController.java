package com.gatekeeper.ticket;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.SecurityUtils;
import com.gatekeeper.common.web.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<TicketDtos.TicketResponse>> listMyTickets(
            @RequestParam(required = false) UUID eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(ticketService.listMyTickets(principal, eventId, page, size));
    }

    @GetMapping("/{ticketId}")
    public ResponseEntity<TicketDtos.TicketResponse> getMyTicket(@PathVariable UUID ticketId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        return ResponseEntity.ok(ticketService.getMyTicket(ticketId, principal));
    }

    @GetMapping(value = "/{ticketId}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getTicketQr(@PathVariable UUID ticketId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        byte[] png = ticketService.getTicketQrPng(ticketId, principal);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.IMAGE_PNG)
                .body(png);
    }

    @GetMapping(value = "/{ticketId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getTicketPdf(@PathVariable UUID ticketId) {
        GateKeeperPrincipal principal = SecurityUtils.requirePrincipal();
        TicketService.TicketPdfPayload payload = ticketService.getTicketPdf(ticketId, principal);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("ticket-" + payload.ticketCode() + ".pdf")
                .build());
        headers.setCacheControl(CacheControl.noStore());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(payload.bytes());
    }
}

