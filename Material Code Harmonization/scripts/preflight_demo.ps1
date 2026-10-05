param(
    [string]$FrontendUrl = "https://numm-frontend.onrender.com",
    [string]$BackendUrl = "https://numm-spring-api.onrender.com",
    [string]$MatchingUrl = "https://numm-matching.onrender.com",
    [string]$Email = "admin@numm.gov.in",
    [string]$Password = "admin123"
)

$ErrorActionPreference = "Stop"

function Invoke-Check {
    param([string]$Name, [scriptblock]$Action)

    $timer = [Diagnostics.Stopwatch]::StartNew()
    try {
        $value = & $Action
        $timer.Stop()
        Write-Host ("[PASS] {0} ({1} ms)" -f $Name, $timer.ElapsedMilliseconds)
        return $value
    }
    catch {
        $timer.Stop()
        Write-Host ("[FAIL] {0} ({1} ms): {2}" -f $Name, $timer.ElapsedMilliseconds, $_.Exception.Message)
        throw
    }
}

Write-Host "Waking and checking NUMM services..."

$frontend = Invoke-Check "Frontend" {
    Invoke-WebRequest -Uri $FrontendUrl -UseBasicParsing -TimeoutSec 180
}
$matching = Invoke-Check "Matching health" {
    Invoke-RestMethod -Uri "$MatchingUrl/health" -TimeoutSec 180
}
$backend = Invoke-Check "Backend health" {
    Invoke-RestMethod -Uri "$BackendUrl/actuator/health" -TimeoutSec 180
}
$login = Invoke-Check "Admin login" {
    Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/auth/login" `
        -ContentType "application/json" `
        -Body (@{ email = $Email; password = $Password } | ConvertTo-Json) `
        -TimeoutSec 180
}

$headers = @{ Authorization = "Bearer $($login.accessToken)" }
Invoke-Check "Dashboard" {
    Invoke-RestMethod -Uri "$BackendUrl/api/dashboard/stats" -Headers $headers -TimeoutSec 60
} | Out-Null
Invoke-Check "Catalog" {
    Invoke-RestMethod -Uri "$BackendUrl/api/codes/search?q=&status=ACTIVE&page=0&size=5" -Headers $headers -TimeoutSec 60
} | Out-Null
Invoke-Check "AI comparison" {
    $payload = @{
        material_a = @{
            description = "GATE VALVE CS CLASS150 2INCH RF API600"
            category = "VALVE"
            specification = "API 600"
        }
        material_b = @{
            description = "GATE VALVE 2 INCH 150# RF FLANGED CS"
            category = "VALVE"
            specification = "ASME B16.34"
        }
    } | ConvertTo-Json -Depth 5

    Invoke-RestMethod -Method Post -Uri "$BackendUrl/api/harmonization/compare" `
        -Headers $headers -ContentType "application/json" -Body $payload -TimeoutSec 120
} | Out-Null

Write-Host "NUMM demo preflight passed. Keep the services warm and open $FrontendUrl"
