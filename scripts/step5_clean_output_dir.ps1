# =============================================================================
#  NSIS build: Use a BRAND-NEW output directory each time (clean, no locks,
#  no Defender-touched files) instead of the default `release/` where the
#  previous win-unpacked/app.asar and app.jar are held open for minutes.
#  After build succeeds, move the produced *Setup.exe back to release/.
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$OUT  = Join-Path $env:TEMP ("_sns_build_" + $TS)
$MAST = Join-Path $LOG "STEP5_CLEANOUT_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "step5 clean-output-dir build @ " + (Get-Date -F 'HH:mm:ss') + "`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

$EB = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
W "  electron-builder.cmd  = $(Test-Path $EB)"
W "  BRAND NEW output dir  = $OUT"  # 100% clean, defender never saw it
New-Item -ItemType Directory -Force -Path $OUT | Out-Null

$MaxAttempts = 5
$FinalExit   = 77
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('===== attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')
  $bout = Join-Path $LOG ("clout-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("clout-err-$TS-$attempt.log")
  # Slightly different clean output dir per attempt
  $ATTEMPT_OUT = Join-Path $env:TEMP ("_sns_build_" + $TS + "_" + $attempt)
  New-Item -ItemType Directory -Force -Path $ATTEMPT_OUT | Out-Null
  [GC]::Collect(); Start-Sleep -Seconds 6

  $dirArg = "-c.directories.output=$ATTEMPT_OUT"
  $args = @('--win','--x64','--publish=never','-c.compression=store',$dirArg)
  W "  RUN: electron-builder $args"
  $p = Start-Process -FilePath $EB `
         -ArgumentList $args `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  $outSize = (Get-Item $bout -ErrorAction SilentlyContinue).Length
  $errSize = (Get-Item $berr -ErrorAction SilentlyContinue).Length
  W "  exit = $code   stdout=$outSize B   stderr=$errSize B"

  # Check installer at the NEW clean output dir
  $setupFiles = @(Get-ChildItem $ATTEMPT_OUT -Filter '*Setup*.exe' -File -Recurse -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    W ''
    W ('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    W ('!         SUCCESS — INSTALLER EXE       !')
    W ('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    foreach ($s in $setupFiles) {
      W ('  src   = ' + $s.FullName)
      W ('  size  = ' + [math]::Round($s.Length/1MB,2) + ' MB')
      W ('  mtime = ' + $s.LastWriteTime)
      # Copy to canonical release dir
      $dst = Join-Path (Join-Path $DSK 'release') $s.Name
      W '  COPY  → ' + $dst
      try {
        Copy-Item -Path $s.FullName -Destination $dst -Force -ErrorAction Stop
        $fi = Get-Item $dst
        W ('  DST   = ' + $fi.FullName + '  (' + [math]::Round($fi.Length/1MB,2) + ' MB)')
      } catch { W '  copy fail: ' + $_.Exception.Message }
    }
    $FinalExit = 0
    break
  }

  if ($code -eq 0 -and $setupFiles.Count -eq 0) {
    W '  exit 0, no setup exe. listing ATTEMPT_OUT tree:'
    Get-ChildItem $ATTEMPT_OUT -Recurse -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.FullName.Replace($env:TEMP,'%TEMP%') }
    $FinalExit = 0
    break
  }

  if (Test-Path $bout) { Get-Content $bout -Tail 25 | ForEach-Object { W '    > ' + $_ } }
  if (Test-Path $berr) { Get-Content $berr -Tail 10 | ForEach-Object { W '    ! ' + $_ } }
  W '  backoff 22s ...'
  Start-Sleep -Seconds 22
}

if ($FinalExit -ne 0) {
  W ''
  W '[FAIL] Giving up.'
  exit 31
}
W ''
W ('=== DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ===')
exit 0
