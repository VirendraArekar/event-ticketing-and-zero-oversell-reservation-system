package com.gatekeeper.event;

import com.gatekeeper.auth.GateKeeperPrincipal;
import com.gatekeeper.auth.Role;
import com.gatekeeper.auth.UserEntity;
import com.gatekeeper.auth.UserRepository;
import com.gatekeeper.common.exception.BadRequestException;
import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.common.persistence.TransactionAfterCommit;
import com.gatekeeper.common.web.PageResponse;
import com.gatekeeper.messaging.DomainEvents;
import com.gatekeeper.messaging.KafkaTopics;
import com.gatekeeper.messaging.OutboxService;
import com.gatekeeper.redis.EventCacheService;
import com.gatekeeper.redis.RedisInventoryService;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final TicketInventoryRepository ticketInventoryRepository;
    private final EventStaffAssignmentRepository staffAssignmentRepository;
    private final UserRepository userRepository;
    private final EventAuthorizationService authorizationService;
    private final EventCacheService eventCacheService;
    private final RedisInventoryService redisInventoryService;
    private final OutboxService outboxService;

    public EventService(
            EventRepository eventRepository,
            TicketTypeRepository ticketTypeRepository,
            TicketInventoryRepository ticketInventoryRepository,
            EventStaffAssignmentRepository staffAssignmentRepository,
            UserRepository userRepository,
            EventAuthorizationService authorizationService,
            EventCacheService eventCacheService,
            RedisInventoryService redisInventoryService,
            OutboxService outboxService
    ) {
        this.eventRepository = eventRepository;
        this.ticketTypeRepository = ticketTypeRepository;
        this.ticketInventoryRepository = ticketInventoryRepository;
        this.staffAssignmentRepository = staffAssignmentRepository;
        this.userRepository = userRepository;
        this.authorizationService = authorizationService;
        this.eventCacheService = eventCacheService;
        this.redisInventoryService = redisInventoryService;
        this.outboxService = outboxService;
    }

    @Transactional
    public EventDtos.EventDetailResponse createEvent(
            GateKeeperPrincipal principal,
            EventDtos.CreateOrUpdateEventRequest request
    ) {
        authorizationService.requireOrganizerRole(principal);
        Instant cutoff = validateEventWindowAndTimezone(request);
        Instant now = Instant.now();

        EventEntity event = new EventEntity(
                UUID.randomUUID(),
                principal.userId(),
                request.title().trim(),
                request.description().trim(),
                request.category().trim(),
                request.venueName().trim(),
                request.venueAddress().trim(),
                request.venueCapacity(),
                request.startTime(),
                request.endTime(),
                request.timezone().trim(),
                cutoff,
                Boolean.TRUE.equals(request.waitingRoomEnabled()),
                request.waitingRoomThreshold() != null ? request.waitingRoomThreshold() : 25,
                now
        );
        eventRepository.save(event);
        return toDetailResponse(event, List.of());
    }

    @Transactional
    public EventDtos.EventDetailResponse updateEvent(
            UUID eventId,
            GateKeeperPrincipal principal,
            EventDtos.CreateOrUpdateEventRequest request
    ) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ConflictException("Cancelled or completed events cannot be edited");
        }
        Instant cutoff = validateEventWindowAndTimezone(request);

        int allocatedCapacity = ticketTypeRepository.sumCapacityByEventId(eventId);
        if (request.venueCapacity() < allocatedCapacity) {
            throw new BadRequestException(
                    "Venue capacity (" + request.venueCapacity() + ") cannot be less than allocated ticket capacity (" + allocatedCapacity + ")"
            );
        }

        event.updateDetails(
                request.title().trim(),
                request.description().trim(),
                request.category().trim(),
                request.venueName().trim(),
                request.venueAddress().trim(),
                request.venueCapacity(),
                request.startTime(),
                request.endTime(),
                request.timezone().trim(),
                cutoff,
                Boolean.TRUE.equals(request.waitingRoomEnabled()),
                request.waitingRoomThreshold() != null ? request.waitingRoomThreshold() : 25,
                Instant.now()
        );
        eventRepository.save(event);
        if (event.getStatus() == EventStatus.PUBLISHED) {
            outboxService.enqueue(
                    KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                    eventId,
                    "EventUpdated",
                    new DomainEvents.EventUpdatedOrCancelledPayload(eventId, event.getTitle(), false)
            );
        }
        TransactionAfterCommit.run(() -> eventCacheService.evictEvent(eventId));
        return toDetailResponse(event, getTicketTypesForEvent(eventId));
    }

    @Transactional
    public EventDtos.EventDetailResponse publishEvent(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() != EventStatus.DRAFT) {
            throw new ConflictException("Only DRAFT events can be published");
        }
        List<TicketTypeEntity> ticketTypes = ticketTypeRepository.findByEventIdOrderByPriceCentsAscCreatedAtAsc(eventId);
        if (ticketTypes.isEmpty()) {
            throw new BadRequestException("Cannot publish an event without at least one ticket type");
        }
        Instant now = Instant.now();
        if (!event.getEndTime().isAfter(now)) {
            throw new BadRequestException("Cannot publish an event whose end time is in the past");
        }
        event.transitionStatus(EventStatus.PUBLISHED, now);
        eventRepository.save(event);
        int totalCapacity = ticketTypes.stream().mapToInt(TicketTypeEntity::getCapacity).sum();
        List<DomainEvents.TicketTypeInit> initTypes = ticketTypes.stream()
                .map(t -> new DomainEvents.TicketTypeInit(t.getId(), t.getName(), t.getCapacity()))
                .toList();
        outboxService.enqueue(
                KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                eventId,
                "EventPublished",
                new DomainEvents.EventPublishedPayload(eventId, event.getTitle(), totalCapacity, initTypes)
        );
        TransactionAfterCommit.run(() -> eventCacheService.evictEvent(eventId));
        return toDetailResponse(event, getTicketTypesForEvent(eventId));
    }

    @Transactional
    public EventDtos.EventDetailResponse cancelEvent(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw new ConflictException("Event is already cancelled");
        }
        if (event.getStatus() == EventStatus.COMPLETED) {
            throw new ConflictException("Completed events cannot be cancelled");
        }
        event.transitionStatus(EventStatus.CANCELLED, Instant.now());
        eventRepository.save(event);
        outboxService.enqueue(
                KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                eventId,
                "EventCancelled",
                new DomainEvents.EventUpdatedOrCancelledPayload(eventId, event.getTitle(), true)
        );
        TransactionAfterCommit.run(() -> eventCacheService.evictEvent(eventId));
        return toDetailResponse(event, getTicketTypesForEvent(eventId));
    }

    @Transactional
    public EventDtos.EventDetailResponse completeEvent(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new ConflictException("Only PUBLISHED events can be marked completed");
        }
        event.transitionStatus(EventStatus.COMPLETED, Instant.now());
        eventRepository.save(event);
        TransactionAfterCommit.run(() -> eventCacheService.evictEvent(eventId));
        return toDetailResponse(event, getTicketTypesForEvent(eventId));
    }

    @Transactional
    public EventDtos.TicketTypeResponse createTicketType(
            UUID eventId,
            GateKeeperPrincipal principal,
            EventDtos.CreateOrUpdateTicketTypeRequest request
    ) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ConflictException("Cannot add ticket types to a cancelled or completed event");
        }
        validateTicketTypeRequest(request);

        if (ticketTypeRepository.existsByEventIdAndNameIgnoreCase(eventId, request.name().trim())) {
            throw new ConflictException("A ticket type with this name already exists for this event");
        }

        int currentTotal = ticketTypeRepository.sumCapacityByEventId(eventId);
        if (currentTotal + request.capacity() > event.getVenueCapacity()) {
            throw new BadRequestException(
                    "Total ticket type capacity (" + (currentTotal + request.capacity())
                            + ") exceeds venue capacity (" + event.getVenueCapacity() + ")"
            );
        }

        Instant now = Instant.now();
        String currency = request.currency() == null || request.currency().isBlank()
                ? "USD"
                : request.currency().trim().toUpperCase(Locale.ROOT);
        UUID id = UUID.randomUUID();

        TicketTypeEntity ticketType = new TicketTypeEntity(
                id,
                eventId,
                request.name().trim(),
                request.description() == null ? null : request.description().trim(),
                request.priceCents(),
                currency,
                request.capacity(),
                request.perOrderLimit(),
                request.salesStart(),
                request.salesEnd(),
                now
        );
        ticketTypeRepository.save(ticketType);

        TicketInventoryEntity inventory = new TicketInventoryEntity(id, eventId, request.capacity(), now);
        ticketInventoryRepository.save(inventory);
        outboxService.enqueue(
                KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                eventId,
                "TicketTypeUpserted",
                new DomainEvents.TicketTypeUpsertPayload(eventId, id, ticketType.getName(), ticketType.getCapacity())
        );
        TransactionAfterCommit.run(() -> {
            redisInventoryService.syncTicketTypeFromMySql(id);
            eventCacheService.evictEvent(eventId);
        });

        return toTicketTypeResponse(ticketType, inventory);
    }

    @Transactional
    public EventDtos.TicketTypeResponse updateTicketType(
            UUID eventId,
            UUID ticketTypeId,
            GateKeeperPrincipal principal,
            EventDtos.CreateOrUpdateTicketTypeRequest request
    ) {
        EventEntity event = authorizationService.lockEventOwnedByOrganizer(eventId, principal);
        if (event.getStatus() == EventStatus.CANCELLED || event.getStatus() == EventStatus.COMPLETED) {
            throw new ConflictException("Cannot modify ticket types on a cancelled or completed event");
        }
        validateTicketTypeRequest(request);

        TicketTypeEntity ticketType = ticketTypeRepository.findByIdAndEventId(ticketTypeId, eventId)
                .orElseThrow(() -> new NotFoundException("Ticket type not found"));
        TicketInventoryEntity inventory = ticketInventoryRepository.findById(ticketTypeId)
                .orElseThrow(() -> new NotFoundException("Ticket inventory not found"));

        int committed = inventory.getHeld() + inventory.getSold();
        if (request.capacity() < committed) {
            throw new BadRequestException(
                    "Ticket capacity (" + request.capacity() + ") cannot be less than held + sold (" + committed + ")"
            );
        }

        int otherCapacity = ticketTypeRepository.sumCapacityByEventId(eventId) - ticketType.getCapacity();
        if (otherCapacity + request.capacity() > event.getVenueCapacity()) {
            throw new BadRequestException(
                    "Total ticket type capacity (" + (otherCapacity + request.capacity())
                            + ") exceeds venue capacity (" + event.getVenueCapacity() + ")"
            );
        }

        Instant now = Instant.now();
        ticketType.update(
                request.name().trim(),
                request.description() == null ? null : request.description().trim(),
                request.priceCents(),
                request.capacity(),
                request.perOrderLimit(),
                request.salesStart(),
                request.salesEnd(),
                now
        );
        inventory.setTotal(request.capacity(), now);

        ticketTypeRepository.save(ticketType);
        ticketInventoryRepository.save(inventory);
        outboxService.enqueue(
                KafkaTopics.EVENT_LIFECYCLE_EVENTS,
                eventId,
                "TicketTypeUpserted",
                new DomainEvents.TicketTypeUpsertPayload(eventId, ticketTypeId, ticketType.getName(), ticketType.getCapacity())
        );
        TransactionAfterCommit.run(() -> {
            redisInventoryService.syncTicketTypeFromMySql(ticketTypeId);
            eventCacheService.evictEvent(eventId);
        });
        return toTicketTypeResponse(ticketType, inventory);
    }

    @Transactional
    public EventDtos.StaffAssignmentResponse assignStaff(
            UUID eventId,
            GateKeeperPrincipal principal,
            EventDtos.AssignStaffRequest request
    ) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        UserEntity staffUser = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("No registered user found with email: " + email));

        if (!staffUser.getRoles().contains(Role.STAFF)) {
            staffUser.addRole(Role.STAFF);
            userRepository.save(staffUser);
        }

        EventStaffAssignmentEntity assignment = staffAssignmentRepository
                .findByEventIdAndUserId(eventId, staffUser.getId())
                .orElseGet(() -> staffAssignmentRepository.save(new EventStaffAssignmentEntity(
                        UUID.randomUUID(),
                        eventId,
                        staffUser.getId(),
                        principal.userId(),
                        Instant.now()
                )));

        return new EventDtos.StaffAssignmentResponse(
                assignment.getId(),
                assignment.getEventId(),
                staffUser.getId(),
                staffUser.getEmail(),
                staffUser.getFullName(),
                assignment.getCreatedAt()
        );
    }

    @Transactional
    public void removeStaff(UUID eventId, UUID staffUserId, GateKeeperPrincipal principal) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        EventStaffAssignmentEntity assignment = staffAssignmentRepository.findByEventIdAndUserId(eventId, staffUserId)
                .orElseThrow(() -> new NotFoundException("Staff assignment not found"));
        staffAssignmentRepository.delete(assignment);
    }

    @Transactional(readOnly = true)
    public List<EventDtos.StaffAssignmentResponse> listStaff(UUID eventId, GateKeeperPrincipal principal) {
        authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        List<EventStaffAssignmentEntity> assignments = staffAssignmentRepository.findByEventIdOrderByCreatedAtAsc(eventId);
        Map<UUID, UserEntity> usersById = userRepository.findAllById(
                assignments.stream().map(EventStaffAssignmentEntity::getUserId).toList()
        ).stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));

        return assignments.stream().map(a -> {
            UserEntity u = usersById.get(a.getUserId());
            return new EventDtos.StaffAssignmentResponse(
                    a.getId(),
                    a.getEventId(),
                    a.getUserId(),
                    u != null ? u.getEmail() : "",
                    u != null ? u.getFullName() : "",
                    a.getCreatedAt()
            );
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<EventDtos.EventSummaryResponse> listStaffAssignedEvents(GateKeeperPrincipal principal) {
        List<UUID> eventIds = staffAssignmentRepository.findByUserIdOrderByCreatedAtDesc(principal.userId())
                .stream()
                .map(EventStaffAssignmentEntity::getEventId)
                .toList();
        if (eventIds.isEmpty()) {
            return List.of();
        }
        return eventRepository.findAllById(eventIds).stream()
                .map(this::toSummaryResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDtos.EventSummaryResponse> listOrganizerEvents(
            GateKeeperPrincipal principal,
            int page,
            int size
    ) {
        authorizationService.requireOrganizerRole(principal);
        Pageable pageable = PageRequest.of(Math.max(0, page), clampSize(size), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<EventEntity> events = eventRepository.findByOrganizerId(principal.userId(), pageable);
        return PageResponse.from(events.map(this::toSummaryResponse));
    }

    @Transactional(readOnly = true)
    public EventDtos.EventDetailResponse getOrganizerEventDetail(UUID eventId, GateKeeperPrincipal principal) {
        EventEntity event = authorizationService.requireEventOwnedByOrganizer(eventId, principal);
        return toDetailResponse(event, getTicketTypesForEvent(eventId));
    }

    @Transactional(readOnly = true)
    public PageResponse<EventDtos.EventSummaryResponse> listPublicEvents(
            String search,
            String category,
            Instant from,
            Instant to,
            int page,
            int size
    ) {
        int safePage = Math.max(0, page);
        int safeSize = clampSize(size);
        return eventCacheService.getOrLoadEventList(search, category, from, to, safePage, safeSize, () -> {
            Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "startTime"));
            Specification<EventEntity> spec = (root, query, cb) -> {
                List<Predicate> predicates = new ArrayList<>();
                predicates.add(cb.equal(root.get("status"), EventStatus.PUBLISHED));

                if (search != null && !search.isBlank()) {
                    String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("title")), pattern),
                            cb.like(cb.lower(root.get("description")), pattern),
                            cb.like(cb.lower(root.get("venueName")), pattern),
                            cb.like(cb.lower(root.get("category")), pattern)
                    ));
                }
                if (category != null && !category.isBlank()) {
                    predicates.add(cb.equal(cb.lower(root.get("category")), category.trim().toLowerCase(Locale.ROOT)));
                }
                if (from != null) {
                    predicates.add(cb.greaterThanOrEqualTo(root.get("startTime"), from));
                }
                if (to != null) {
                    predicates.add(cb.lessThanOrEqualTo(root.get("startTime"), to));
                }
                return cb.and(predicates.toArray(Predicate[]::new));
            };

            Page<EventEntity> result = eventRepository.findAll(spec, pageable);
            return PageResponse.from(result.map(this::toSummaryResponse));
        });
    }

    @Transactional(readOnly = true)
    public EventDtos.EventDetailResponse getPublicEventDetail(UUID eventId) {
        return eventCacheService.getOrLoadEventDetail(eventId, () -> {
            EventEntity event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new NotFoundException("Event not found"));
            if (event.getStatus() == EventStatus.DRAFT) {
                throw new NotFoundException("Event not found");
            }
            return toDetailResponse(event, getTicketTypesForEvent(eventId));
        });
    }

    public List<EventDtos.TicketTypeResponse> getTicketTypesForEvent(UUID eventId) {
        List<TicketTypeEntity> types = ticketTypeRepository.findByEventIdOrderByPriceCentsAscCreatedAtAsc(eventId);
        Map<UUID, TicketInventoryEntity> inventoryById = ticketInventoryRepository.findByEventId(eventId)
                .stream()
                .collect(Collectors.toMap(TicketInventoryEntity::getTicketTypeId, Function.identity()));

        return types.stream()
                .map(type -> toTicketTypeResponse(type, inventoryById.get(type.getId())))
                .toList();
    }

    private EventDtos.EventSummaryResponse toSummaryResponse(EventEntity event) {
        List<TicketTypeEntity> types = ticketTypeRepository.findByEventIdOrderByPriceCentsAscCreatedAtAsc(event.getId());
        Map<UUID, TicketInventoryEntity> inv = ticketInventoryRepository.findByEventId(event.getId())
                .stream()
                .collect(Collectors.toMap(TicketInventoryEntity::getTicketTypeId, Function.identity()));

        long minPrice = types.stream().mapToLong(TicketTypeEntity::getPriceCents).min().orElse(0L);
        int totalAvailable = types.stream()
                .mapToInt(t -> inv.containsKey(t.getId()) ? inv.get(t.getId()).getAvailable() : 0)
                .sum();

        return new EventDtos.EventSummaryResponse(
                event.getId(),
                event.getOrganizerId(),
                event.getTitle(),
                event.getCategory(),
                event.getVenueName(),
                event.getVenueAddress(),
                event.getVenueCapacity(),
                event.getStartTime(),
                event.getEndTime(),
                event.getTimezone(),
                event.getStatus(),
                event.isWaitingRoomEnabled(),
                minPrice,
                totalAvailable
        );
    }

    private EventDtos.EventDetailResponse toDetailResponse(EventEntity event, List<EventDtos.TicketTypeResponse> types) {
        return new EventDtos.EventDetailResponse(
                event.getId(),
                event.getOrganizerId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getVenueName(),
                event.getVenueAddress(),
                event.getVenueCapacity(),
                event.getStartTime(),
                event.getEndTime(),
                event.getTimezone(),
                event.getStatus(),
                event.getCancellationCutoffAt(),
                event.isWaitingRoomEnabled(),
                event.getWaitingRoomThreshold(),
                event.getVersion(),
                event.getCreatedAt(),
                event.getUpdatedAt(),
                types
        );
    }

    private EventDtos.TicketTypeResponse toTicketTypeResponse(TicketTypeEntity type, TicketInventoryEntity inv) {
        int held = inv != null ? inv.getHeld() : 0;
        int sold = inv != null ? inv.getSold() : 0;
        int total = inv != null ? inv.getTotal() : type.getCapacity();
        int available = Math.max(0, total - held - sold);
        return new EventDtos.TicketTypeResponse(
                type.getId(),
                type.getEventId(),
                type.getName(),
                type.getDescription(),
                type.getPriceCents(),
                type.getCurrency(),
                type.getCapacity(),
                held,
                sold,
                available,
                type.getPerOrderLimit(),
                type.getSalesStart(),
                type.getSalesEnd()
        );
    }

    private Instant validateEventWindowAndTimezone(EventDtos.CreateOrUpdateEventRequest request) {
        try {
            ZoneId.of(request.timezone().trim());
        } catch (Exception ex) {
            throw new BadRequestException("Invalid IANA timezone: " + request.timezone());
        }
        if (!request.endTime().isAfter(request.startTime())) {
            throw new BadRequestException("Event end time must be after start time");
        }
        Instant cutoff = request.cancellationCutoffAt() != null
                ? request.cancellationCutoffAt()
                : request.startTime();
        if (cutoff.isAfter(request.startTime())) {
            throw new BadRequestException("Cancellation cutoff cannot be after event start time");
        }
        return cutoff;
    }

    private void validateTicketTypeRequest(EventDtos.CreateOrUpdateTicketTypeRequest request) {
        if (!request.salesEnd().isAfter(request.salesStart())) {
            throw new BadRequestException("Ticket sales end must be after sales start");
        }
        if (request.perOrderLimit() > request.capacity()) {
            throw new BadRequestException("Per-order limit cannot exceed ticket type capacity");
        }
    }

    private int clampSize(int size) {
        return Math.min(Math.max(size, 1), 50);
    }
}

