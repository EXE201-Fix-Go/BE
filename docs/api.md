# Fix&Go API v1 — luồng đặt đơn

Base URL: `http://localhost:8080/api/v1`. JSON, UTC ISO-8601, UUID. Gửi `Authorization: Bearer <accessToken>`
cho mọi endpoint trừ OTP/refresh và `GET /services`. Field lạ trong body bị từ chối (400).

## Xác thực — OTP + JWT tự quản (QD-12, C-06: không có mật khẩu)

| Endpoint | Body | Trả về |
|---|---|---|
| `POST /auth/otp` | `{"phone":"0901234567"}` | `{otpId, expiresInSec, devCode?}` — `devCode` chỉ có khi `OTP_DEV_ECHO=true` |
| `POST /auth/otp/verify` | `{"otpId","code","platform?":"IOS\|ANDROID\|WEB","deviceFingerprint?"}` | `{accessToken, refreshToken, tokenType, expiresIn, refreshExpiresAt, role, user}` |
| `POST /auth/refresh` | `{"refreshToken"}` | cùng envelope; token cũ hết hiệu lực, dùng lại → thu hồi cả thiết bị |
| `POST /auth/logout` / `/logout-all` | — | 204 |

`role` là mã app-level FE điều hướng: `CUSTOMER | P_IND | P_SHOP | P_STAFF | ADMIN`
(ERD lưu `app_users.role` + `partner_profiles.partner_type`, backend map ở tầng API).
Số điện thoại lần đầu → tài khoản `CUSTOMER`. OTP: 6 số, 5 phút, 5 lần thử, dùng một lần (RB-03), giới hạn theo số & IP (RB-04).

## Người dùng
- `GET /users/me`, `PATCH /users/me {"fullName"}`.

## Đối tác
- `POST /partner-registration` (tài khoản vừa OTP): `{fullName, partnerType: INDIVIDUAL|SHOP, shopName?, serviceCodes[], documents[{documentType: ID_FRONT|ID_BACK|SELFIE|LICENSE|OTHER, storageKey}]}` → hồ sơ `PENDING` (BR06).
- `GET /partner/me`; `PATCH /partner/me/presence {availability, lat, lng}` — chỉ hồ sơ `APPROVED` mới `ONLINE` được.
- Chủ tiệm: `GET|POST /partner/shop/staff` — `POST {phone, fullName}` tạo tài khoản thợ (`SHOP_STAFF`, vẫn `PENDING` KYC — RB-12).
- Admin: `POST /admin/partners/{userId}/verify {"status":"APPROVED|REJECTED"}`.

## Đơn cứu hộ (khách)
| Endpoint | Ghi chú |
|---|---|
| `GET /services` | bảng giá (public) |
| `POST /orders` | `{serviceId, extraServiceIds?, addressText, note?, photoUrls?, lat, lng, vehicleDescription?, contactName?, contactPhone?}` → `PENDING_CONFIRMATION`, `callOutFee` snapshot |
| `POST /orders/{id}/confirm` | xác nhận phí gọi thợ → `REQUESTED` + broadcast vòng 1 (BR01) |
| `GET /orders`, `GET /orders/{id}` | đơn đầy đủ: `partner`, `quote` (revision mới nhất), `payment`, `history`, `contactName/contactPhone` |
| `GET /orders/{id}/status` | **poll rẻ** `{id, orderCode, status, version}` — FE poll cái này, chỉ tải đơn đầy đủ khi status đổi |
| `POST /orders/{id}/cancel {"reason"}` | BR09; hủy sau khi thợ đã tới → phát sinh `payment` phí gọi thợ (30k, `quote_id` NULL) |
| `POST /orders/{id}/quotes/{qid}/approve` · `/decline {"reason"?}` | chỉ khách của đơn (RB-45); approve → `APPROVED` → `IN_PROGRESS` |
| `POST /orders/{id}/payment/confirm` | dự phòng: xác nhận khoản đang PENDING (vd phí gọi thợ khi hủy). Đơn hoàn tất thì thợ đã thu tiền → payment tự CONFIRMED |
| `POST /orders/{id}/review {"rating":1..5,"feedback"?}` | sau `COMPLETED`, 1 lần/đơn |

## Điều phối & thực hiện (thợ)
| Endpoint | Ghi chú |
|---|---|
| `GET /partner/offers` | lời mời đang mở (broadcast theo bán kính, BR07) |
| `POST /partner/offers/{assignmentId}/accept` | ai nhận trước được (RB-36); người sau nhận 409 |
| `POST /partner/offers/{assignmentId}/decline` | |
| `GET /partner/jobs` | đơn đang thực hiện |
| `GET /partner/stats` | `{completedToday, earnedToday, completedTotal, averageRating, reviewCount, activeJobs}` cho dashboard |
| `POST /orders/{id}/arrive` → `/check` | `ASSIGNED → ARRIVED → CHECKING` |
| `POST /orders/{id}/quotes {items:[{itemType: LABOR\|PART\|SURCHARGE\|DISCOUNT\|SUPPORT, description, quantity, unitPrice, serviceId?}], validMinutes?}` | từ `CHECKING` = `INITIAL`; từ `IN_PROGRESS` = `ADDITIONAL` (BR03). Mỗi revision chứa **toàn bộ** giá trị đơn (RB-42). Tối đa 1 `SENT`/đơn (RB-46). Không sửa bản đã gửi — tạo revision mới (C-02) |
| `POST /orders/{id}/pause` · `/resume` · `/complete` | `complete` cần báo giá `APPROVED` (BR02); ghi `payment` = tổng bản APPROVED mới nhất và **CONFIRMED bởi PARTNER** (thợ thu tiền mặt tại chỗ — RB-59) |

Vòng điều phối (`dispatch_policies`, BR08): 2 km/60 s → 4 km/75 s → 7 km/90 s (vòng cuối). Hết vòng cuối không ai nhận → `NO_PARTNER_FOUND` (RB-34). Đơn `PENDING_CONFIRMATION` quá 10 phút → `EXPIRED`.

## Trạng thái đơn (ERD §7.1 — 14 giá trị)
`PENDING_CONFIRMATION → REQUESTED → ASSIGNED → ARRIVED → CHECKING → WAITING_FOR_APPROVAL → APPROVED → IN_PROGRESS → COMPLETED`
+ `ADDITIONAL_QUOTE`, `PAUSED`, `CANCELLED`, `NO_PARTNER_FOUND`, `EXPIRED`. Chuyển trạng thái sai → 409 `INVALID_STATUS_TRANSITION`.

## Dữ liệu test (dev)
Chạy với `DEV_SEED=true` (run-dev.ps1 bật sẵn): khách `0901000001`, thợ đã duyệt & online `0902000001`, `0902000002`, chủ tiệm `0903000001` (+ nhân viên `0903000002`), kèm 3 đơn mẫu (hoàn tất / hủy sau khi thợ tới / không tìm được thợ). Mã OTP lấy từ `devCode` khi `OTP_DEV_ECHO=true`.

## Lỗi
`{timestamp, status, code, message, path, fieldErrors}`. Mã hay gặp: `INVALID_OTP`, `OTP_RATE_LIMITED`, `INVALID_REFRESH_TOKEN`,
`ACCOUNT_LOCKED`, `FORBIDDEN`, `ORDER_NOT_FOUND` (không lộ đơn người khác), `INVALID_STATUS_TRANSITION`,
`ORDER_ALREADY_TAKEN`, `OFFER_CLOSED`, `QUOTE_ALREADY_SENT`, `QUOTE_NOT_APPROVED`, `PARTNER_NOT_VERIFIED`.
