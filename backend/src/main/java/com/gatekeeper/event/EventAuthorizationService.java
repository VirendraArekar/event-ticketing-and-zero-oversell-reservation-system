package com.gatekeeper.event;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.Role;
import com.gatekeeper.common.exception.ForbiddenException;
import com.gatekeeper.common.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class EventAuthorizationService {

    private final EventRepository eventRepository;
    private final EventStaffAssignmentRepository staffAssignmentRepository;

    public EventAuthorizationService(
            EventRepository eventRepository,
            EventStaffAssignmentRepository staffAssignmentRepository
    ) {
        this.eventRepository = eventRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
    }

    public void requireOrganizerRole(GateKeeperPrincipal principal) {
        if (!principal.hasRole(Role.ORGANIZER)) {
            throw new ForbiddenException("Organizer role is required for this action");
        }
    }

    @Transactional(readOnly = true)
    public EventEntity requireEventOwnedByOrganizer(UUID eventId, GateKeeperPrincipal principal) {
        requireOrganizerRole(principal);
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (!event.getOrganizerId().equals(principal.userId())) {
            throw new ForbiddenException("You do not have permission to manage this event");
        }
        return event;
    }

    @Transactional
    public EventEntity lockEventOwnedByOrganizer(UUID eventId, GateKeeperPrincipal principal) {
        requireOrganizerRole(principal);
        EventEntity event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        if (!event.getOrganizerId().equals(principal.userId())) {
            throw new ForbiddenException("You do not have permission to manage this event");
        }
        return event;
    }

    @Transactional(readOnly = true)
    public EventEntity requireStaffAssignedToEvent(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Event not found"));
        boolean assigned = staffAssignmentRepository.existsByEventIdAndUserId(eventId, principal.userId());
        if (!assigned) {
            throw new ForbiddenException("You are not assigned as check-in staff for this event");
        }
        return event;
    }
}

