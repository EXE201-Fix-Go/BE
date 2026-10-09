// Khóa các tài khoản test/mẫu trước khi go live (KHÔNG xóa: đơn cũ còn tham chiếu tới họ, và khóa thì hoàn tác được).
//
//   node scripts/lock-test-accounts.mjs <API_BASE_URL> <SĐT_ADMIN> --phones "^\+84(933|901|902)" [--apply]
//
// Mặc định chỉ LIỆT KÊ (dry-run). Thêm --apply mới khóa. Cần BE đang cho phép nhận mã OTP của admin (đăng nhập OTP),
// vì script đăng nhập bằng tài khoản admin: ở production không có OTP_DEV_ECHO nên hãy lấy token admin bằng tay:
//   set ADMIN_TOKEN=<access token>    (Windows: $env:ADMIN_TOKEN = '...')
// và bỏ tham số <SĐT_ADMIN> (đặt "-").
//
// Thợ đang có đơn mở sẽ báo 409 PARTNER_HAS_ACTIVE_JOB: hủy/giao lại đơn đó rồi chạy lại.
const args = process.argv.slice(2);
const apply = args.includes('--apply');
const flag = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
const [base, adminPhone] = args.filter((a) => !a.startsWith('--') && a !== flag('--phones'));
const pattern = flag('--phones');
if (!base || !pattern) {
  console.error('Cách dùng: node scripts/lock-test-accounts.mjs <API_BASE_URL> <SĐT_ADMIN|-> --phones "<regex>" [--apply]');
  process.exit(2);
}
const api = base.replace(/\/$/, '');
const re = new RegExp(pattern);

async function call(method, path, token, body) {
  const res = await fetch(api + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

let token = process.env.ADMIN_TOKEN;
if (!token) {
  if (!adminPhone || adminPhone === '-') { console.error('Thiếu ADMIN_TOKEN hoặc SĐT admin.'); process.exit(2); }
  const otp = await call('POST', '/auth/otp', null, { phone: adminPhone });
  if (!otp.json?.devCode) { console.error('Backend không trả devCode (đúng với production). Đặt ADMIN_TOKEN thay vì đăng nhập bằng script.'); process.exit(2); }
  const v = await call('POST', '/auth/otp/verify', null, { otpId: otp.json.otpId, code: otp.json.devCode, platform: 'WEB' });
  token = v.json.accessToken;
}

const matches = [];
for (let page = 0; ; page++) {
  const r = await call('GET', `/admin/users?page=${page}&size=100`, token);
  if (r.status !== 200) { console.error('Không đọc được danh sách người dùng:', r.status, r.json?.message); process.exit(1); }
  for (const u of r.json.items) if (u.role !== 'ADMIN' && u.status === 'ACTIVE' && re.test(u.phone ?? '')) matches.push(u);
  if (page + 1 >= r.json.totalPages) break;
}

console.log(`${matches.length} tài khoản khớp /${pattern}/ và đang ACTIVE${apply ? ' — SẼ KHÓA' : ' (dry-run, thêm --apply để khóa)'}:`);
let locked = 0, blocked = 0;
for (const u of matches) {
  if (!apply) { console.log(`  ${u.phone}  ${u.appRole.padEnd(8)} ${u.fullName ?? ''}`); continue; }
  const r = await call('PATCH', `/admin/users/${u.id}/status`, token, { status: 'LOCKED' });
  if (r.status === 200) { locked++; console.log(`  khóa  ${u.phone}  ${u.fullName ?? ''}`); }
  else { blocked++; console.log(`  BỎ QUA ${u.phone}: ${r.json?.code ?? r.status}`); }
}
if (apply) console.log(`\nĐã khóa ${locked}, bỏ qua ${blocked}. Mở khóa lại: PATCH /admin/users/{id}/status {"status":"ACTIVE"}.`);
