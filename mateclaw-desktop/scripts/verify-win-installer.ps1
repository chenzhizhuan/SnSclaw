param(
  [Parameter(Mandatory = $true)]
  [string]$InstallerPath,
  [Parameter(Mandatory = $true)]
  [string]$InstallDir
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $InstallerPath)) {
  throw "Installer not found: $InstallerPath"
}

if (Test-Path $InstallDir) {
  Remove-Item $InstallDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

Write-Host "Installing silently..."
& $InstallerPath /S ("/D=$InstallDir")

Start-Sleep -Seconds 2

$mainExe = Join-Path $InstallDir "SnSclaw.exe"
$resourcesDir = Join-Path $InstallDir "resources"
$appAsar = Join-Path $resourcesDir "app.asar"

Write-Host "Checking installed files..."
if (-not (Test-Path $mainExe)) { throw "Missing: $mainExe" }
if (-not (Test-Path $resourcesDir)) { throw "Missing: $resourcesDir" }
if (-not (Test-Path $appAsar)) { throw "Missing: $appAsar" }

Write-Host "Launching app for smoke test..."
$proc = Start-Process -FilePath $mainExe -PassThru
Start-Sleep -Seconds 5
if (-not $proc.HasExited) {
  Stop-Process -Id $proc.Id -Force
}

Write-Host "OK"
