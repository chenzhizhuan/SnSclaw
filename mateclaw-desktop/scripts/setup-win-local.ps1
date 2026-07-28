param(
  [ValidateSet("x64", "arm64")]
  [string]$Arch = "x64"
)

$ErrorActionPreference = "Stop"

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$DesktopDir = (Resolve-Path (Join-Path $RepoRoot "mateclaw-desktop")).Path
$ServerDir = (Resolve-Path (Join-Path $RepoRoot "mateclaw-server")).Path

$ToolsDir = Join-Path $RepoRoot ".tools"
$JdkDir = Join-Path $ToolsDir "temurin-jdk-21"
$TempDir = Join-Path $ToolsDir ".tmp"

function Ensure-Dir([string]$Path) {
  if (-not (Test-Path $Path)) {
    New-Item -ItemType Directory -Force -Path $Path | Out-Null
  }
}

function Expand-ZipFlatten([string]$ZipPath, [string]$DestDir) {
  Ensure-Dir $DestDir
  $extractDir = Join-Path $TempDir ([Guid]::NewGuid().ToString("N"))
  Ensure-Dir $extractDir

  Expand-Archive -Force -Path $ZipPath -DestinationPath $extractDir
  $topDir = Get-ChildItem -Path $extractDir -Directory | Select-Object -First 1
  if (-not $topDir) {
    throw "Zip has no top-level directory: $ZipPath"
  }

  Get-ChildItem -Path $DestDir -Force | Remove-Item -Recurse -Force
  Copy-Item -Path (Join-Path $topDir.FullName "*") -Destination $DestDir -Recurse -Force

  Remove-Item -Recurse -Force $extractDir
}

function Ensure-TemurinJdk21() {
  Ensure-Dir $ToolsDir
  Ensure-Dir $TempDir
  Ensure-Dir $JdkDir

  $javaExe = Join-Path $JdkDir "bin\java.exe"
  if (Test-Path $javaExe) {
    return
  }

  $zipPath = Join-Path $TempDir "temurin-jdk-21-win-x64.zip"
  $url = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk"
  Write-Host "Downloading Temurin JDK 21: $url"
  & curl.exe -L --fail --retry 3 --retry-delay 2 -o $zipPath $url
  Expand-ZipFlatten -ZipPath $zipPath -DestDir $JdkDir
}

function Ensure-TemurinJre21([string]$Arch = "x64") {
  Ensure-Dir $TempDir

  $jrePlatform = if ($Arch -eq "arm64") { "aarch64" } else { "x64" }
  $destDir = Join-Path $DesktopDir ("resources\jre\win32-" + $Arch)
  Ensure-Dir $destDir

  $javaExe = Join-Path $destDir "bin\java.exe"
  if (Test-Path $javaExe) {
    return
  }

  $zipPath = Join-Path $TempDir ("temurin-jre-21-win-" + $Arch + ".zip")
  $url = "https://api.adoptium.net/v3/binary/latest/21/ga/windows/$jrePlatform/jre/hotspot/normal/eclipse?project=jdk"
  Write-Host "Downloading Temurin JRE 21 ($Arch): $url"
  & curl.exe -L --fail --retry 3 --retry-delay 2 -o $zipPath $url
  Expand-ZipFlatten -ZipPath $zipPath -DestDir $destDir

  if (-not (Test-Path $javaExe)) {
    throw "JRE extracted but java.exe not found: $javaExe"
  }
}

function Use-Jdk21ForChildProcesses() {
  Ensure-TemurinJdk21
  $env:JAVA_HOME = $JdkDir
  $env:Path = ($JdkDir + "\bin;") + $env:Path
}

function Build-ServerJar() {
  Use-Jdk21ForChildProcesses

  Push-Location $ServerDir
  try {
    & mvn clean package -DskipTests -Dmaven.test.skip=true
  } finally {
    Pop-Location
  }
}

function Copy-ServerJarToDesktopResources() {
  $jar = Get-ChildItem -Path (Join-Path $ServerDir "target") -Filter "mateclaw-server-*.jar" | Select-Object -First 1
  if (-not $jar) {
    throw "Could not find built jar in: $ServerDir\target"
  }

  $resourcesDir = Join-Path $DesktopDir "resources"
  Ensure-Dir $resourcesDir
  Copy-Item -Path $jar.FullName -Destination (Join-Path $resourcesDir "app.jar") -Force
}

function Ensure-DesktopDependencies() {
  Push-Location $DesktopDir
  try {
    if (-not (Test-Path "node_modules")) {
      & npm install
    }
  } finally {
    Pop-Location
  }
}

function Setup-WindowsLocalResources([string]$Arch = "x64") {
  Ensure-DesktopDependencies
  Build-ServerJar
  Copy-ServerJarToDesktopResources
  Ensure-TemurinJre21 -Arch $Arch
}

Setup-WindowsLocalResources -Arch $Arch
