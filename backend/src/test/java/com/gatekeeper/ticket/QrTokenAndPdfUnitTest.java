package com.gatekeeper.ticket;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QrTokenAndPdfUnitTest {

    private static String randomSecret() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    @Test
    void qrTokenSignsVerifiesRejectsTamperingAndContainsNoPersonalData() {
        QrTokenService service = new QrTokenService(randomSecret());
        QrTokenService otherKeyService = new QrTokenService(randomSecret());

        UUID ticketId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Instant expiry = Instant.now().plusSeconds(3600);

        String token = service.signToken(ticketId, eventId, expiry);
        assertThat(token).startsWith("gk1.");
        assertThat(token).doesNotContain("@", "Alice", "Bob");

        Optional<QrTokenService.VerifiedQrPayload> verified = service.verifyToken(token);
        assertThat(verified).isPresent();
        assertThat(verified.get().ticketId()).isEqualTo(ticketId);
        assertThat(verified.get().eventId()).isEqualTo(eventId);
        assertThat(verified.get().expiresAt().getEpochSecond()).isEqualTo(expiry.getEpochSecond());

        // Tamper signature
        String tampered = token.substring(0, token.length() - 2) + "XX";
        assertThat(service.verifyToken(tampered)).isEmpty();

        // Tamper payload (flip last char of ticketId)
        String[] parts = token.split("\\.");
        char flipped = parts[1].endsWith("a") ? 'b' : 'a';
        String tamperedTicket = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1) + flipped
                + "." + parts[2] + "." + parts[3] + "." + parts[4];
        assertThat(service.verifyToken(tamperedTicket)).isEmpty();

        // Wrong secret
        assertThat(otherKeyService.verifyToken(token)).isEmpty();
    }

    @Test
    void zxingQrPngAndOpenPdfGenerationProduceValidBinaries() {
        QrTokenService tokenService = new QrTokenService(randomSecret());
        QrCodeImageService qrService = new QrCodeImageService();
        TicketPdfService pdfService = new TicketPdfService(qrService);

        String token = tokenService.signToken(UUID.randomUUID(), UUID.randomUUID(), Instant.now().plusSeconds(7200));
        byte[] png = qrService.generatePng(token, 240);
        assertThat(png.length).isGreaterThan(200);
        // PNG magic header: 89 50 4E 47
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 0x50);
        assertThat(png[2]).isEqualTo((byte) 0x4E);
        assertThat(png[3]).isEqualTo((byte) 0x47);

        byte[] pdf = pdfService.renderTicketPdf(
                "GateKeeper Live",
                "Main Arena",
                "100 Civic Center",
                Instant.now().plusSeconds(3600),
                "UTC",
                "VIP",
                "Jane Doe",
                "GK-AB12CD34EF56",
                token,
                TicketStatus.VALID
        );
        assertThat(pdf.length).isGreaterThan(1000);
        String header = new String(pdf, 0, 5);
        assertThat(header).isEqualTo("%PDF-");
    }
}

