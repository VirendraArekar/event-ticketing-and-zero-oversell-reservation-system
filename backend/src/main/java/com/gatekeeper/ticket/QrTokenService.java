package com.gatekeeper.ticket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class QrTokenService {

    private static final String VERSION_PREFIX = "gk1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretKeyBytes;

    public QrTokenService(@Value("${gatekeeper.qr.hmac-secret}") String hmacSecret) {
        if (hmacSecret == null || hmacSecret.isBlank()) {
            throw new IllegalStateException("gatekeeper.qr.hmac-secret must be configured");
        }
        this.secretKeyBytes = sha256(hmacSecret);
    }

    public record VerifiedQrPayload(
            UUID ticketId,
            UUID eventId,
            Instant expiresAt
    ) {
    }

    public String signToken(UUID ticketId, UUID eventId, Instant expiresAt) {
        long expiryEpoch = expiresAt.getEpochSecond();
        String payload = ticketId + ":" + eventId + ":" + expiryEpoch;
        byte[] sig = computeHmac(payload);
        String encodedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        return VERSION_PREFIX + "." + ticketId + "." + eventId + "." + expiryEpoch + "." + encodedSig;
    }

    public Optional<VerifiedQrPayload> verifyToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 5 || !VERSION_PREFIX.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            UUID ticketId = UUID.fromString(parts[1]);
            UUID eventId = UUID.fromString(parts[2]);
            long expiryEpoch = Long.parseLong(parts[3]);
            byte[] providedSig = Base64.getUrlDecoder().decode(parts[4]);

            String payload = ticketId + ":" + eventId + ":" + expiryEpoch;
            byte[] expectedSig = computeHmac(payload);

            // Constant-time comparison prevents timing side-channel signature forgery attacks
            if (!MessageDigest.isEqual(expectedSig, providedSig)) {
                return Optional.empty();
            }

            return Optional.of(new VerifiedQrPayload(ticketId, eventId, Instant.ofEpochSecond(expiryEpoch)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private byte[] computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKeyBytes, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

