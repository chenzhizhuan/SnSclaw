# =============================================================================
#  MANUAL TAKEOVER PIPELINE — resume from Maven PACKAGE phase (SKIP JAVAC CLEAN)
#  Reason: previous run spent 26min compiling 1998 *.class files, then mvn
#          spring-boot:repackage I/O locked. We SAVED target/classes from
#          being wiped by the wrapper's "retry → clean" loop.
#  Flow  : JDK21 Force → mvn package (no clean) → JAR audit → copy app.jar
#          → Desktop build → ws check → electron-builder
# =============================================================================
$ErrorActionPreference = 'Stop'

# ---- Paths --------------------------------------------------------------- {{{1
$ROOT    = Split-Path -Parent $PSScriptRoot
$SRV     = Join-Path $ROOT 'mateclaw-server'
$DSK     = Join-Path $ROOT 'mateclaw-desktop'
$RELEASE = Join-Path $DSK  'release_takeover'
$LOGDIR  = Join-Path $ROOT '.build-logs'
$TS      = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MASTER  = Join-Path $LOGDIR "PIPELINE_TAKEOVER_$TS.log"
New-Item $LOGDIR -ItemType Directory -Force | Out-Null
'' | Set-Content $MASTER

# ---- Helpers ------------------------------------------------------------- {{{1
function Write-Tee { param([string]$s) $s | Tee-Object -FilePath $MASTER -Append }
function Run-Exe   {  # reliable external-call wrapper (sandbox-safe)
  param(
    [Parameter(Mandatory)][string]$Exe,
    [string[]]$ArgList=@(),
    [string]$Cwd=$ROOT,
    [string]$LogOut,
    [string]$LogErr
  )
  $std = if ($LogOut) { $LogOut } else { Join-Path $LOGDIR "tmp_$TS_out.log" }
  $ste = if ($LogErr) { $LogErr } else { Join-Path $LOGDIR "tmp_$TS_err.log" }
  Write-Tee "    → Run: $Exe $($ArgList -join ' ')"
  Write-Tee "      cwd : $Cwd"
  Write-Tee "      stdout -> $std"
  Write-Tee "      stderr -> $ste"
  $p = Start-Process -FilePath $Exe -ArgumentList $ArgList `
         -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $std -RedirectStandardError $ste
  $code = $p.ExitCode
  Write-Tee "      ExitCode = $code"
  return $code
}

# =============================================================================
#  PRE: Force JDK 21
# =============================================================================
Write-Tee '==============================================================================='
Write-Tee '  PRE: FORCE JDK 21 (project cache)  @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
if (-not (Test-Path $JDK21)) { Write-Tee "[FATAL] JDK21 missing at $JDK21"; exit 97 }
$env:JAVA_HOME = $JDK21
$env:PATH      = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$mv = & (Join-Path $JDK21 'bin\java.exe') -version 2>&1 | Out-String
Write-Tee "    java -version:`n$mv"
$mm = & mvn.cmd -version 2>&1 | Out-String
Write-Tee "    mvn -version:`n$mm"
if ($mm -notmatch 'version:\s*21\.') { Write-Tee '[FATAL] Maven still NOT on JDK 21'; exit 98 }
Write-Tee '    [OK] JDK 21 forced via project cache + PATH preload'

# =============================================================================
#  PRE2: Verify classes compiled assets intact  (MUST NOT RUN CLEAN HERE!)
# =============================================================================
$CLS = Join-Path $SRV 'target\classes'
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  PRE2: Verify target/classes already compiled (NO CLEAN — SAVE 25 MIN!)'
Write-Tee '==============================================================================='
if (-not (Test-Path $CLS)) { Write-Tee "[FATAL] classes missing — must clean rebuild"; exit 10 }
$f = Get-ChildItem $CLS -Recurse -File
Write-Tee "    files      = $($f.Count)"
Write-Tee "    size       = $([math]::Round(($f | Measure-Object Length -Sum).Sum/1MB,2)) MB"
Write-Tee "    *.class    = $(($f | ? { $_.Extension -eq '.class' }).Count)"
Write-Tee "    static/*   = $((Get-ChildItem (Join-Path $CLS 'static') -Recurse -File | Measure).Count)"
Write-Tee "    [OK] Compiled assets preserved — javac SKIPPED"

# =============================================================================
#  STEP 1/5: Maven package (NO CLEAN — uses existing target/classes)
# =============================================================================
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  STEP 1/5 MVN package (NO CLEAN!) — up to 3 retries @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
$JAR_PATH = $null
$MVN_OK = $false
foreach ($att in 1..3) {
  Write-Tee "  [Maven attempt $att/3]"
  $out = Join-Path $LOGDIR "takeover-step1-mvn-out-a$att.log"
  $err = Join-Path $LOGDIR "takeover-step1-mvn-err-a$att.log"
  [GC]::Collect(); [GC]::WaitForPendingFinalizers(); Start-Sleep -Seconds 3
  $code = Run-Exe mvn.cmd @('package','-DskipTests','-Dmaven.test.skip=true') `
                  -Cwd $SRV -LogOut $out -LogErr $err
  if ($code -eq 0) {
    $candidates = Get-ChildItem (Join-Path $SRV 'target') -Filter 'mateclaw-server-*.jar' `
                     | Where-Object { $_.Name -notlike '*-sources*' -and $_.Name -notlike '*-javadoc*' -and $_.Name -notlike '*.original' }
    if ($candidates -and $candidates.Count -ge 1) {
      $JAR_PATH = ($candidates | Sort-Object Length -Descending)[0].FullName
      $MVN_OK = $true
      Write-Tee "    [OK] Maven package SUCCESS (attempt $att)"
      Write-Tee "         JAR = $JAR_PATH"
      Write-Tee "         Size = $([math]::Round((Get-Item $JAR_PATH).Length/1MB,2)) MB"
      break
    }
  }
  Write-Tee "    attempt $att FAILED (exit=$code). GC + 10s backoff..."
  [GC]::Collect(); Start-Sleep -Seconds 10
}
if (-not $MVN_OK) { Write-Tee '[FATAL] MVN package FAILED all 3 attempts'; exit 21 }

# =============================================================================
#  STEP 2/5: JAR audit (static/index.html in BOOT-INF/classes) + copy → app.jar
# =============================================================================
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  STEP 2/5 JAR audit + copy app.jar @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
$a4 = Join-Path $ROOT 'scripts\step4_audit_jar.ps1'
$a4out = Join-Path $LOGDIR "takeover-step4-audit.log"
'' | Set-Content $a4out
$code = Run-Exe powershell.exe @('-NoProfile','-ExecutionPolicy','Bypass','-File',$a4,'-JarPath',$JAR_PATH,'-LogPath',$a4out) `
                -Cwd $ROOT
if ($code -ne 0) { Write-Tee "[FATAL] JAR audit failed (exit=$code)"; cat $a4out | Tee-Object $MASTER -Append; exit 22 }
Get-Content $a4out | Tee-Object $MASTER -Append
$APP_JAR = Join-Path $DSK 'resources\app.jar'
New-Item -ItemType Directory (Split-Path $APP_JAR) -Force | Out-Null
Write-Tee "    Copy-Item JAR → $APP_JAR"
Copy-Item $JAR_PATH $APP_JAR -Force
Write-Tee "    [OK] app.jar in place, size = $([math]::Round((Get-Item $APP_JAR).Length/1MB,2)) MB"

# =============================================================================
#  STEP 3/5: Desktop build (main process)
# =============================================================================
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  STEP 3/5 Desktop main build (npm run build) @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
$dout = Join-Path $LOGDIR "takeover-step3-desktop-out.log"
$derr = Join-Path $LOGDIR "takeover-step3-desktop-err.log"
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE    = 'local'
$code = Run-Exe npm.cmd @('run','build') -Cwd $DSK -LogOut $dout -LogErr $derr
if ($code -ne 0) { Write-Tee "[FATAL] desktop build FAILED (exit=$code). See $derr"; exit 23 }
$MAINJS = Join-Path $DSK 'dist-electron\main\index.js'
if (-not (Test-Path $MAINJS)) { Write-Tee "[FATAL] dist-electron/main/index.js missing"; exit 24 }
Write-Tee "    [OK] Desktop build OK"
Write-Tee "         main/index.js size = $([math]::Round((Get-Item $MAINJS).Length/1KB,2)) KB"

# =============================================================================
#  STEP 4/5: ws external check  (prevent "l.mask is not a function")
# =============================================================================
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  STEP 4/5 ws external check @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
$a5    = Join-Path $ROOT 'scripts\step5_check_ws_external.ps1'
$a5out = Join-Path $LOGDIR "takeover-step5-ws.log"
'' | Set-Content $a5out
$code = Run-Exe powershell.exe @('-NoProfile','-ExecutionPolicy','Bypass','-File',$a5,'-MainJs',$MAINJS,'-LogPath',$a5out) `
                -Cwd $ROOT
if ($code -ne 0) { Write-Tee "[FATAL] ws check failed (exit=$code)"; Get-Content $a5out | Tee-Object $MASTER -Append; exit 25 }
Get-Content $a5out | Tee-Object $MASTER -Append

# =============================================================================
#  STEP 5/5: electron-builder
# =============================================================================
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  STEP 5/5 electron-builder (win) @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================================='
New-Item $RELEASE -ItemType Directory -Force | Out-Null
$bout = Join-Path $LOGDIR "takeover-step5-builder-out.log"
$berr = Join-Path $LOGDIR "takeover-step5-builder-err.log"
$code = Run-Exe npx.cmd @('electron-builder','--win','--x64','--publish=never',"`-c.output=$RELEASE") `
                -Cwd $DSK -LogOut $bout -LogErr $berr
if ($code -ne 0) { Write-Tee "[FATAL] electron-builder FAILED (exit=$code). See $berr"; exit 26 }
Write-Tee "    electron-builder exit=0"
Write-Tee ''
Write-Tee '==============================================================================='
Write-Tee '  RELEASE CONTENTS'
Write-Tee '==============================================================================='
$rels = Get-ChildItem $RELEASE -Recurse -File -ErrorAction SilentlyContinue
foreach ($r in $rels) { Write-Tee ("    {0,-80} {1,10:N2} MB   {2}" -f $r.FullName.Replace($ROOT,'.').Replace('\','/'), ($r.Length/1MB), $r.LastWriteTime) }
$exe = $rels | Where-Object { $_.Extension -eq '.exe' } | Sort-Object Length -Descending
if ($exe) {
  Write-Tee ''
  Write-Tee ('[SUCCESS] Final EXE ready: {0}   ({1:N2} MB)' -f $exe[0].FullName, ($exe[0].Length/1MB))
} else {
  Write-Tee '[WARN] No .exe file found in release dir. Check logs.'
}
Write-Tee ''
Write-Tee '=== MANUAL TAKEOVER PIPELINE DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ==='
exit 0
