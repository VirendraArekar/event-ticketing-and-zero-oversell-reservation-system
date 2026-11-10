package com.gatekeeper.common.exception;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class WaitingRoomRequiredException extends ApiException {

    private final UUID eventId;
    private final String queueToken;
    private final long position;

    public WaitingRoomRequiredException(String message, UUID eventId, String queueToken, long position) {
        super(HttpStatus.PRECONDITION_REQUIRED, message);
        this.eventId = eventId;
        this.queueToken = queueToken;
        this.position = position;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getQueueToken() {
        return queueToken;
    }

    public long getPosition() {
        return position;
    }
}

