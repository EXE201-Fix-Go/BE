-- Làm sạch dữ liệu test: xoá các đơn CHƯA HOÀN THÀNH (còn đang chạy) và toàn bộ dữ liệu con.
-- GIỮ LẠI đơn đã kết thúc để làm lịch sử: COMPLETED, CANCELLED, NO_PARTNER_FOUND, EXPIRED.
-- Chạy 1 lần trong Supabase → SQL Editor. Schema mặc định của backend này là fixgo_v2.
-- (Nếu backend của bạn dùng schema khác, thay 'fixgo_v2' bên dưới cho khớp DB_SCHEMA.)

BEGIN;

-- Tập id các đơn cần xoá (snapshot trước khi xoá con).
CREATE TEMP TABLE _inflight ON COMMIT DROP AS
SELECT id FROM fixgo_v2.rescue_orders
WHERE status NOT IN ('COMPLETED', 'CANCELLED', 'NO_PARTNER_FOUND', 'EXPIRED');

-- Xoá con trước, cha sau (theo khoá ngoại).
DELETE FROM fixgo_v2.quote_items
 WHERE quote_id IN (SELECT id FROM fixgo_v2.quotes WHERE order_id IN (SELECT id FROM _inflight));
DELETE FROM fixgo_v2.payments             WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.quotes               WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.reviews              WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.dispatch_notifications
 WHERE dispatch_round_id IN (SELECT id FROM fixgo_v2.dispatch_rounds WHERE order_id IN (SELECT id FROM _inflight));
DELETE FROM fixgo_v2.order_assignments    WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.dispatch_rounds      WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.order_status_history WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.order_photos         WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.order_extra_services WHERE order_id IN (SELECT id FROM _inflight);
DELETE FROM fixgo_v2.rescue_orders        WHERE id IN (SELECT id FROM _inflight);

-- Giải phóng thợ đang BUSY vì đơn vừa bị xoá → cho nhận đơn mới (DevSeed cũng set ONLINE khi restart).
UPDATE fixgo_v2.partner_profiles SET availability = 'ONLINE' WHERE availability = 'BUSY';

COMMIT;

-- Sau khi chạy: các đơn seed đang kẹt (ASSIGNED/ARRIVED/CHECKING/WAITING_FOR_APPROVAL/IN_PROGRESS/
-- ADDITIONAL_QUOTE/PAUSED) đã bị xoá; thợ tương ứng được giải phóng (ONLINE trở lại).
-- DevSeed sẽ KHÔNG tạo lại vì mỗi khách seed vẫn còn 1 đơn đã kết thúc (điều kiện chống tạo trùng).
