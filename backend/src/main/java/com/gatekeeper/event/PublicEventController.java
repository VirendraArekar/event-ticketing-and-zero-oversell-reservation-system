package com.gatekeeper.event;

import com.gatekeeper.common.web.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/events")
public class PublicEventController {

    private static final CacheControl PUBLIC_CACHE = CacheControl.maxAge(15, TimeUnit.SECONDS)
            .cachePublic()
            .staleWhileRevalidate(30, TimeUnit.SECONDS);

    private final EventService eventService;

    public PublicEventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<EventDtos.EventSummaryResponse>> listEvents(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size
    ) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(eventService.listPublicEvents(search, category, from, to, page, size));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<EventDtos.EventDetailResponse> getEvent(@PathVariable UUID eventId) {
        return ResponseEntity.ok()
                .cacheControl(PUBLIC_CACHE)
                .body(eventService.getPublicEventDetail(eventId));
    }
}

