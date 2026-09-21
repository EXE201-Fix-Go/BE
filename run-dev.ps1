# Chạy backend ở máy dev. Lần đầu: tạo .local\config.ps1 để bạn điền, rồi dừng. Lần sau: chạy thẳng.
# Mọi thứ trong .local\ đã được git-ignore (không bao giờ bị commit).
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$local = Join-Path $root ".local"
$config = Join-Path $local "config.ps1"
$secretFile = Join-Path $local "jwt.secret"
New-Item -ItemType Directory -Force $local | Out-Null

if (-not (Test-Path $config)) {
@'
# ===== ĐIỀN 2 DÒNG NÀY RỒI CHẠY LẠI run-dev.ps1 =====
$DB_PASSWORD  = "DAN_MAT_KHAU_DATABASE_SUPABASE_VAO_DAY"
$ADMIN_PHONE  = "0912345678"     # số điện thoại của BẠN -> thành tài khoản ADMIN (đăng nhập bằng OTP)

# Không cần sửa bên dưới
$DB_URL       = "jdbc:postgresql://aws-0-ap-southeast-2.pooler.supabase.com:5432/postgres?sslmode=require"
$DB_USERNAME  = "postgres.jziazdjwazigarpmupcz"
'@ | Set-Content -Encoding utf8 $config
    Write-Host ""
    Write-Host "Da tao file:  $config" -ForegroundColor Yellow
    Write-Host "Mo file do, dien DB_PASSWORD va ADMIN_PHONE, luu lai, roi chay lai:  .\run-dev.ps1" -ForegroundColor Yellow
    Write-Host ""
    exit 0
}

. $config
if ($DB_PASSWORD -like "DAN_MAT_KHAU*") { Write-Host "Ban chua dien DB_PASSWORD trong $config" -ForegroundColor Red; exit 1 }

# JWT secret: tu sinh 1 lan, luu lai de token khong bi vo hieu moi lan chay
if (-not (Test-Path $secretFile)) {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    [Convert]::ToBase64String($bytes) | Set-Content -Encoding ascii $secretFile
    Write-Host "Da sinh JWT secret moi -> $secretFile"
}

$env:DB_URL = $DB_URL
$env:DB_USERNAME = $DB_USERNAME
$env:DB_PASSWORD = $DB_PASSWORD
$env:JWT_SECRET = (Get-Content $secretFile -Raw).Trim()
$env:OTP_DEV_ECHO = "true"            # dev: API tra ma OTP trong response (chua co SMS)
$env:BOOTSTRAP_ADMIN_PHONE = $ADMIN_PHONE

Write-Host "Dang chay backend... (Ctrl+C de dung). Sau khi thay 'Started FixGoApplication', mo docs\order-flow.http de test." -ForegroundColor Green
Set-Location $root
& .\mvnw.cmd spring-boot:run
