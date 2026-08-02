$ErrorActionPreference = 'Continue'
Write-Host "=== HELLO TEST ==="
Write-Host "PID=$PID"
Write-Host "CWD=$(Get-Location)"
Write-Host "T1=$(Get-Date)"
Start-Sleep -Seconds 5
Write-Host "T2=$(Get-Date)"
Write-Host "=== BYE ==="
exit 0
