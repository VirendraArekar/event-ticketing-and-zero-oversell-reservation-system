package com.gatekeeper.messaging;

public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String ORDER_EVENTS = "order-events";
    public static final String TICKET_EVENTS = "ticket-events";
    public static final String CHECKIN_EVENTS = "checkin-events";
    public static final String WAITLIST_EVENTS = "waitlist-events";
    public static final String EVENT_LIFECYCLE_EVENTS = "event-lifecycle-events";

    public static final String GROUP_NOTIFICATIONS = "notification-consumer";
    public static final String GROUP_DASHBOARD = "dashboard-aggregator";
    public static final String GROUP_WAITLIST = "waitlist-consumer";
}

