# Triển khai và go-live

Mục tiêu: backend chạy trên Render, dữ liệu ở Supabase, web FE và app Mobile trỏ vào đó. Cấu hình mẫu: `render.yaml`.
Mọi thứ nhạy cảm (khóa SMS, service key, mật khẩu DB) chỉ đặt ở biến môi trường của Render, không commit.

## 1. Chuẩn bị tài khoản

| Dịch vụ | Dùng để | Việc cần làm |
|---|---|---|
| Supabase | Postgres + Storage | Có project; lấy chuỗi kết nối **pooler** (port 5432), `SUPABASE_URL` và **service-role key** |
| eSMS.vn | Gửi OTP | Đăng ký brandname và **mẫu tin OTP** (xem §4), lấy `ApiKey`/`SecretKey` |
| Render | Chạy backend | Kết nối repo GitHub, tạo Blueprint từ `render.yaml` (gói trả phí: gói free sẽ ngủ sau 15 phút) |
| Hosting web | Chạy web FE | Cloudflare Pages hoặc GitHub Pages (xem §6) |
| Expo (EAS) | Build app Mobile | Tài khoản Expo; Apple Developer / Google Play nếu lên cửa hàng |

## 2. Cơ sở dữ liệu

- Backend tự migrate bằng Flyway (`V1…V7`) vào schema `DB_SCHEMA` ở lần khởi động đầu. Schema phải **thuộc riêng backend này**.
- **Cần quyết định:** dev đang dùng `fixgo_v2` vì schema `fixgo` do project khác quản lý. Production nên dùng một schema riêng
  (ví dụ `fixgo_prod`) để dữ liệu test của dev không lẫn vào. Đặt `DB_SCHEMA` rồi deploy; Flyway tạo bảng.
- Bật backup/PITR của Supabase trước khi mở cho người dùng thật.
- Ảnh hiện trường và giấy tờ KYC nằm ở Supabase Storage, **không** nằm trong DB. Backend tự tạo hai bucket ở lần upload đầu:
  `fixgo-public` (công khai) và `fixgo-kyc` (riêng tư). Kiểm tra trên dashboard rằng `fixgo-kyc` ở chế độ **Private**.

## 3. Biến môi trường (backend)

| Biến | Bắt buộc | Ghi chú |
|---|:-:|---|
| `SPRING_PROFILES_ACTIVE=prod` | ✓ | Bật `ProductionSafetyCheck`: backend **không khởi động** nếu thiếu/đặt sai các mục dưới |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_SCHEMA` | ✓ | Xem §2. `DB_POOL_SIZE` mặc định 10 (gợi ý 8 để không chạm giới hạn pooler) |
| `JWT_SECRET` | ✓ | **Base64 của ≥ 32 byte ngẫu nhiên** (không phải chuỗi tự đặt). Sinh bằng `node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"`. Sai định dạng thì backend không khởi động. Đổi giá trị = đăng xuất mọi người |
| `OTP_DEV_ECHO` | ✓ | Phải `false` (nếu `true`, API trả luôn mã OTP) |
| `SMS_PROVIDER=esms`, `ESMS_API_KEY`, `ESMS_SECRET_KEY`, `ESMS_BRANDNAME` | ✓ | `log` bị từ chối ở prod. `ESMS_CONTENT_TEMPLATE` nếu mẫu tin đã đăng ký khác mặc định |
| `STORAGE_PROVIDER=supabase`, `SUPABASE_URL`, `SUPABASE_SERVICE_KEY` | ✓ | `local` bị từ chối ở prod (đĩa Render bị xóa mỗi lần deploy) |
| `CORS_ALLOWED_ORIGINS` | ✓ | Danh sách origin của web, cách nhau dấu phẩy. Chứa `localhost` hoặc `*` thì bị từ chối ở prod |
| `BOOTSTRAP_ADMIN_PHONE` | ✓ | SĐT admin đầu tiên (đăng nhập bằng OTP). `BOOTSTRAP_ADMIN_NAME` tùy chọn |
| `TRUSTED_PROXY_HOPS` | ✓ | Số proxy của nền tảng đứng trước app (Render: bắt đầu bằng `1`). Kiểm tra theo §5 |
| `DISPATCH_LOCATION_MAX_AGE` | khuyến nghị | `5m`: bỏ qua thợ có vị trí quá cũ. Chỉ bật khi app thợ đã gửi vị trí định kỳ (bản FE/Mobile mới đã gửi mỗi 60 giây) |
| `DISABLED_SERVICE_CODES` | khuyến nghị | Mặc định `towing` (kéo xe cần luồng bàn giao BR04/BR05 trước) |
| `OTP_MAX_PER_TARGET_PER_HOUR`, `OTP_MAX_PER_IP_PER_HOUR` | tùy chọn | Mặc định 5 và 30 mỗi giờ |

## 4. Gửi OTP bằng eSMS

1. Đăng ký **brandname** với eSMS (cần duyệt, vài ngày).
2. Đăng ký **mẫu tin chăm sóc khách hàng** giống hệt mẫu mặc định (hoặc đặt `ESMS_CONTENT_TEMPLATE` theo mẫu bạn đăng ký, giữ `{code}` và `{minutes}`):
   `{code} la ma xac thuc Fix&Go cua ban, co hieu luc {minutes} phut. Tuyet doi khong chia se ma nay voi bat ky ai.`
   Sai mẫu thì eSMS trả mã `146` và người dùng không đăng nhập được.
3. Thử khóa trước khi mở cho người dùng: đặt tạm `ESMS_SANDBOX=true` (eSMS chỉ kiểm tra request, không gửi, không tính tiền), xin OTP một lần,
   xem log có dòng `sms.esms sent ... (sandbox)`. Sau đó bỏ biến này.
4. Nạp tiền tài khoản eSMS; hết tiền thì không ai nhận được mã (API trả 502 `OTP_DELIVERY_FAILED`).

## 5. Deploy lần đầu và kiểm tra

1. Render → New → Blueprint → chọn repo; điền các biến `sync: false`.
2. Chờ build; mở log, tìm `Started FixGoApplication` và `Successfully applied N migration`.
3. Kiểm tra nhanh (chỉ đọc):
   ```bash
   node scripts/smoke.mjs https://<backend>/api/v1 https://<origin-web>
   ```
4. **Kiểm tra `TRUSTED_PROXY_HOPS`** (giới hạn OTP theo IP chỉ đúng khi số này đúng). Từ máy bạn xin một OTP bằng số của bạn, rồi trong SQL editor của Supabase:
   ```sql
   select request_ip, created_at from <DB_SCHEMA>.otp_challenges order by created_at desc limit 3;
   ```
   `request_ip` phải đúng là IP công khai của máy bạn (xem tại `curl ifconfig.me`). Nếu là IP nội bộ của Render, tăng `TRUSTED_PROXY_HOPS`;
   nếu là IP bạn tự đặt vào header `X-Forwarded-For`, giảm xuống.
5. Đăng nhập admin bằng `BOOTSTRAP_ADMIN_PHONE`, vào trang quản trị: tổng quan, hàng chờ KYC phải mở được.
6. Chạy một đơn thật từ đầu đến cuối bằng hai điện thoại (khách + thợ): đặt đơn, nhận, báo giá, duyệt, hoàn tất, đánh giá.

## 6. Web FE

- Biến lúc build: `VITE_API_BASE_URL=https://<backend>/api/v1` (đặt trong GitHub: Settings → Variables → Actions), `VITE_SUPPORT_PHONE` (hotline; trống thì ẩn nút gọi).
  **Không** đặt `VITE_PARTNER_GPS=off` ở bản chạy thật.
- Origin của web phải có trong `CORS_ALLOWED_ORIGINS`.
- Hosting: `vite.config.ts` đặt `base: '/'` (Cloudflare Pages / tên miền riêng). GitHub Pages dạng dự án (`.../FE/`) cần đổi `base` cho khớp.
  Bản build có kèm `404.html` để mở thẳng đường dẫn con (`/partner/login`) không bị 404 trên GitHub Pages.

## 7. Mobile

`EXPO_PUBLIC_API_BASE_URL` đặt bằng `eas env:create` (README của repo Mobile). `eas build --profile preview --platform android` cho APK thử trên máy thật
trước khi gửi cửa hàng. Mobile chỉ gọi API, không cần CORS.

## 8. Trước khi mở cho người dùng thật

- [ ] Backup Supabase đã bật; đã thử khôi phục một lần.
- [ ] Các biến ở §3 đã đặt; backend khởi động sạch (nếu thiếu mục nào, log ghi rõ lý do dừng).
- [ ] `scripts/smoke.mjs` đạt toàn bộ; §5.4 đã kiểm.
- [ ] Đã chạy một đơn hoàn chỉnh trên bản thật (cả web và Mobile nếu phát hành cả hai).
- [ ] **Dọn dữ liệu test** (khóa, không xóa — đơn cũ còn tham chiếu). Xem trước rồi mới áp dụng:
  ```bash
  node scripts/lock-test-accounts.mjs https://<backend>/api/v1 - --phones "^\+84(933|901|902)"            # dry-run
  node scripts/lock-test-accounts.mjs https://<backend>/api/v1 - --phones "^\+84(933|901|902)" --apply
  ```
  (đặt `ADMIN_TOKEN` trước; điều chỉnh regex cho đúng các đầu số test của bạn). Nếu dùng schema riêng cho production thì không có dữ liệu test cần dọn.
- [ ] Ít nhất 2 admin; số admin không lộ công khai.
- [ ] Branch protection trên `main`: bắt buộc CI xanh trước khi merge (BE, FE, Mobile đều có workflow).

## 9. Rollback và sự cố

- **Backend lỗi sau deploy:** Render → Deploys → Rollback về bản trước. Migration Flyway chỉ **thêm** (V7 thêm bảng), nên bản cũ vẫn chạy được trên schema mới.
- **Không ai đăng nhập được:** xem log `sms.esms ...` (CodeResult 101 sai khóa, 104 brandname, 146 sai mẫu tin, hết tiền), hoặc eSMS đang lỗi.
- **Lộ khóa:** đổi `SUPABASE_SERVICE_KEY` / khóa eSMS trên nhà cung cấp rồi cập nhật Render; đổi `JWT_SECRET` nếu nghi lộ token.
- **Tài khoản bị lạm dụng:** admin khóa qua trang quản trị (`PATCH /admin/users/{id}/status`); khóa thu hồi mọi token ngay.

## 10. Giới hạn đã biết

- Mỗi app thợ gọi `GET /partner/dashboard` mỗi 3 giây (~0,4 giây xử lý ở backend khi DB ở xa). Với hàng trăm thợ online cùng lúc cần
  tăng tần suất thưa hơn, thêm cache/ETag hoặc đẩy thông báo (push/WebSocket). Chưa làm.
- Một instance backend. Chạy nhiều instance cần kiểm lại job điều phối (`DispatchScheduler`), hiện giả định một bản chạy.
- Chưa có: thông báo đẩy, đối soát hoa hồng, chế tài, khiếu nại, chat, lịch sử thu nhập chi tiết, tìm địa chỉ → tọa độ (đơn dùng vị trí GPS; địa chỉ gõ tay chỉ là mô tả).
- Địa chỉ tìm thợ dựa trên GPS thiết bị; không có GPS thì dùng vị trí khu vực thí điểm.
