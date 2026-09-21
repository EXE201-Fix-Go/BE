# Fix&Go Backend

Java 21 · Spring Boot 3.5 · PostgreSQL + PostGIS (Supabase) · Flyway.
Xác thực **OTP + JWT tự quản** — không lưu mật khẩu (C-06). Schema theo `docs/ERD.md` v4.1, 14 trạng thái đơn (§7.1).

## Chạy
1. Bật extension **PostGIS** trên Supabase (Database → Extensions) — migration cũng tự `CREATE EXTENSION IF NOT EXISTS`.
2. Đặt biến môi trường (xem `.env.example`): `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, tùy chọn `OTP_DEV_ECHO=true`, `BOOTSTRAP_ADMIN_PHONE`.
3. `.\mvnw.cmd spring-boot:run` — Flyway tạo schema `fixgo` (V1) + dữ liệu tham chiếu (V2: bảng giá, phí gọi thợ 30k, vòng điều phối).

Chi tiết kết nối: `docs/supabase-setup.md`. API: `docs/api.md`.

## Test
- Không cần Docker: trỏ `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` tới DB PostGIS (schema `fixgo_test` bị xóa & tạo lại mỗi lần chạy).
- Có Docker: Testcontainers `postgis/postgis` tự chạy.

```
.\mvnw.cmd test
```
`OrderFlowContract` đi trọn luồng OTP → tạo đơn → điều phối → báo giá → hoàn tất → thanh toán → đánh giá, kèm các ca bị từ chối (HT-04).

## Cấu trúc
```
com.fixgo
  auth/       OTP, thiết bị, refresh token xoay vòng, JWT (security/)
  user/       app_users, user_identities
  partner/    hồ sơ đối tác, KYC, tiệm & nhân viên, vị trí
  catalog/    bảng giá dịch vụ
  config/     phí gọi thợ, chính sách điều phối (RB-23: giá trị nằm trong DB)
  order/      đơn cứu hộ + state machine 14 trạng thái + lịch sử
  dispatch/   vòng broadcast, lời mời, nhận đơn (RB-36), scheduler hết hạn
  quote/      báo giá revision, bất biến (C-02)
  payment/    thanh toán tiền mặt pilot, đánh giá
  admin/      bootstrap ADMIN theo SĐT, khóa tài khoản, duyệt KYC
```
