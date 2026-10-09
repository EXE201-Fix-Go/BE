// Kiểm tra nhanh sau khi deploy (chỉ đọc, không tạo dữ liệu):
//   node scripts/smoke.mjs https://<backend>/api/v1 https://<origin-web-hop-le>
// Thoát mã 1 nếu có mục nào hỏng.
const [base, origin] = process.argv.slice(2);
if (!base) {
  console.error('Cách dùng: node scripts/smoke.mjs <API_BASE_URL> [ORIGIN_WEB]');
  process.exit(2);
}
const api = base.replace(/\/$/, '');
let failed = 0;
const check = (label, ok, detail = '') => {
  if (!ok) failed++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
};
const get = (path, headers = {}) => fetch(api + path, { headers, redirect: 'manual' });

const ping = await get('/ping');
check('GET /ping trả pong', ping.status === 200 && (await ping.text()).trim() === 'pong', `(${ping.status})`);

const services = await get('/services');
const list = services.ok ? await services.json() : [];
check('GET /services có dịch vụ', services.status === 200 && list.length > 0, `(${list.length} dịch vụ)`);
check('Kéo xe (towing) đang tắt', !list.some((s) => s.id === 'towing'));
check('GET /services có X-Request-Id', !!services.headers.get('x-request-id'));

const pricing = await (await get('/pricing')).json().catch(() => ({}));
check('GET /pricing có phí gọi thợ từ DB', Number(pricing.callOutFee) > 0, JSON.stringify(pricing));

for (const path of ['/orders', '/partner/me', '/admin/overview', '/users/me']) {
  const r = await get(path);
  check(`${path} không đăng nhập bị chặn`, r.status === 401, `(${r.status})`);
}
const uploads = await fetch(api + '/uploads/kyc', { method: 'POST' });
check('POST /uploads/kyc không đăng nhập bị chặn', uploads.status === 401, `(${uploads.status})`);
const adminDocs = await get('/admin/partners/00000000-0000-0000-0000-000000000000/documents');
check('Giấy tờ KYC không đọc được khi chưa đăng nhập', adminDocs.status === 401, `(${adminDocs.status})`);

if (origin) {
  const pre = await fetch(api + '/orders', { method: 'OPTIONS', headers: { Origin: origin, 'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'authorization' } });
  check(`CORS cho phép ${origin}`, pre.status === 200 && pre.headers.get('access-control-allow-origin') === origin, `(${pre.status})`);
  const evil = await fetch(api + '/orders', { method: 'OPTIONS', headers: { Origin: 'https://evil.example', 'Access-Control-Request-Method': 'GET' } });
  check('CORS từ chối origin lạ', evil.headers.get('access-control-allow-origin') == null, `(${evil.status})`);
}

// Không gọi POST /auth/otp ở đây: nó gửi SMS thật. Việc không lộ mã OTP đã được đảm bảo lúc khởi động (ProductionSafetyCheck).

console.log(failed === 0 ? '\nTất cả đạt.' : `\n${failed} mục hỏng.`);
process.exit(failed === 0 ? 0 : 1);
