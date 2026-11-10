package com.gatekeeper.auth;

import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.event.EventAuthorizationService;
import com.gatekeeper.event.EventEntity;
import com.gatekeeper.event.EventRepository;
import com.gatekeeper.event.EventStaffAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthAndEventRoleUnitTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventStaffAssignmentRepository staffAssignmentRepository;

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    @Test
    void jwtServiceGeneratesAndParsesTokenAndRejectsTamperedToken() {
        JwtService jwtService = new JwtService(randomSecret(), 900, 604800);
        UUID userId = UUID.randomUUID();
        UserEntity user = new UserEntity(
                userId,
                "organizer@example.com",
                "$2a$12$hash",
                "Ada Lovelace",
                Set.of(Role.ORGANIZER, Role.ATTENDEE),
                Instant.now()
        );

        String token = jwtService.generateAccessToken(user);
        Optional<GateKeeperPrincipal> parsed = jwtService.parseAccessToken(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().userId()).isEqualTo(userId);
        assertThat(parsed.get().email()).isEqualTo("organizer@example.com");
        assertThat(parsed.get().roles()).containsExactlyInAnyOrder(Role.ORGANIZER, Role.ATTENDEE);

        String tampered = token.substring(0, token.length() - 4) + "abcd";
        assertThat(jwtService.parseAccessToken(tampered)).isEmpty();
    }

    @Test
    void eventAuthorizationEnforcesOrganizerOwnershipAndStaffAssignment() {
        EventAuthorizationService authService = new EventAuthorizationService(eventRepository, staffAssignmentRepository);

        UUID ownerId = UUID.randomUUID();
        UUID otherOrganizerId = UUID.randomUUID();
        UUID attendeeId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        EventEntity event = new EventEntity(
                eventId,
                ownerId,
                "Summit",
                "Description",
                "Conference",
                "Main Hall",
                "1 Main St",
                500,
                Instant.now().plusSeconds(3600),
                Instant.now().plusSeconds(7200),
                "UTC",
                Instant.now().plusSeconds(1800),
                false,
                25,
                Instant.now()
        );

        GateKeeperPrincipal owner = new GateKeeperPrincipal(ownerId, "owner@ex.com", "Owner", Set.of(Role.ORGANIZER));
        GateKeeperPrincipal other = new GateKeeperPrincipal(otherOrganizerId, "other@ex.com", "Other", Set.of(Role.ORGANIZER));
        GateKeeperPrincipal attendee = new GateKeeperPrincipal(attendeeId, "att@ex.com", "Att", Set.of(Role.ATTENDEE));

        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));
        when(staffAssignmentRepository.existsByEventIdAndUserId(eventId, attendeeId)).thenReturn(false);

        assertThat(authService.requireEventOwnedByOrganizer(eventId, owner)).isEqualTo(event);

        assertThatThrownBy(() -> authService.requireEventOwnedByOrganizer(eventId, other))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authService.requireEventOwnedByOrganizer(eventId, attendee))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authService.requireStaffAssignedToEvent(eventId, attendee))
                .isInstanceOf(ForbiddenException.class);
    }
}

