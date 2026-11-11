$ErrorActionPreference = "Stop"

$RootDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$EnvFile = Join-Path $RootDir ".env"

function New-RandomHex {
    param([int]$ByteLength = 32)
    $bytes = New-Object byte[] $ByteLength
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    return ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
}

if (-not (Test-Path $EnvFile)) {
    Write-Host "Generating .env with cryptographically random secrets..."
    $mysqlPassword = New-RandomHex 24
    $mysqlRootPassword = New-RandomHex 24
    $jwtSecret = New-RandomHex 48
    $qrHmacSecret = New-RandomHex 48

    @"
MYSQL_DATABASE=gatekeeper
MYSQL_USER=gatekeeper
MYSQL_PASSWORD=$mysqlPassword
MYSQL_ROOT_PASSWORD=$mysqlRootPassword

JWT_SECRET=$jwtSecret
QR_HMAC_SECRET=$qrHmacSecret

JWT_ACCESS_EXPIRATION_SECONDS=900
JWT_REFRESH_EXPIRATION_SECONDS=604800
HOLD_DURATION_SECONDS=600
PAYMENT_DEADLINE_SECONDS=86400
CORS_ALLOWED_ORIGINS=http://localhost:3000,http://localhost

NEXT_PUBLIC_API_URL=/api
"@ | Set-Content -Path $EnvFile -Encoding UTF8
    Write-Host "Created $EnvFile"
} else {
    Write-Host "Using existing $EnvFile"
}

Write-Host "Starting GateKeeper stack via Docker Compose..."
docker compose --project-directory $RootDir up --build -d
Write-Host "GateKeeper stack started. Check http://localhost"

