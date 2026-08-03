# =============================================================================
#  COMPLETE RE-BUILD PIPELINE (整合式一键重打包)
#  Step 0: PRE — JDK21 file-system check + server JAR audit + app.jar consistency
#  Step 1: Copy 405MB server JAR → desktop/resources/app.jar (if mismatch)
#  Step 2: Desktop main process build (npm run build)
#  Step 3: ws external regex check
#  Step 4: electron-builder NSIS win x64 (clean temp dir + compression=store)
#  Step 5: Copy Setup.exe → release/ + SHA256 + inventory report
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "FULL_REBUILD_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== FULL RE-BUILD START @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }
function Run-Exe {
  param(
    [Parameter(Mandatory=$true)][string]$FilePath,
    [Parameter(Mandatory=$true)][string[]]$ArgumentList,
    [Parameter(Mandatory=$true)][string]$Cwd,
    [Parameter(Mandatory=$true)][string]$OutLog,
    [Parameter(Mandatory=$true)][string]$ErrLog
  )
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}

# ===========================================================
# PRE: JDK21 + tooling
# ===========================================================
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$javaExe = Join-Path $JDK21 'bin\java.exe'
$javacExe= Join-Path $JDK21 'bin\javac.exe'
$jarExe  = Join-Path $JDK21 'bin\jar.exe'
$EB      = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$NPM     = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

W ''
W '==========================================================='
W '  PRE: Environment Check'
W '==========================================================='
W "  JDK21 ok     = $((Test-Path $javaExe) -and (Test-Path $javacExe) -and (Test-Path $jarExe))"
W "  npm.cmd      = $NPM"
W "  e-builder ok = $(Test-Path $EB)"
$7za = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  W '  !! 7za.exe missing, restoring from 7za_original.exe'
  Copy-Item -Path $7zaOrig -Destination $7za -Force
}
W "  7za.exe      = $(Test-Path $7za)"

# ===========================================================
# STEP 1 — Server JAR audit + app.jar copy if mismatch
# ===========================================================
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$APP_JAR = Join-Path $DSK 'resources\app.jar'
W ''
W '==========================================================='
W '  STEP 1/5 — Server JAR audit &amp; app.jar sync'
W '==========================================================='
W "  Server JAR   = $SRV_JAR"
W "  exists       = $(Test-Path $SRV_JAR)"
if (Test-Path $SRV_JAR) {
  $srvFi = Get-Item $SRV_JAR
  W "  size         = $([math]::Round($srvFi.Length/1MB,2)) MB"
  W "  last write   = $($srvFi.LastWriteTime)"
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  try {
    $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
    $idx = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
    $libs = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
    $z.Dispose()
    $ok = ($null -ne $idx -and $libs -gt 200)
    W "  static/index = $(if ($idx) { ($idx.Length) } else { 'MISSING' }) B"
    W "  BOOT-INF/lib = $libs jars  $(if ($libs -gt 200) {'[OK]'} else {'[NO]'})"
    W "  JAR audit    = $(if ($ok) {'PASS'} else {'FAIL'})"
  } catch {
    W "  JAR audit EXCEPTION: $($_.Exception.Message)"
  }
} else {
  W '  [FAIL] Server JAR not found - cannot continue'
  exit 11
}
# Sync
if ((-not (Test-Path $APP_JAR)) -or
    ((Get-Item $APP_JAR).Length -ne (Get-Item $SRV_JAR).Length) -or
    ((Get-Item $APP_JAR).LastWriteTime -lt (Get-Item $SRV_JAR).LastWriteTime)) {
  W '  → app.jar stale or missing, copying ...'
  [GC]::Collect(); Start-Sleep -Seconds 2
  $tries = 0
  while ($tries++ -lt 4) {
    try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
    catch { [GC]::Collect(); Start-Sleep -Seconds 5; W "    copy attempt $tries fail: $($_.Exception.Message)" }
  }
}
$appFi = Get-Item $APP_JAR
W "  app.jar now  = $([math]::Round($appFi.Length/1MB,2)) MB  last=$($appFi.LastWriteTime)"

# ===========================================================
# STEP 2 — Desktop main process build
# ===========================================================
W ''
W '==========================================================='
W '  STEP 2/5 — Desktop main build'
W '==========================================================='
$out2 = Join-Path $LOG "rebuild-step2-out-$TS.log"
$err2 = Join-Path $LOG "rebuild-step2-err-$TS.log"
$code2 = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $out2 -ErrLog $err2
W "  exit         = $code2"
$mainB = Join-Path $DSK 'dist-electron\main\index.js'
W "  main.js ok   = $(Test-Path $mainB)"
if (Test-Path $mainB) { W "  main.js size = $([math]::Round(((Get-Item $mainB).Length/1KB),2)) KB" }
if ($code2 -ne 0) {
  W '  FAIL build. stdout tail:'
  Get-Content $out2 -Tail 20 | ForEach-Object { W '    > ' + $_ }
  exit 12
}

# ===========================================================
# STEP 3 — ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/5 — ws external require("ws") check'
W '==========================================================='
$mainTxt = [IO.File]::ReadAllText($mainB)
$m = [regex]::Match($mainTxt, 'require\([\x22\x27]ws[\x22\x27]\)')
W "  found match  = $($m.Success)"
if (-not $m.Success) { W '  [FAIL] ws inlined - l.mask is not a function crash imminent!'; exit 13 }
W '  [OK] ws external verified'

# ===========================================================
# STEP 4 — electron-builder, retries, clean temp output dir
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/5 — electron-builder NSIS win x64 (clean dirs)'
W '==========================================================='
$MaxAttempts = 6
$FinalExit   = 88
$InstallerSrc = $null
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ("----- attempt " + $attempt + "/" + $MaxAttempts + " @ " + (Get-Date -F 'HH:mm:ss') + " -----")
  $ATTEMPT_OUT = Join-Path $env:TEMP ("_sns_rbld_" + $TS + "_" + $attempt)
  New-Item -ItemType Directory -Force -Path $ATTEMPT_OUT | Out-Null
  $bout = Join-Path $LOG ("rebuild-step4-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("rebuild-step4-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 5

  $dirArg = "-c.directories.output=$ATTEMPT_OUT"
  W "  electron-builder $dirArg"
  $code4 = Run-Exe -FilePath $EB `
            -ArgumentList @('--win','--x64','--publish=never','-c.compression=store',$dirArg) `
            -Cwd $DSK -OutLog $bout -ErrLog $berr
  $boSz = (Get-Item $bout -ErrorAction SilentlyContinue).Length
  W "  exit=$code4  stdout=$boSz B"

  $setupFiles = @(Get-ChildItem $ATTEMPT_OUT -Filter '*Setup*.exe' -File -Recurse -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    $InstallerSrc = $setupFiles[0]
    W "  [OK] FOUND installer at: $($InstallerSrc.FullName)"
    W "     size = $([math]::Round($InstallerSrc.Length/1MB,2)) MB"
    $FinalExit = 0
    break
  }
  if ($code4 -eq 0) {
    W '  exit 0 but no Setup.exe. listing ATTEMPT_OUT:'
    Get-ChildItem $ATTEMPT_OUT -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name }
    $FinalExit = 0
    break
  }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  if (Test-Path $berr) { Get-Content $berr -Tail 8  | ForEach-Object { W '    ! ' + $_ } }
  Start-Sleep -Seconds 18
}
if ($FinalExit -ne 0) { W '  giving up after all attempts'; exit 14 }

# ===========================================================
# STEP 5 — Publish to canonical release/ + final QA
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/5 — Publish &amp; Report'
W '==========================================================='
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
W "  copy: $($InstallerSrc.Name)  →  release/"
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
W "  PATH    = $($dstFi.FullName)"
W "  SIZE    = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME   = $($dstFi.LastWriteTime)"
W "  SHA256  = $sha"

# Final release root inventory
W ''
W '  Release root:'
Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
  if ($_.PSIsContainer) {
    $sz = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    W ("    [DIR ] {0,-30} {1,10:N2} MB" -f $_.Name, ($sz/1MB))
  } else {
    W ("    [FILE] {0,-30} {1,10:N2} MB" -f $_.Name, ($_.Length/1MB))
  }
}

W ''
W '==========================================================='
W '  REBUILD DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss')
W '==========================================================='
exit 0
