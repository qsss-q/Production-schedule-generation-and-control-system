param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"
$checks = @(
    @{ Path = "/v3/api-docs"; Name = "OpenAPI schema" },
    @{ Path = "/api/equipment"; Name = "Equipment directory" },
    @{ Path = "/api/ovens"; Name = "Oven directory" },
    @{ Path = "/api/operations"; Name = "Schedule operations" },
    @{ Path = "/api/settings"; Name = "Schedule settings" },
    @{ Path = "/api/notifications"; Name = "Notifications" },
    @{ Path = "/api/audit"; Name = "Audit log" },
    @{ Path = "/api/temperature/oven/1"; Name = "Oven temperature" },
    @{ Path = "/api/v1/schedule-runs"; Name = "Schedule runs API v1" },
    @{ Path = "/api/v1/schedule-runs/active"; Name = "Active schedule run" }
)

$failed = 0
foreach ($check in $checks) {
    $url = $BaseUrl.TrimEnd('/') + $check.Path
    try {
        $response = Invoke-WebRequest -Uri $url -Method Get -TimeoutSec 10
        if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300) {
            Write-Host "[PASS] $($check.Name) ($($response.StatusCode))" -ForegroundColor Green
        } else {
            Write-Host "[FAIL] $($check.Name) ($($response.StatusCode))" -ForegroundColor Red
            $failed++
        }
    } catch {
        Write-Host "[FAIL] $($check.Name): $($_.Exception.Message)" -ForegroundColor Red
        $failed++
    }
}

Write-Host ""
Write-Host ("Result: {0} passed, {1} failed" -f ($checks.Count - $failed), $failed)
if ($failed -gt 0) { exit 1 }
exit 0
