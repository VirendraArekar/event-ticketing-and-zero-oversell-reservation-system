CREATE TABLE users (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    full_name VARCHAR(150) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE user_roles (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    role VARCHAR(32) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE refresh_tokens (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_token_hash (token_hash),
    INDEX idx_refresh_user_revoked (user_id, revoked),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    organizer_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(80) NOT NULL,
    venue_name VARCHAR(200) NOT NULL,
    venue_address VARCHAR(300) NOT NULL,
    venue_capacity INT NOT NULL,
    start_time DATETIME(6) NOT NULL,
    end_time DATETIME(6) NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    cancellation_cutoff_at DATETIME(6) NULL,
    waiting_room_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    waiting_room_threshold INT NOT NULL DEFAULT 25,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_events_status_start (status, start_time),
    INDEX idx_events_organizer_created (organizer_id, created_at),
    INDEX idx_events_category_status_start (category, status, start_time),
    CONSTRAINT fk_events_organizer FOREIGN KEY (organizer_id) REFERENCES users (id),
    CONSTRAINT chk_events_venue_capacity CHECK (venue_capacity > 0),
    CONSTRAINT chk_events_time_order CHECK (end_time > start_time),
    CONSTRAINT chk_events_waiting_threshold CHECK (waiting_room_threshold > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_staff_assignments (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    assigned_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_staff (event_id, user_id),
    INDEX idx_staff_user (user_id),
    CONSTRAINT fk_staff_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_staff_assigned_by FOREIGN KEY (assigned_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ticket_types (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    price_cents BIGINT NOT NULL DEFAULT 0,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    capacity INT NOT NULL,
    per_order_limit INT NOT NULL,
    sales_start DATETIME(6) NOT NULL,
    sales_end DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ticket_type_event_name (event_id, name),
    INDEX idx_ticket_types_event (event_id),
    CONSTRAINT fk_ticket_types_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT chk_ticket_types_price CHECK (price_cents >= 0),
    CONSTRAINT chk_ticket_types_capacity CHECK (capacity > 0),
    CONSTRAINT chk_ticket_types_limit CHECK (per_order_limit > 0),
    CONSTRAINT chk_ticket_types_sales_window CHECK (sales_end > sales_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ticket_inventory (
    ticket_type_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total INT NOT NULL,
    held INT NOT NULL DEFAULT 0,
    sold INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (ticket_type_id),
    INDEX idx_inventory_event (event_id),
    CONSTRAINT fk_inventory_ticket_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_inventory_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT chk_inventory_bounds CHECK (held >= 0 AND sold >= 0 AND total > 0 AND (held + sold) <= total)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE orders (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    total_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    idempotency_key VARCHAR(128) NOT NULL,
    hold_expires_at DATETIME(6) NULL,
    payment_deadline_at DATETIME(6) NULL,
    confirmed_at DATETIME(6) NULL,
    cancelled_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_orders_event_status (event_id, status),
    INDEX idx_orders_user_created (user_id, created_at),
    INDEX idx_orders_hold_expiry (status, hold_expires_at),
    INDEX idx_orders_payment_deadline (status, payment_deadline_at),
    CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_orders_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT chk_orders_total CHECK (total_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE order_items (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_type_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    unit_price_cents BIGINT NOT NULL,
    line_total_cents BIGINT NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_item_type (order_id, ticket_type_id),
    INDEX idx_order_items_ticket_type (ticket_type_id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_order_items_ticket_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id),
    CONSTRAINT chk_order_items_qty CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price CHECK (unit_price_cents >= 0),
    CONSTRAINT chk_order_items_line_total CHECK (line_total_cents >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE idempotency_records (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    response_status INT NOT NULL,
    response_body TEXT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    INDEX idx_idempotency_expires (expires_at),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_idempotency_order FOREIGN KEY (order_id) REFERENCES orders (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE payment_ledger (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider VARCHAR(64) NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    amount_cents BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'USD',
    reference_code VARCHAR(128) NULL,
    notes VARCHAR(500) NULL,
    actor_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ledger_order_created (order_id, created_at),
    INDEX idx_ledger_event_created (event_id, created_at),
    CONSTRAINT fk_ledger_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_ledger_event FOREIGN KEY (event_id) REFERENCES events (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE tickets (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    order_item_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_type_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_code VARCHAR(64) NOT NULL,
    qr_token VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    checked_in_at DATETIME(6) NULL,
    checked_in_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tickets_code (ticket_code),
    INDEX idx_tickets_event_status (event_id, status),
    INDEX idx_tickets_user_created (user_id, created_at),
    INDEX idx_tickets_order (order_id),
    CONSTRAINT fk_tickets_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_tickets_order_item FOREIGN KEY (order_item_id) REFERENCES order_items (id),
    CONSTRAINT fk_tickets_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_tickets_ticket_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id),
    CONSTRAINT fk_tickets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_tickets_checked_in_by FOREIGN KEY (checked_in_by) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE check_in_logs (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    staff_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    scanned_at DATETIME(6) NOT NULL,
    notes VARCHAR(255) NULL,
    PRIMARY KEY (id),
    INDEX idx_checkin_logs_event_time (event_id, scanned_at),
    INDEX idx_checkin_logs_ticket (ticket_id),
    CONSTRAINT fk_checkin_logs_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT fk_checkin_logs_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_checkin_logs_staff FOREIGN KEY (staff_user_id) REFERENCES users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE waitlist_entries (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_type_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    offer_order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    offered_at DATETIME(6) NULL,
    offer_expires_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_waitlist_queue (ticket_type_id, status, created_at),
    INDEX idx_waitlist_user_created (user_id, created_at),
    INDEX idx_waitlist_offer_expiry (status, offer_expires_at),
    CONSTRAINT fk_waitlist_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_ticket_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_waitlist_offer_order FOREIGN KEY (offer_order_id) REFERENCES orders (id) ON DELETE SET NULL,
    CONSTRAINT chk_waitlist_qty CHECK (quantity > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE notifications (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    reference_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_notifications_user_created (user_id, created_at),
    INDEX idx_notifications_user_unread (user_id, is_read, created_at),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_notifications_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE outbox_events (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic VARCHAR(100) NOT NULL,
    partition_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE processed_kafka_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_dashboard_stats (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    total_capacity INT NOT NULL DEFAULT 0,
    total_sold INT NOT NULL DEFAULT 0,
    total_revenue_cents BIGINT NOT NULL DEFAULT 0,
    total_checked_in INT NOT NULL DEFAULT 0,
    total_cancelled INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id),
    CONSTRAINT fk_dash_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ticket_type_dashboard_stats (
    ticket_type_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ticket_type_name VARCHAR(120) NOT NULL,
    capacity INT NOT NULL DEFAULT 0,
    sold_count INT NOT NULL DEFAULT 0,
    revenue_cents BIGINT NOT NULL DEFAULT 0,
    checked_in_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (ticket_type_id),
    INDEX idx_tt_dash_event (event_id),
    CONSTRAINT fk_tt_dash_ticket_type FOREIGN KEY (ticket_type_id) REFERENCES ticket_types (id) ON DELETE CASCADE,
    CONSTRAINT fk_tt_dash_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_checkin_buckets (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    checkin_count INT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, bucket_start),
    CONSTRAINT fk_checkin_bucket_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE event_sales_buckets (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    bucket_start DATETIME(6) NOT NULL,
    tickets_sold INT NOT NULL DEFAULT 0,
    revenue_cents BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (event_id, bucket_start),
    CONSTRAINT fk_sales_bucket_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

