-- Support bounded history pages, optional status filters and latest related-row lookups.
CREATE INDEX IF NOT EXISTS idx_rescue_orders_customer_status_created
    ON rescue_orders(customer_id, status, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_order_assignments_order_status_accepted
    ON order_assignments(order_id, status, accepted_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_payments_order_created
    ON payments(order_id, created_at DESC, id DESC);
