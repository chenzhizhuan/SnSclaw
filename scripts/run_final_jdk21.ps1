$ErrorActionPreference = 'Continue'

$ROOT    = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI      = Join-Path $ROOT 'mateclaw-ui'
$SRV     = Join-Path $ROOT 'mateclaw-server'
$DSK     = Join-Path $ROOT 'mateclaw-desktop'
$LOGS    = Join-Path $ROOT '.build-logs'
$SCRIPTS = Join-Path $ROOT 'scripts'
$STATIC  = Join-Path $SRV  'src\main\resources\static'
$RELEASE = Join-Path $DSK  'release_local_final'
$JDK21   = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$MASTER  = Join-Path $LOGS ('PIPELINE_FINAL_' + [int][double](Get-Date -UFormat %s) + '.log')

New-Item -ItemType Directory -Force -Path $LOGS,$SCRIPTS | Out-Null
Set-Location $ROOT

function Write-Tee([string]$msg) { Write-Host $msg; Add-Content -Path $MASTER -Value $msg -Encoding UTF8 -ErrorAction SilentlyContinue }
function Write-Step([string]$msg) {
  $hr = '=' * 78
  Write-Tee ''
  Write-Tee $hr
  Write-Tee "  $msg  @ $(Get-Date -Format 'HH:mm:ss')"
  Write-Tee $hr
}
function Append-Log([string]$file,[int]$tail=40) {
  if (Test-Path $file) {
    $lines = Get-Content $file -Tail $tail -ErrorAction SilentlyContinue
    foreach ($l in $lines) { Write-Tee $l }
  }
}
function Run-Exe([string]$exe,[string[]]$ArgList,[string]$wd,[string]$out,[string]$err) {
  Write-Tee "    Running: $exe $($ArgList -join ' ')"
  Write-Tee "    cwd    : $wd"
  $p = Start-Process -FilePath $exe -ArgumentList $ArgList -WorkingDirectory $wd `
       -PassThru -Wait -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
  Write-Tee "    exit=$($p.ExitCode)"
  return $p
}

# ===== CRITICAL: FORCE JDK 21 FROM PROJECT CACHE =====
Write-Step 'PRE: FORCE JDK 21 (project cache) override system JAVA_HOME'
Write-Tee "    JDK21 PATH = $JDK21"
if (-not (Test-Path (Join-Path $JDK21 'bin\java.exe'))) {
  Write-Tee '!! FAIL JDK21 not cached.  Expect: .cache/jdk21/jdk-21.0.11+10/bin/java.exe'
  exit 99
}
$env:JAVA_HOME = $JDK21
$env:PATH      = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$v = & (Join-Path $JDK21 'bin\java.exe') -version 2>&1 | Out-String
Write-Tee "    JAVA_HOME verified:  $($v -split "`n" | Where-Object { $_ -match 'version' } | Select-Object -First 1)"
$mv = & 'mvn.cmd' '-version' 2>&1 | Out-String
$m = ($mv -split "`n" | Select-Object -First 6) -join "`n"
Write-Tee "    Maven JDK reported:`n$m"
if ($mv -notmatch 'version:\s*21\.') {
  Write-Tee '!! FAIL Maven is still NOT using JDK 21.  ABORT.'
  exit 98
}
Write-Tee '    [OK] Maven will use JDK 21 + project cached JAVA_HOME'

Write-Tee "PIPELINE (final, JDK21-forced) start @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Tee "ROOT    = $ROOT"
Write-Tee "SRV     = $SRV"
Write-Tee "DSK     = $DSK"
Write-Tee "STATIC  = $STATIC"
Write-Tee "RELEASE = $RELEASE"
Write-Tee "LOG     = $MASTER"
Write-Tee $("=" * 78)

# ===== STEP 0: VERIFY VITE OUTPUT =====
Write-Step 'STEP 0/4 Verify Vite output already in classpath:static/'
if (-not (Test-Path (Join-Path $STATIC 'index.html'))) {
  Write-Tee '!! FAIL  static/index.html missing  (need re-run vite build)'
  exit 91
}
$f = Get-ChildItem $STATIC -Recurse -File
Write-Tee "[VERIFIED] static/: $($f.Count) files, total=$([math]::Round(($f|Measure-Object Length -Sum).Sum/1MB,2)) MB"
$env:NODE_OPTIONS            = '--max-old-space-size=8192'
$env:BUILD_MODE              = 'local'
$env:ELECTRON_CACHE          = Join-Path $ROOT '.cache\electron'
$env:ELECTRON_BUILDER_CACHE  = Join-Path $ROOT '.cache\electron-builder'
New-Item -ItemType Directory -Force -Path $env:ELECTRON_CACHE,$env:ELECTRON_BUILDER_CACHE | Out-Null

# ===== STEP 1: MVN (with 3 retries for transient Windows Defender file locks) =====
Write-Step 'STEP 1/4 MVN clean package (up to 3 retries for Windows file locks)'
$mvnAttempts = 3
$mvnOk = $false
for ($att = 1; $att -le $mvnAttempts; $att++) {
  Write-Tee "  [Maven attempt $att/$mvnAttempts]"
  $outL = Join-Path $LOGS ("final-step1-mvn-out-a$att.log")
  $errL = Join-Path $LOGS ("final-step1-mvn-err-a$att.log")
  $p = Run-Exe 'mvn.cmd' @('clean','package','-DskipTests','-Dmaven.test.skip=true') $SRV $outL $errL
  if ($p.ExitCode -eq 0) {
    $mvnOk = $true
    break
  }
  Write-Tee "    Maven attempt $att FAILED (exit=$($p.ExitCode)). Retry in 10s after GC + close handles..."
  [GC]::Collect(); [GC]::WaitForPendingFinalizers()
  Start-Sleep -Seconds 10
}
if (-not $mvnOk) {
  Write-Tee '!! MVN FAIL after 3 attempts'
  Append-Log $outL 80
  exit 11
}
$JAR = Get-ChildItem (Join-Path $SRV 'target') -Filter 'mateclaw-server-*.jar' -File |
       Where-Object { $_.Name -notmatch 'sources|javadoc|original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $JAR) { Write-Tee '!! MVN FAIL JAR missing in target\'; exit 12 }
Write-Tee "[OK] JAR = $($JAR.FullName)  Size=$([math]::Round($JAR.Length/1MB,2)) MB"

# ===== STEP 2: AUDIT JAR + COPY APP.JAR =====
Write-Step 'STEP 2/4 AUDIT JAR + copy to desktop/resources/app.jar'
$outL = Join-Path $LOGS 'final-step2-audit.log'
$auditPs1 = Join-Path $SCRIPTS 'step4_audit_jar.ps1'
$p = Run-Exe 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',$auditPs1,'-JarPath',$JAR.FullName,'-LogPath',$outL) $SRV $outL $outL
Append-Log $outL 20
if ($p.ExitCode -ne 0) { Write-Tee '!! AUDIT FAIL'; exit 21 }
Copy-Item $JAR.FullName (Join-Path $DSK 'resources\app.jar') -Force
Write-Tee '[OK] app.jar copied'

# ===== STEP 3: DESKTOP BUILD + WS EXTERNAL =====
Write-Step 'STEP 3/4 DESKTOP shell build + ws external check'
$outL = Join-Path $LOGS 'final-step3-desktop-out.log'
$errL = Join-Path $LOGS 'final-step3-desktop-err.log'
$p = Run-Exe 'npm.cmd' @('run','build') $DSK $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 60; Write-Tee '!! DESKTOP BUILD FAIL'; exit 31 }
$MAINJS = Join-Path $DSK 'dist-electron\main\index.js'
if (-not (Test-Path $MAINJS)) { Write-Tee '!! DESKTOP BUILD no main/index.js'; exit 32 }
$outL2 = Join-Path $LOGS 'final-step3-ws.log'
$wsPs1 = Join-Path $SCRIPTS 'step5_check_ws_external.ps1'
$q = Run-Exe 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',$wsPs1,'-MainJs',$MAINJS,'-LogPath',$outL2) $DSK $outL2 $outL2
Append-Log $outL2 20
if ($q.ExitCode -ne 0) { Write-Tee '!! WS EXTERNAL FAIL'; exit 33 }
Write-Tee '[OK] Desktop + ws external verified'

# ===== STEP 4: ELECTRON-BUILDER =====
Write-Step 'STEP 4/4 ELECTRON-BUILDER generate final .exe'
Remove-Item $RELEASE -Recurse -Force -ErrorAction SilentlyContinue
$outL = Join-Path $LOGS 'final-step4-builder-out.log'
$errL = Join-Path $LOGS 'final-step4-builder-err.log'
$p = Run-Exe 'npx.cmd' @('--no-install','electron-builder','--win',"--config.directories.output=$RELEASE") $DSK $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 80; Write-Tee '!! BUILDER FAIL'; exit 41 }

# ===== DONE =====
Write-Tee ''
Write-Tee $("=" * 78)
Write-Tee " PIPELINE COMPLETE @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Tee $("=" * 78)
Write-Tee " Installer directory = $RELEASE"
Write-Tee ' Installer files:'
Get-ChildItem $RELEASE -Filter '*.exe' -File -ErrorAction SilentlyContinue |
  Select-Object Name,@{n='SizeMB';e={[math]::Round($_.Length/1MB,2)}},LastWriteTime |
  Format-Table -AutoSize | Out-String | ForEach-Object { Write-Tee $_ }
Write-Tee ''
Write-Tee 'Baked fixes:'
Write-Tee '  [X] JAVA_HOME = project cached JDK21 (fix release 21 not supported)'
Write-Tee '  [X] Maven 3 retries (survive Windows Defender transient locks)'
Write-Tee '  [X] Vite => server static/ DIRECT output'
Write-Tee '  [X] JAR static/index.html audit'
Write-Tee '  [X] ws external + asarUnpack'
Write-Tee '  [X] afterPack trim-playwright-driver for slim JAR'
Write-Tee ''
exit 0
