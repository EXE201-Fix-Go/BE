-- V5: Performance indexes for dispatch hot-path and scheduler tick().
-- NOTE: CONCURRENTLY cannot run inside a transaction.
-- Flyway wraps each script in a transaction by default; use IF NOT EXISTS so re-runs are safe.

-- [1] Partial composite index cho findCandidates() — chi index partner dang ONLINE co vi tri
CREATE INDEX IF NOT EXISTS idx_partner_profiles_dispatch
    ON partner_profiles(verification_status, operational_status)
    WHERE availability = 'ONLINE'
      AND current_location IS NOT NULL;

-- [2] Index cho partner_services EXISTS subquery trong findCandidates()
CREATE INDEX IF NOT EXISTS idx_partner_services_active
    ON partner_services(service_id, partner_id)
    WHERE is_active = true;

-- [3] Partial index cho app_users JOIN trong findCandidates()
CREATE INDEX IF NOT EXISTS idx_app_users_active
    ON app_users(id)
    WHERE status = 'ACTIVE';

-- [4] Index cho scheduler tick(): findByStatusCreatedBefore(PENDING_CONFIRMATION, cutoff)
CREATE INDEX IF NOT EXISTS idx_rescue_orders_status_created
    ON rescue_orders(status, created_at)
    WHERE status IN ('PENDING_CONFIRMATION', 'REQUESTED');