package com.gatekeeper.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessExpirationSeconds;
    private final long refreshExpirationSeconds;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtService(
            @Value("${gatekeeper.jwt.secret}") String jwtSecret,
            @Value("${gatekeeper.jwt.access-expiration-seconds:900}") long accessExpirationSeconds,
            @Value("${gatekeeper.jwt.refresh-expiration-seconds:604800}") long refreshExpirationSeconds
    ) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("gatekeeper.jwt.secret must be configured");
        }
        this.signingKey = Keys.hmacShaKeyFor(sha256(jwtSecret));
        this.accessExpirationSeconds = accessExpirationSeconds;
        this.refreshExpirationSeconds = refreshExpirationSeconds;
    }

    public String generateAccessToken(UserEntity user) {
        Instant now = Instant.now();
        Instant expiry = now.plusSeconds(accessExpirationSeconds);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("fullName", user.getFullName())
                .claim("roles", user.getRoles().stream().map(Enum::name).sorted().toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<GateKeeperPrincipal> parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            UUID userId = UUID.fromString(claims.getSubject());
            String email = claims.get("email", String.class);
            String fullName = claims.get("fullName", String.class);
            Object rawRoles = claims.get("roles");
            Set<Role> roles = Set.of();
            if (rawRoles instanceof Collection<?> col) {
                roles = col.stream()
                        .map(Object::toString)
                        .map(Role::valueOf)
                        .collect(Collectors.toUnmodifiableSet());
            }
            return Optional.of(new GateKeeperPrincipal(userId, email, fullName, roles));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public String generateOpaqueRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String rawToken) {
        return HexFormat.of().formatHex(sha256(rawToken));
    }

    public long getAccessExpirationSeconds() {
        return accessExpirationSeconds;
    }

    public long getRefreshExpirationSeconds() {
        return refreshExpirationSeconds;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

