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
Số điện thoại lần đầu → tài khoản `CUSTOMER`.
Giới hạn theo IP dùng `X-Forwarded-For` chỉ khi đặt `TRUSTED_PROXY_HOPS` (số proxy của mình đứng trước app; lấy mục thứ N từ bên phải, không bao giờ lấy mục đầu do client gửi). Mặc định 0 = bỏ qua header.
Profile `prod` từ chối khởi động nếu `OTP_DEV_ECHO=true` hoặc `JWT_SECRET` ngắn hơn 32 ký tự. OTP: 6 số, 5 phút, 5 lần thử, dùng một lần (RB-03), giới hạn theo số & IP (RB-04).

## Người dùng
- `GET /users/me` trả về `fullName`, số điện thoại, `email`, `dateOfBirth` (ISO `yyyy-MM-dd`) và `avatarUrl`.
- `PATCH /users/me` nhận `{"fullName", "email?", "dateOfBirth?":"yyyy-MM-dd", "avatarUrl?"}`; số điện thoại lấy từ identity và không được chỉnh sửa trong endpoint này.

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

## Quyền riêng tư (AUTHZ S1)
SĐT và vị trí chỉ chia sẻ khi đơn còn mở. Khi đơn `COMPLETED`/`CANCELLED`: khách thấy `partner.phone/lat/lng = null` (vẫn có tên thợ); thợ thấy `contactPhone/lat/lng = null`. ADMIN không bị ẩn.

## Báo giá hết hạn (S12)
Nếu thợ đặt `validMinutes`, duyệt/từ chối sau hạn → 409 `QUOTE_EXPIRED`. Thợ gửi lại báo giá mới được (bản hết hạn chuyển `EXPIRED`, đơn giữ nguyên trạng thái chờ duyệt).

## Dịch vụ tắt theo cấu hình
`DISABLED_SERVICE_CODES` (mặc định `towing`) ẩn dịch vụ khỏi `GET /services` và từ chối tạo đơn/đăng ký dịch vụ đó (`UNKNOWN_SERVICE`).

## Thợ rút đơn
`POST /orders/{id}/withdraw` (thợ đã nhận, trạng thái `ASSIGNED`, body `{reason?}`) → 204. Đơn về `REQUESTED` và được phát lại cho thợ khác; thợ đã rút không được mời lại và mất quyền xem đơn. Sau `ARRIVED` trả 409. `POST /orders/{id}/cancel` của thợ khi đơn còn `ASSIGNED` được xử lý như rút đơn (app cũ vẫn chạy).
Admin khóa tài khoản thợ đang có đơn mở → 409 `PARTNER_HAS_ACTIVE_JOB` (hủy hoặc giao lại đơn trước).
`DISPATCH_LOCATION_MAX_AGE` (vd `10m`; mặc định `0s` = không giới hạn): bỏ qua thợ có vị trí cũ hơn ngưỡng khi chọn thợ — chỉ bật khi app thợ gửi vị trí định kỳ qua `PATCH /partner/me/presence`.

## Nhân viên tiệm — lời mời (AUTHZ §6.3)
Chủ tiệm chỉ **mời**, tài khoản người được mời không bị đổi cho tới khi họ tự chấp nhận.

| Endpoint | Ai | Ghi chú |
|---|---|---|
| `POST /partner/shop/staff` `{phone, fullName}` | chủ tiệm đã được duyệt | tạo lời mời `PENDING` (hạn 7 ngày); trả danh sách nhân viên như cũ. 403 `PARTNER_NOT_VERIFIED` nếu tiệm chưa duyệt, 409 `INVITATION_PENDING` / `ALREADY_PARTNER` |
| `GET /partner/shop/invitations` | chủ tiệm | lời mời đã gửi (`PENDING/ACCEPTED/DECLINED/CANCELLED/EXPIRED`) |
| `DELETE /partner/shop/invitations/{id}` | chủ tiệm | hủy lời mời đang chờ → 204 |
| `DELETE /partner/shop/staff/{userId}` | chủ tiệm | gỡ nhân viên, họ thành đối tác cá nhân → 204 (409 nếu đang có đơn) |
| `POST /partner/shop/leave` | nhân viên | tự rời tiệm → 204 |
| `GET /invitations` | người được mời (khách hoặc đối tác cá nhân) | lời mời đang mở gửi tới SĐT của mình |
| `POST /invitations/{id}/accept` | người được mời | khách → `SHOP_STAFF` (vẫn chờ KYC, RB-12); trả hồ sơ đối tác. 409 `CUSTOMER_HAS_ACTIVE_ORDER` nếu khách còn đơn mở |
| `POST /invitations/{id}/decline` | người được mời | → 204 |

Lời mời của người khác trả 404 (không lộ tồn tại). Migration `V7__shop_invitations.sql`.

## Gửi OTP qua SMS (eSMS.vn)
`SMS_PROVIDER=log` (mặc định) chỉ ghi log — dùng khi chạy local cùng `OTP_DEV_ECHO=true`. `SMS_PROVIDER=esms` gửi qua eSMS.vn
(`POST .../SendMultipleMessage_V4_post_json/`, tin chăm sóc khách hàng `SmsType=2`, thành công khi `CodeResult=100`).

| Biến | Ý nghĩa |
|---|---|
| `ESMS_API_KEY`, `ESMS_SECRET_KEY` | khóa tài khoản eSMS (chỉ đặt trong biến môi trường của Render, không commit) |
| `ESMS_BRANDNAME` | brandname đã đăng ký với eSMS (bắt buộc với `SmsType=2`) |
| `ESMS_CONTENT_TEMPLATE` | nội dung tin, có `{code}` và `{minutes}`. **Phải trùng đúng mẫu đã đăng ký với eSMS**, nếu không eSMS trả `146` |
| `ESMS_SANDBOX=true` | eSMS chỉ kiểm tra request, không gửi và không tính tiền — dùng để thử khóa trước khi chạy thật |
| `ESMS_SMS_TYPE`, `ESMS_URL`, `ESMS_TIMEOUT` | mặc định `2`, URL của eSMS, `5s` |

Mẫu tin mặc định (không dấu, 1 tin, cần đăng ký trước với eSMS):
`{code} la ma xac thuc Fix&Go cua ban, co hieu luc {minutes} phut. Tuyet doi khong chia se ma nay voi bat ky ai.`

Gửi lỗi (khóa sai `101`, brandname lạ `104`, mẫu chưa đăng ký `146`, cổng lỗi/timeout) → 502 `OTP_DELIVERY_FAILED`, không trả `devCode`,
không lộ thông tin nhà cung cấp; chi tiết nằm trong log (`sms.esms ...`, chỉ hiện 4 số cuối). Profile `prod` từ chối khởi động nếu
`SMS_PROVIDER` vẫn là `log`. SpeedSMS chưa được hỗ trợ (tài liệu công khai của họ chưa đủ rõ để viết adapter an toàn).

## Trạng thái đơn (ERD §7.1 — 14 giá trị)
`PENDING_CONFIRMATION → REQUESTED → ASSIGNED → ARRIVED → CHECKING → WAITING_FOR_APPROVAL → APPROVED → IN_PROGRESS → COMPLETED`
+ `ADDITIONAL_QUOTE`, `PAUSED`, `CANCELLED`, `NO_PARTNER_FOUND`, `EXPIRED`. Chuyển trạng thái sai → 409 `INVALID_STATUS_TRANSITION`.

## Dữ liệu test (dev)
Không còn dữ liệu mẫu: tài khoản đăng nhập bằng OTP (xem `devCode` khi `OTP_DEV_ECHO=true`); tài khoản admin đầu tiên tạo qua `BOOTSTRAP_ADMIN_PHONE`.

## Lỗi
`{timestamp, status, code, message, path, fieldErrors}`. Mã hay gặp: `INVALID_OTP`, `OTP_RATE_LIMITED`, `INVALID_REFRESH_TOKEN`,
`ACCOUNT_LOCKED`, `FORBIDDEN`, `ORDER_NOT_FOUND` (không lộ đơn người khác), `INVALID_STATUS_TRANSITION`,
`ORDER_ALREADY_TAKEN`, `OFFER_CLOSED`, `QUOTE_ALREADY_SENT`, `QUOTE_EXPIRED`, `QUOTE_NOT_APPROVED`, `PARTNER_NOT_VERIFIED`.
