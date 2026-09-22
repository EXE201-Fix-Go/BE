-- Travel-by-km fee: a new quote bucket (TRAVEL) plus the per-km rate as reference data (RB-23).

-- 1. Quote gains a travel bucket; the total check (RB-47) must account for it.
ALTER TABLE quotes ADD COLUMN travel_amount NUMERIC(12, 0) NOT NULL DEFAULT 0;
ALTER TABLE quotes DROP CONSTRAINT ck_quotes_total;
ALTER TABLE quotes ADD CONSTRAINT ck_quotes_total CHECK (
    total_amount = call_out_fee_amount + labor_amount + travel_amount + parts_amount + surcharge_amount - discount_amount);

-- 2. TRAVEL becomes a valid quote line type.
ALTER TABLE quote_items DROP CONSTRAINT IF EXISTS quote_items_item_type_check;
ALTER TABLE quote_items ADD CONSTRAINT quote_items_item_type_check
    CHECK (item_type IN ('LABOR', 'PART', 'SURCHARGE', 'DISCOUNT', 'SUPPORT', 'TRAVEL'));

-- 3. The distance and fee are snapshotted onto the order when a partner accepts (nullable until then).
ALTER TABLE rescue_orders ADD COLUMN travel_distance_km NUMERIC(8, 1);
ALTER TABLE rescue_orders ADD COLUMN travel_fee_snapshot NUMERIC(12, 0);
ALTER TABLE rescue_orders ADD COLUMN travel_fee_config_id UUID;

-- 4. Per-km rate lives in the DB (mirrors call_out_fee_configs).
CREATE TABLE travel_fee_configs (
    id UUID PRIMARY KEY,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('GLOBAL', 'AREA')),
    scope_value VARCHAR(50),
    per_km_amount NUMERIC(12, 0) NOT NULL,
    free_km NUMERIC(8, 1) NOT NULL DEFAULT 0,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_to TIMESTAMPTZ,
    created_by UUID REFERENCES app_users(id),
    CONSTRAINT ex_travel_fee_no_overlap EXCLUDE USING gist (
        scope_type WITH =, COALESCE(scope_value, '') WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
);

ALTER TABLE rescue_orders ADD CONSTRAINT fk_rescue_orders_travel_fee_config
    FOREIGN KEY (travel_fee_config_id) REFERENCES travel_fee_configs(id);

-- Pilot rate: 5.000 đ/km, no free kilometres. Adjustable without a code change.
INSERT INTO travel_fee_configs (id, scope_type, scope_value, per_km_amount, free_km, effective_from, effective_to, created_by)
VALUES ('55555555-0000-4000-8000-000000000001', 'GLOBAL', NULL, 5000, 0, TIMESTAMPTZ '2026-01-01 00:00:00+07', NULL, NULL);
