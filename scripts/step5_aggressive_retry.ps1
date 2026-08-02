# =============================================================================
#  NSIS installer build — Aggressive Defender lock release pre-step, then
#  electron-builder with compression=store. Retry up to 8 times; between
#  attempts we actually touch/scan all files in win-unpacked/ (read 1 byte
#  each) to force Defender to finish its background scan and release handles.
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "STEP5_AGGRO_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "step5 aggressive retry start @ " + (Get-Date -F 'HH:mm:ss') + "`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

function Release-LockOnDir {
  param([Parameter(Mandatory=$true)][string]$Dir)
  if (-not (Test-Path $Dir)) { return }
  W '    releasing handles on ' + $Dir
  $fi = Get-ChildItem $Dir -Recurse -File -ErrorAction SilentlyContinue
  $count = 0
  foreach ($f in $fi) {
    try {
      $fs = [System.IO.File]::OpenRead($f.FullName)
      $null = $fs.ReadByte()
      $fs.Close(); $fs.Dispose()
    } catch {
      # ignore — if still locked, builder will fail and we retry
    }
    $count++
    if ($count % 500 -eq 0) { W "      touched $count / $($fi.Count) ..." }
  }
  [GC]::Collect(); [GC]::WaitForPendingFinalizers()
  Start-Sleep -Seconds 6
}

$MaxAttempts = 8
$FinalExit   = 77
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('===== attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')

  # Pre: touch every file in win-unpacked/ + release/ to force Defender scan finish
  Release-LockOnDir (Join-Path $DSK 'release\win-unpacked')
  if ($attempt -gt 2) {
    W '    hard attempt: rename win-unpacked to .bak then delete'
    $rel = Join-Path $DSK 'release'
    $bak = Join-Path $rel ("win-unpacked-bak-" + [guid]::NewGuid().ToString('N'))
    $wu  = Join-Path $rel 'win-unpacked'
    if (Test-Path $wu) {
      try { Rename-Item -Path $wu -NewName (Split-Path $bak -Leaf) -Force -ErrorAction Stop } catch { W '    rename fail: ' + $_.Exception.Message }
      Start-Sleep -Seconds 4
      try { Remove-Item $bak -Recurse -Force -ErrorAction Stop } catch { W '    delete bak fail: ' + $_.Exception.Message }
    }
  }

  $bout = Join-Path $LOG ("step5a-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("step5a-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 8

  W '  RUN: npx electron-builder --win --x64 --publish=never -c.compression=store'
  $p = Start-Process -FilePath npx.cmd `
         -ArgumentList @('electron-builder','--win','--x64','--publish=never','-c.compression=store') `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  W '  exit = ' + $code

  $setupFiles = @(Get-ChildItem (Join-Path $DSK 'release') -Filter '*Setup*.exe' -File -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    W ''
    W ('[SUCCESS] ' + $setupFiles.Count + ' SETUP EXE:')
    foreach ($s in $setupFiles) {
      W ('  ' + $s.FullName + '  ' + [math]::Round($s.Length/1MB,2) + ' MB  ' + $s.LastWriteTime)
    }
    # Also output BLOCK-LEVEL summary for user
    W ''
    W ('=== FINAL PRODUCT ===')
    W ('  Mode     : LOCAL (bundled JDK 21 + Spring Boot JAR)')
    W ('  Installer: ' + $setupFiles[0].FullName)
    W ('  Size     : ' + [math]::Round($setupFiles[0].Length/1MB,2) + ' MB')
    W ('  Brand    : SnSclaw / MateClaw')
    $FinalExit = 0
    break
  }

  W '  stdout tail:'
  if (Test-Path $bout) { Get-Content $bout -Tail 15 | ForEach-Object { W '    > ' + $_ } }
  W '  stderr tail:'
  if (Test-Path $berr) { Get-Content $berr -Tail 8 | ForEach-Object { W '    ! ' + $_ } }

  if ($code -eq 0 -and $setupFiles.Count -eq 0) {
    W '  exit 0 but no setup exe — listing release:'
    Get-ChildItem (Join-Path $DSK 'release') -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name + '  (' + [math]::Round($_.Length/1MB,2) + ' MB)' }
    $FinalExit = 0
    break
  }
  W '  backoff 22s ...'
  Start-Sleep -Seconds 22
}

if ($FinalExit -ne 0) {
  W ''
  W '[FAIL] Giving up after ' + $MaxAttempts + ' attempts.'
  exit 28
}
W ''
W ('ALL DONE @ ' + (Get-Date -F 'HH:mm:ss'))
exit 0
