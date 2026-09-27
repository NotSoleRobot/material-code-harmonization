# ==============================================================================
# NUMM (National Unified Material Master) — SIH 2026 PowerShell Demo Seed Script
# ==============================================================================
$BackendUrl = if ($env:BACKEND_URL) { $env:BACKEND_URL } else { "http://localhost:8080" }

Write-Host "--------------------------------------------------" -ForegroundColor Cyan
Write-Host "Seeding NUMM Demonstration Data at $BackendUrl" -ForegroundColor Cyan
Write-Host "--------------------------------------------------" -ForegroundColor Cyan

# 1. Login as Admin
Write-Host "1. Authenticating as National Governance Administrator..." -ForegroundColor Yellow
$LoginBody = @{
    email    = "admin@numm.gov.in"
    password = "admin123"
} | ConvertTo-Json

try {
    $LoginResp = Invoke-RestMethod -Uri "$BackendUrl/api/auth/login" -Method Post -Body $LoginBody -ContentType "application/json"
    $Token = $LoginResp.accessToken
    Write-Host "✅ Authenticated. Bearer token acquired." -ForegroundColor Green
} catch {
    Write-Host "❌ Login failed: $_" -ForegroundColor Red
    exit 1
}

$Headers = @{
    "Authorization" = "Bearer $Token"
}

# 2. Upload ONGC Batch
Write-Host "2. Ingesting ONGC Plant Materials Batch..." -ForegroundColor Yellow
$OngcCsv = @"
cpse_material_code,description,specification,unit_of_measure,category,cpse_name
ONGC-PP-2001,CS SEAMLESS PIPE 50MM SCH40 ASTM A106 GRB,ASTM A106,MTR,PIPE,ONGC
ONGC-VL-2002,GLOBE VLV 3INCH 300LB WCB RTJ,API 600,NOS,VALVE,ONGC
ONGC-GK-2003,SPIRAL WOUND GASKET 50MM 300#,ASME B16.20,NOS,GASKET,ONGC
ONGC-PP-2004,SS 316L PIPE 1IN SCH10S ASTM A312,ASTM A312,MTR,PIPE,ONGC
ONGC-BR-2005,BALL BEARING 6308 2RS C3,ISO 15,NOS,BEARING,ONGC
"@

Invoke-RestMethod -Uri "$BackendUrl/api/materials/bulk-csv?autoHarmonize=true" -Method Post -Headers $Headers -ContentType "text/plain" -Body $OngcCsv | Out-Null
Write-Host "✅ ONGC batch ingested." -ForegroundColor Green

# 3. Upload IOCL Batch
Write-Host "3. Ingesting IOCL Plant Materials Batch (Matching Candidates)..." -ForegroundColor Yellow
$IoclCsv = @"
cpse_material_code,description,specification,unit_of_measure,category,cpse_name
IOCL-PP-101,CARBON STEEL PIPE DN50 SCHEDULE 40 GR.B IS1239,IS 1239,MTR,PIPE,IOCL
IOCL-VL-102,GATE VALVE CS CLASS150 2INCH RF API600,API 600,NOS,VALVE,IOCL
IOCL-BR-103,DEEP GROOVE BALL BEARING 6205-2RS1,SKF 6205,NOS,BEARING,IOCL
"@

Invoke-RestMethod -Uri "$BackendUrl/api/materials/bulk-csv?autoHarmonize=true" -Method Post -Headers $Headers -ContentType "text/plain" -Body $IoclCsv | Out-Null
Write-Host "✅ IOCL batch ingested." -ForegroundColor Green

# 4. Trigger Batch Harmonization
Write-Host "4. Running National Batch Harmonization Pipeline..." -ForegroundColor Yellow
Invoke-RestMethod -Uri "$BackendUrl/api/harmonization/harmonize-all" -Method Post -Headers $Headers | Out-Null
Write-Host "✅ Batch harmonization completed." -ForegroundColor Green

# 5. Verify Cryptographic Audit Chain
Write-Host "5. Verifying Cryptographic Audit Chain Integrity..." -ForegroundColor Yellow
$VerifyResp = Invoke-RestMethod -Uri "$BackendUrl/api/mappings/audit/verify" -Method Get -Headers $Headers
Write-Host ("Audit Verification Result: " + ($VerifyResp | ConvertTo-Json -Compress)) -ForegroundColor Green

Write-Host "--------------------------------------------------" -ForegroundColor Cyan
Write-Host "🎉 Demonstration Environment Ready!" -ForegroundColor Cyan
Write-Host "--------------------------------------------------" -ForegroundColor Cyan
