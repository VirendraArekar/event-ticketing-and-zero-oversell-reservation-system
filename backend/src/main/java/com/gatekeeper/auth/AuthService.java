package com.gatekeeper.auth;

import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.UnauthorizedException;
import com.gatekeeper.event.EventStaffAssignmentEntity;
import com.gatekeeper.event.EventStaffAssignmentRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final EventStaffAssignmentRepository staffAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            EventStaffAssignmentRepository staffAssignmentRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ConflictException("An account with this email already exists");
        }

        Set<Role> requestedRoles = EnumSet.noneOf(Role.class);
        if (request.roles() == null || request.roles().isEmpty()) {
            requestedRoles.add(Role.ATTENDEE);
        } else {
            for (Role role : request.roles()) {
                if (role == Role.STAFF) {
                    throw new BadRequestException("STAFF role is assigned per event by an organizer");
                }
                requestedRoles.add(role);
            }
            requestedRoles.add(Role.ATTENDEE);
        }

        Instant now = Instant.now();
        UserEntity user = new UserEntity(
                UUID.randomUUID(),
                normalizedEmail,
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                requestedRoles,
                now
        );
        userRepository.save(user);
        return issueTokens(user, now);
    }

    @Transactional
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase(Locale.ROOT);
        UserEntity user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        if (!user.isEnabled() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        return issueTokens(user, Instant.now());
    }

    @Transactional
    public AuthDtos.AuthResponse refresh(AuthDtos.RefreshRequest request) {
        String tokenHash = jwtService.hashRefreshToken(request.refreshToken().trim());
        RefreshTokenEntity existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        Instant now = Instant.now();
        if (existing.isRevoked() || existing.getExpiresAt().isBefore(now)) {
            throw new UnauthorizedException("Refresh token expired or revoked");
        }

        existing.revoke();
        refreshTokenRepository.save(existing);

        UserEntity user = userRepository.findById(existing.getUserId())
                .filter(UserEntity::isEnabled)
                .orElseThrow(() -> new UnauthorizedException("Account is disabled or not found"));

        return issueTokens(user, now);
    }

    @Transactional
    public void logout(AuthDtos.LogoutRequest request, UUID optionalAuthenticatedUserId) {
        if (request != null && request.refreshToken() != null && !request.refreshToken().isBlank()) {
            String hash = jwtService.hashRefreshToken(request.refreshToken().trim());
            refreshTokenRepository.findByTokenHash(hash).ifPresent(token -> {
                token.revoke();
                refreshTokenRepository.save(token);
            });
        }
        if (optionalAuthenticatedUserId != null) {
            refreshTokenRepository.revokeAllActiveForUser(optionalAuthenticatedUserId);
        }
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserProfileResponse getProfile(UUID userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        List<UUID> staffEventIds = staffAssignmentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(EventStaffAssignmentEntity::getEventId)
                .toList();
        return new AuthDtos.UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                Set.copyOf(user.getRoles()),
                staffEventIds
        );
    }

    private AuthDtos.AuthResponse issueTokens(UserEntity user, Instant now) {
        String accessToken = jwtService.generateAccessToken(user);
        String rawRefreshToken = jwtService.generateOpaqueRefreshToken();
        String refreshHash = jwtService.hashRefreshToken(rawRefreshToken);
        RefreshTokenEntity refreshEntity = new RefreshTokenEntity(
                UUID.randomUUID(),
                user.getId(),
                refreshHash,
                now.plusSeconds(jwtService.getRefreshExpirationSeconds()),
                now
        );
        refreshTokenRepository.save(refreshEntity);

        List<UUID> staffEventIds = staffAssignmentRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(EventStaffAssignmentEntity::getEventId)
                .toList();

        AuthDtos.UserProfileResponse profile = new AuthDtos.UserProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                Set.copyOf(user.getRoles()),
                staffEventIds
        );
        return new AuthDtos.AuthResponse(
                accessToken,
                rawRefreshToken,
                jwtService.getAccessExpirationSeconds(),
                profile
        );
    }
}

