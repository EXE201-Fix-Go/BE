# Kết nối Fix&Go Backend với Supabase

Backend dùng PostgreSQL JDBC, JPA, Flyway và **PostGIS**. Không cần Supabase SDK, không dùng API key/secret key của Supabase, không dùng Supabase Auth: xác thực là OTP + JWT tự quản (QD-12). Bật extension **PostGIS** trong Dashboard → Database → Extensions trước khi chạy (migration V1 cũng tự `CREATE EXTENSION IF NOT EXISTS`).

## 1. Chuẩn bị database

Hướng dẫn này dùng schema `fixgo` và profile mới `supabase`.

- Nếu đã chạy `FixGo_Supabase_Init.sql` và có 21 bảng: đọc kỹ bước baseline ở mục 5.
- Nếu database còn trống: profile này cho Flyway tạo schema `fixgo` và **3 bảng tài khoản** bằng migration V1. Nó không tự tạo 18 bảng nghiệp vụ chưa có migration.
- Chọn một cách khởi tạo. Không chạy script tạo 21 bảng sau khi Flyway đã tạo 3 bảng; cần migration bổ sung nếu muốn mở rộng từ đó.

Kiểm tra trên Supabase SQL Editor, không làm thay đổi dữ liệu:

```sql
SELECT tablename
FROM pg_catalog.pg_tables
WHERE schemaname = 'fixgo'
ORDER BY tablename;
```

## 2. Lấy host và tài khoản kết nối

Trong project Supabase, bấm **Connect**, chọn **Session pooler**. Dùng đúng host, port, database và username được hiển thị, không tự đoán hostname theo region. Session mode thường dùng port **5432**, username dạng `postgres.PROJECT_REF`.

Đây là lựa chọn phù hợp cho backend chạy trên máy phát triển dùng IPv4. Nếu máy có IPv6, có thể dùng Direct connection với đúng host và username của phương thức đó. Không trộn host Direct với username Pooler, và không dùng Transaction pooler port 6543 cho cấu hình này. Xem [tài liệu kết nối Supabase](https://supabase.com/docs/guides/database/connecting-to-postgres).

Chuỗi JDBC dùng trong Java có dạng:

```text
jdbc:postgresql://HOST_TU_CONNECT:5432/postgres?sslmode=require
```

`HOST_TU_CONNECT` là chỗ cần thay bằng giá trị thật. Giữ database/port theo Connect nếu project hiển thị giá trị khác. URL không chứa username hoặc password; hai giá trị này truyền riêng nên không cần URL-encode mật khẩu. Không dùng URL REST `https://...supabase.co` làm JDBC URL.

`sslmode=require` mã hóa đường truyền nhưng không xác minh đầy đủ danh tính server. Khi triển khai production, dùng `sslmode=verify-full` với CA/root certificate và hostname phù hợp theo hướng dẫn SSL của Supabase; không tắt SSL để xử lý lỗi chứng chỉ.

## 3. Tạo khóa JWT riêng cho Fix&Go

`JWT_SECRET` là khóa ký token của backend, **không phải** database password, anon key, service-role key hay JWT secret của Supabase.

Chạy đoạn sau trong PowerShell trên máy của bạn để tạo khóa ngẫu nhiên. Nó đưa khóa vào clipboard, không in ra console:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
[Convert]::ToBase64String($bytes) | Set-Clipboard
```

Dán vào biến `JWT_SECRET` của IntelliJ ở bước tiếp theo. Giữ nguyên khóa giữa các lần chạy và các máy chạy cùng backend; thay khóa sẽ làm access token cũ không còn hợp lệ. Lưu bằng cấu hình bí mật nội bộ, không gửi vào chat/Git; tránh đồng bộ clipboard chứa secret sang máy khác và xóa clipboard sau khi đã lưu an toàn.

## 4. Cấu hình IntelliJ IDEA

Mở project `D:\IdeaProjects\BE_EXE201` dưới dạng Maven, dùng JDK 21.

Vào **Run > Edit Configurations**, chọn cấu hình chạy `com.fixgo.FixGoApplication` (Application hoặc Spring Boot). Nếu trường chưa hiện, dùng **Modify options** để hiện Program arguments/Environment variables. Cấu hình môi trường được hỗ trợ trong [IntelliJ run configuration](https://www.jetbrains.com/help/idea/run-debug-configuration-spring-boot.html).

**Program arguments**:

```text
--spring.profiles.active=supabase
```

Xóa `--spring.profiles.active=local` cũ, và không bật đồng thời local/supabase. Nếu có trường Active profiles riêng, chỉ chọn `supabase`, không để `local` ở đó. Kiểm tra không còn environment/VM option khác ghi đè datasource/schema.

**Environment variables**: mở bảng chỉnh biến và thêm từng dòng riêng, tránh ghép thủ công bằng dấu chấm phẩy khi password có ký tự đặc biệt.

| Tên | Giá trị |
|---|---|
| `DB_URL` | `jdbc:postgresql://HOST_TU_CONNECT:5432/postgres?sslmode=require` |
| `DB_USERNAME` | Username chính xác trong Session pooler, ví dụ mẫu `postgres.PROJECT_REF` |
| `DB_PASSWORD` | Mật khẩu database đã đặt cho project, nhập giá trị thật không thêm dấu ngoặc/nháy |
| `JWT_SECRET` | Chuỗi Base64 ngẫu nhiên ở mục 3 |
| `PORT` | Tùy chọn, mặc định 8080; dùng 8081 nếu 8080 đang bị chiếm |
| `OTP_DEV_ECHO` | `true` ở máy dev để API trả mã OTP trong response (chưa có SMS provider). Không bật ở production |
| `BOOTSTRAP_ADMIN_PHONE` | SĐT tạo tài khoản ADMIN đầu tiên (đăng nhập bằng OTP, không mật khẩu) |

Không bật **Store as project file** cho run configuration chứa secrets, không chụp ảnh màn hình các biến bí mật. `.idea/` được ignore trong project này. File `.env` không được tự nạp bởi ứng dụng.

Không cần thêm thư viện H2/PostgreSQL bằng tay. Profile `supabase` chọn PostgreSQL và schema `fixgo` cho Hikari, Hibernate và Flyway; mặc định pool tối đa 5 kết nối.

## 5. Chỉ khi đã tạo 21 bảng bằng SQL: baseline một lần

Nếu schema đã có bảng nhưng chưa có `flyway_schema_history`, Flyway sẽ từ chối tự chạy migration trên schema không rỗng. Đây là cơ chế bảo vệ, không phải thiếu thư viện.

Chỉ thực hiện khi chắc chắn:

1. Đúng project phát triển Fix&Go và đúng schema `fixgo`.
2. 21 bảng được tạo thành công từ `FixGo_Supabase_Init.sql`, 3 bảng tài khoản chưa bị chỉnh sai so với migration V1.
3. Chưa có lịch sử migration cần giữ/sửa. Không dùng baseline để bỏ qua lỗi checksum hoặc migration thất bại.

Thêm tạm vào **Program arguments** cho lần chạy đầu:

```text
--spring.profiles.active=supabase --spring.flyway.baseline-on-migrate=true
```

Version baseline đã được đặt là **1** trong profile, nên Flyway không tạo lại 3 bảng V1. Sau lần khởi động thành công và xác nhận có bản ghi BASELINE version 1, **bỏ `--spring.flyway.baseline-on-migrate=true`** trước các lần chạy sau. Không đổi mặc định false trong file cấu hình. Xem [giải thích baseline-on-migrate của Flyway](https://documentation.red-gate.com/fd/flyway-baseline-on-migrate-setting-277578974.html).

Nếu schema trống, bỏ qua toàn bộ bước baseline: Flyway tự chạy V1. Nếu đã có lịch sử Flyway hợp lệ, cũng không cần bật baseline.

Lưu ý: 18 bảng được tạo bằng SQL vẫn chưa có migration tương ứng trong repo. Chưa tạo V2 để CREATE lại chúng. Trước khi nhân bản sang môi trường mới, cần thống nhất migration từ snapshot này; các thay đổi sau phải có version mới, không sửa V1 đã áp dụng.

## 6. Chạy và xác minh

Bấm Run trong IntelliJ. Log mong đợi:

- Active profile: `supabase`.
- Hikari kết nối được PostgreSQL.
- Flyway baseline/migration thành công, Hibernate validate thành công.
- `Started FixGoApplication` và cổng HTTP đã chọn.

Nếu dùng terminal, các biến phải được đặt trong chính terminal đó; biến trong Run Configuration không tự truyền sang cửa sổ terminal. Khi đã đặt đủ biến:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=supabase"
```

Lệnh trên không tự bật baseline. Cách IntelliJ ở mục 5 là cách hướng dẫn cho lần tích hợp schema thủ công.

Kiểm tra database/schema và lịch sử migration trên SQL Editor sau khi backend khởi động:

```sql
SELECT current_database();
SELECT installed_rank, version, description, type, success
FROM fixgo.flyway_schema_history
ORDER BY installed_rank;
```

Database Supabase thường có tên `postgres`, còn `fixgo` là schema, không phải tên database. `current_schema()` của SQL Editor có thể là `public`; điều đó không phản ánh schema của kết nối Java.

Kiểm tra API theo `docs/api.md`: `POST /api/v1/auth/otp` → `POST /api/v1/auth/otp/verify` → `GET /api/v1/users/me`. Tài khoản mới phải xuất hiện trong `fixgo.app_users` (không có cột mật khẩu). Có thể kiểm tra số lượng mà không đọc dữ liệu nhạy cảm:

```sql
SELECT count(*) AS account_count FROM fixgo.app_users;
```

Sau khi baseline, thường sẽ thấy 22 bảng gồm 21 bảng ứng dụng và `flyway_schema_history`. Không mở `http://localhost:8080/` để đánh giá app chạy được hay chưa: backend không có trang giao diện ở đường dẫn gốc.

## 7. Lỗi thường gặp

| Lỗi | Kiểm tra |
|---|---|
| `no password was provided` | DB_PASSWORD chưa có trong đúng Run Configuration |
| `password authentication failed` | Mật khẩu database/username sai; không dùng mật khẩu đăng nhập Supabase Dashboard hoặc API key |
| `Tenant or user not found` | Host pooler/username/project ref không khớp |
| `Network is unreachable` / timeout | Dùng Session pooler nếu mạng không có IPv6; kiểm tra project đang hoạt động và các giới hạn mạng |
| `non-empty schema ... no schema history` | Chỉ baseline theo mục 5 sau khi xác nhận cấu trúc thủ công |
| `missing table app_users` | Schema Flyway/Hibernate hoặc cấu trúc tạo thủ công không đúng; không đổi ddl-auto thành update để lấp lỗi |
| `permission denied` / RLS | Vai trò database chưa có quyền phù hợp; không bật policy public để chữa lỗi |
| `Port 8080 already in use` | Dừng đúng backend cũ trong IDE hoặc dùng PORT=8081; không kết luận là lỗi DB |
| Thiếu/không hợp lệ `JWT_SECRET` | Dùng Base64 của ít nhất 32 byte ngẫu nhiên, không dùng chuỗi mật khẩu tùy ý |

Profile/kiểm thử cục bộ không xác nhận được kết nối Supabase thật khi chưa có credentials và chưa chạy tại máy bạn. Không cần chia sẻ secrets để làm theo hướng dẫn này.
