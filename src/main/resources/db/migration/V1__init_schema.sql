-- Fix&Go schema — ERD v4.1. No password columns anywhere (RB-01 / C-06).
-- Extensions: PostGIS for geography, btree_gist for non-overlapping config ranges (RB-25).
CREATE EXTENSION IF NOT EXISTS postgis WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA public;

-- ---------------------------------------------------------------- 4. Xác thực
CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    full_name VARCHAR(100),
    role VARCHAR(20) NOT NULL CHECK (role IN ('CUSTOMER', 'PARTNER', 'ADMIN')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'LOCKED')),
    created_at TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ
);

CREATE TABLE user_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    provider VARCHAR(10) NOT NULL CHECK (provider IN ('PHONE', 'ZALO', 'EMAIL')),
    provider_uid VARCHAR(100) NOT NULL,
    verified_at TIMESTAMPTZ,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_user_identities_provider_uid UNIQUE (provider, provider_uid)
);
CREATE UNIQUE INDEX uk_user_identities_primary ON user_identities(user_id) WHERE is_primary;

CREATE TABLE otp_challenges (
    id UUID PRIMARY KEY,
    target VARCHAR(20) NOT NULL,
    purpose VARCHAR(20) NOT NULL CHECK (purpose IN ('LOGIN', 'DEVICE_BIND', 'PHONE_CHANGE')),
    code_hash VARCHAR(64) NOT NULL,
    attempt_count SMALLINT NOT NULL DEFAULT 0,
    max_attempts SMALLINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    request_ip VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_otp_challenges_target_created ON otp_challenges(target, created_at);
CREATE INDEX idx_otp_challenges_ip_created ON otp_challenges(request_ip, created_at);
CREATE INDEX idx_otp_challenges_open ON otp_challenges(expires_at) WHERE consumed_at IS NULL;

CREATE TABLE user_devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    device_fingerprint VARCHAR(200) NOT NULL,
    platform VARCHAR(10) NOT NULL CHECK (platform IN ('IOS', 'ANDROID', 'WEB')),
    biometric_public_key TEXT,
    biometric_type VARCHAR(20) NOT NULL DEFAULT 'NONE' CHECK (biometric_type IN ('FACE', 'FINGERPRINT', 'NONE')),
    bound_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    revoked_by UUID REFERENCES app_users(id)
);
CREATE INDEX idx_user_devices_active ON user_devices(user_id) WHERE revoked_at IS NULL;

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES user_devices(id),
    token_hash VARCHAR(64) NOT NULL,
    replaced_by UUID REFERENCES refresh_tokens(id),
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    revoke_reason VARCHAR(50),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX idx_refresh_tokens_device ON refresh_tokens(device_id);

-- ---------------------------------------------------------------- Dịch vụ & cấu hình
CREATE TABLE services (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(200),
    base_price NUMERIC(12, 0) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE call_out_fee_configs (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('GLOBAL', 'AREA')),
    scope_value VARCHAR(50),
    fee_amount NUMERIC(12, 0) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by UUID REFERENCES app_users(id),
    CONSTRAINT ex_call_out_fee_no_overlap EXCLUDE USING gist (
        scope_type WITH =, COALESCE(scope_value, '') WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
);

CREATE TABLE commission_configs (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(15) NOT NULL CHECK (scope_type IN ('GLOBAL', 'PARTNER_TYPE', 'PARTNER')),
    scope_value VARCHAR(50),
    commission_rate NUMERIC(5, 4) NOT NULL CHECK (commission_rate >= 0 AND commission_rate <= 1),
    commission_base VARCHAR(15) NOT NULL CHECK (commission_base IN ('ORDER_TOTAL', 'SERVICE_TOTAL', 'LABOR')),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by UUID REFERENCES app_users(id),
    CONSTRAINT ex_commission_no_overlap EXCLUDE USING gist (
        scope_type WITH =, COALESCE(scope_value, '') WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
);

CREATE TABLE dispatch_policies (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('GLOBAL', 'AREA')),
    scope_value VARCHAR(50),
    round_no INT NOT NULL CHECK (round_no >= 1),
    radius_m INT NOT NULL CHECK (radius_m > 0),
    timeout_seconds INT NOT NULL CHECK (timeout_seconds > 0),
    include_lower_priority BOOLEAN NOT NULL DEFAULT FALSE,
    is_final_round BOOLEAN NOT NULL DEFAULT FALSE,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ
);
CREATE INDEX idx_dispatch_policies_scope_round ON dispatch_policies(scope_type, scope_value, round_no);

-- ---------------------------------------------------------------- 5. Đối tác
CREATE TABLE partner_profiles (
    user_id UUID PRIMARY KEY REFERENCES app_users(id),
    parent_shop_id UUID REFERENCES partner_profiles(user_id),
    partner_type VARCHAR(15) NOT NULL CHECK (partner_type IN ('INDIVIDUAL', 'SHOP', 'SHOP_STAFF')),
    verification_status VARCHAR(10) NOT NULL CHECK (verification_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    verified_by UUID REFERENCES app_users(id),
    verified_at TIMESTAMPTZ,
    availability VARCHAR(10) NOT NULL DEFAULT 'OFFLINE' CHECK (availability IN ('ONLINE', 'BUSY', 'OFFLINE')),
    operational_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (operational_status IN ('ACTIVE', 'LOWER_PRIORITY', 'SUSPENDED')),
    operational_status_until TIMESTAMPTZ,
    shop_name VARCHAR(120),
    current_lat DOUBLE PRECISION,
    current_lng DOUBLE PRECISION,
    current_location GEOGRAPHY(Point, 4326),
    location_updated_at TIMESTAMPTZ,
    -- RB-10: SHOP_STAFF <=> parent_shop_id IS NOT NULL
    CONSTRAINT ck_partner_staff_parent CHECK (
        (partner_type = 'SHOP_STAFF' AND parent_shop_id IS NOT NULL)
        OR (partner_type <> 'SHOP_STAFF' AND parent_shop_id IS NULL))
);
CREATE INDEX idx_partner_profiles_location ON partner_profiles USING gist (current_location);
CREATE INDEX idx_partner_profiles_parent ON partner_profiles(parent_shop_id);

CREATE TABLE partner_documents (
    id UUID PRIMARY KEY,
    partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    document_type VARCHAR(30) NOT NULL,
    storage_key TEXT NOT NULL,
    review_status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by UUID REFERENCES app_users(id),
    reviewed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_partner_documents_partner ON partner_documents(partner_id);

CREATE TABLE partner_services (
    id UUID PRIMARY KEY,
    partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    service_id UUID NOT NULL REFERENCES services(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_partner_services UNIQUE (partner_id, service_id)
);

-- ---------------------------------------------------------------- Khách hàng
CREATE TABLE vehicles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    description VARCHAR(120) NOT NULL,
    license_plate VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------- 7. Đơn cứu hộ & điều phối
CREATE TABLE rescue_orders (
    id UUID PRIMARY KEY,
    order_code VARCHAR(20) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES app_users(id),
    vehicle_id UUID REFERENCES vehicles(id),
    requested_service_id UUID NOT NULL REFERENCES services(id),
    vehicle_description_snapshot TEXT,
    contact_name VARCHAR(100),
    contact_phone VARCHAR(20) NOT NULL,
    pickup_lat DOUBLE PRECISION NOT NULL,
    pickup_lng DOUBLE PRECISION NOT NULL,
    pickup_location GEOGRAPHY(Point, 4326) NOT NULL,
    pickup_address_text TEXT NOT NULL,
    pickup_note TEXT,
    call_out_fee_snapshot NUMERIC(12, 0) NOT NULL,
    call_out_fee_config_id UUID NOT NULL REFERENCES call_out_fee_configs(id),
    call_out_fee_confirmed_at TIMESTAMPTZ,
    status VARCHAR(25) NOT NULL CHECK (status IN (
        'PENDING_CONFIRMATION', 'REQUESTED', 'ASSIGNED', 'ARRIVED', 'CHECKING',
        'WAITING_FOR_APPROVAL', 'APPROVED', 'IN_PROGRESS', 'ADDITIONAL_QUOTE', 'PAUSED',
        'COMPLETED', 'CANCELLED', 'NO_PARTNER_FOUND', 'EXPIRED')),
    created_at TIMESTAMPTZ NOT NULL,
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_by UUID REFERENCES app_users(id),
    cancellation_source VARCHAR(10) CHECK (cancellation_source IN ('CUSTOMER', 'PARTNER', 'ADMIN', 'SYSTEM')),
    cancellation_reason TEXT,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_rescue_orders_customer ON rescue_orders(customer_id, created_at DESC);
CREATE INDEX idx_rescue_orders_status ON rescue_orders(status);
CREATE INDEX idx_rescue_orders_location ON rescue_orders USING gist (pickup_location);

-- Extra services the customer ticked on the confirm screen; the primary one stays in requested_service_id.
CREATE TABLE order_extra_services (
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    service_id UUID NOT NULL REFERENCES services(id),
    PRIMARY KEY (order_id, service_id)
);

-- Scene photos captured by the customer when creating the order (BR05 evidence).
CREATE TABLE order_photos (
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    line_no INT NOT NULL,
    url TEXT NOT NULL,
    PRIMARY KEY (order_id, line_no)
);

CREATE TABLE dispatch_rounds (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    policy_id UUID NOT NULL REFERENCES dispatch_policies(id),
    round_no INT NOT NULL,
    radius_m INT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    end_reason VARCHAR(15) CHECK (end_reason IN ('CLAIMED', 'TIMEOUT', 'CANCELLED', 'NO_CANDIDATE')),
    candidate_count INT NOT NULL DEFAULT 0,
    no_candidate_reason VARCHAR(20) CHECK (no_candidate_reason IN (
        'NO_PARTNER_ONLINE', 'OUT_OF_RADIUS', 'ALL_BUSY', 'ALL_SUSPENDED', 'NO_SERVICE_MATCH')),
    CONSTRAINT uk_dispatch_rounds_order_round UNIQUE (order_id, round_no)
);
CREATE INDEX idx_dispatch_rounds_open ON dispatch_rounds(expires_at) WHERE ended_at IS NULL;

CREATE TABLE order_assignments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    dispatch_round_id UUID REFERENCES dispatch_rounds(id),
    partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    status VARCHAR(10) NOT NULL CHECK (status IN ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'REASSIGNED', 'ENDED')),
    assignment_source VARCHAR(15) NOT NULL CHECK (assignment_source IN ('BROADCAST', 'SHOP_REASSIGN', 'ADMIN_ASSIGN')),
    assigned_by UUID REFERENCES app_users(id),
    decline_reason TEXT,
    offered_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    arrived_at TIMESTAMPTZ,
    ended_at TIMESTAMPTZ
);
CREATE INDEX idx_order_assignments_order ON order_assignments(order_id);
CREATE INDEX idx_order_assignments_partner_status ON order_assignments(partner_id, status);

CREATE TABLE dispatch_notifications (
    id UUID PRIMARY KEY,
    dispatch_round_id UUID NOT NULL REFERENCES dispatch_rounds(id),
    partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('PUSH', 'ZALO', 'SMS')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('QUEUED', 'SENT', 'DELIVERED', 'OPENED', 'FAILED')),
    provider_message_id VARCHAR(100),
    error_message TEXT,
    sent_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    opened_at TIMESTAMPTZ
);

CREATE TABLE order_status_history (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    from_status VARCHAR(25),
    to_status VARCHAR(25) NOT NULL,
    changed_by UUID REFERENCES app_users(id),
    actor_type VARCHAR(10) NOT NULL CHECK (actor_type IN ('CUSTOMER', 'PARTNER', 'ADMIN', 'SYSTEM')),
    note TEXT,
    changed_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_order_status_history_order ON order_status_history(order_id, changed_at);

-- ---------------------------------------------------------------- 8. Báo giá
CREATE TABLE quotes (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    created_by_partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    revision_no INT NOT NULL,
    quote_type VARCHAR(10) NOT NULL CHECK (quote_type IN ('INITIAL', 'ADDITIONAL')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('DRAFT', 'SENT', 'APPROVED', 'DECLINED', 'SUPERSEDED', 'EXPIRED')),
    call_out_fee_amount NUMERIC(12, 0) NOT NULL,
    labor_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    parts_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    surcharge_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    discount_amount NUMERIC(12, 0) NOT NULL DEFAULT 0,
    total_amount NUMERIC(12, 0) NOT NULL,
    sent_at TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    decided_by UUID REFERENCES app_users(id),
    decided_at TIMESTAMPTZ,
    decline_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_quotes_order_revision UNIQUE (order_id, revision_no),
    -- RB-47
    CONSTRAINT ck_quotes_total CHECK (
        total_amount = call_out_fee_amount + labor_amount + parts_amount + surcharge_amount - discount_amount)
);
-- RB-46: at most one SENT quote per order.
CREATE UNIQUE INDEX uk_quotes_one_sent ON quotes(order_id) WHERE status = 'SENT';

CREATE TABLE quote_items (
    id UUID PRIMARY KEY,
    quote_id UUID NOT NULL REFERENCES quotes(id),
    service_id UUID REFERENCES services(id),
    line_no INT NOT NULL,
    item_type VARCHAR(10) NOT NULL CHECK (item_type IN ('LABOR', 'PART', 'SURCHARGE', 'DISCOUNT', 'SUPPORT')),
    description VARCHAR(200) NOT NULL,
    quantity NUMERIC(8, 2) NOT NULL,
    unit_price NUMERIC(12, 0) NOT NULL,
    line_amount NUMERIC(12, 0) NOT NULL,
    CONSTRAINT uk_quote_items_line UNIQUE (quote_id, line_no)
);

-- ---------------------------------------------------------------- 10. Thanh toán
CREATE TABLE payments (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES rescue_orders(id),
    quote_id UUID REFERENCES quotes(id),   -- NULL when only the call-out fee is due (cancel after arrival)
    method VARCHAR(15) NOT NULL CHECK (method IN ('CASH', 'BANK_TRANSFER', 'GATEWAY')),
    fund_holder VARCHAR(10) NOT NULL CHECK (fund_holder IN ('PARTNER', 'PLATFORM')),
    collected_by_partner_id UUID REFERENCES partner_profiles(user_id),
    amount NUMERIC(12, 0) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'FAILED')),
    confirmed_by UUID REFERENCES app_users(id),
    confirmed_by_role VARCHAR(10) CHECK (confirmed_by_role IN ('CUSTOMER', 'PARTNER', 'ADMIN')),
    confirmed_at TIMESTAMPTZ,
    provider_transaction_id VARCHAR(100),
    idempotency_key VARCHAR(80) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_payments_order ON payments(order_id);

-- ---------------------------------------------------------------- 11. Chất lượng
CREATE TABLE reviews (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES rescue_orders(id),
    partner_id UUID NOT NULL REFERENCES partner_profiles(user_id),
    rating SMALLINT NOT NULL CHECK (rating BETWEEN 1 AND 5),
    feedback TEXT,
    is_hidden BOOLEAN NOT NULL DEFAULT FALSE,
    hidden_by UUID REFERENCES app_users(id),
    hidden_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

-- ---------------------------------------------------------------- Geography triggers
-- JPA writes plain lat/lng; the database keeps the geography column in sync for ST_DWithin queries.
CREATE OR REPLACE FUNCTION sync_pickup_location() RETURNS trigger AS $$
BEGIN
    NEW.pickup_location := ST_SetSRID(ST_MakePoint(NEW.pickup_lng, NEW.pickup_lat), 4326)::geography;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_rescue_orders_location
    BEFORE INSERT OR UPDATE OF pickup_lat, pickup_lng ON rescue_orders
    FOR EACH ROW EXECUTE FUNCTION sync_pickup_location();

CREATE OR REPLACE FUNCTION sync_partner_location() RETURNS trigger AS $$
BEGIN
    IF NEW.current_lat IS NULL OR NEW.current_lng IS NULL THEN
        NEW.current_location := NULL;
    ELSE
        NEW.current_location := ST_SetSRID(ST_MakePoint(NEW.current_lng, NEW.current_lat), 4326)::geography;
    END IF;
    RETURN NEW;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_partner_profiles_location
    BEFORE INSERT OR UPDATE OF current_lat, current_lng ON partner_profiles
    FOR EACH ROW EXECUTE FUNCTION sync_partner_location();
