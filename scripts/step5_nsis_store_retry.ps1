# =============================================================================
#  One-shot NSIS build: Start-Process with stdout/stderr redirect to files.
#  No Tee-Object, no Write-Host, no inline &amp; that might block sandbox.
#  Try with compression=store (fastest, least I/O, avoids 7za spawn EPERM from
#  Defender locking the deflate worker).
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "STEP5_FINAL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "step5 nsis build start @ " + (Get-Date -F 'HH:mm:ss') + "`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

$MaxAttempts = 5
$FinalExit   = 77
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('===== attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')

  $bout = Join-Path $LOG ("step5-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("step5-err-$TS-$attempt.log")
  [GC]::Collect()
  [GC]::WaitForPendingFinalizers()
  Start-Sleep -Seconds 15

  W '  RUN: npx electron-builder --win --x64 --publish=never -c.compression=store'
  W '    cwd = ' + $DSK
  $p = Start-Process -FilePath npx.cmd `
         -ArgumentList @('electron-builder','--win','--x64','--publish=never','-c.compression=store') `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  W '  exit = ' + $code

  $setupFiles = @(Get-ChildItem (Join-Path $DSK 'release') -Filter '*Setup*.exe' -File -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    W ''
    W ('[SUCCESS] FOUND ' + $setupFiles.Count + ' SETUP EXE:')
    foreach ($s in $setupFiles) {
      W ('  ' + $s.FullName + '  ' + [math]::Round($s.Length/1MB,2) + ' MB  ' + $s.LastWriteTime)
    }
    $FinalExit = 0
    break
  }

  W '  stdout tail:'
  if (Test-Path $bout) { Get-Content $bout -Tail 25 | ForEach-Object { W '    > ' + $_ } }
  W '  stderr tail:'
  if (Test-Path $berr) { Get-Content $berr -Tail 15 | ForEach-Object { W '    ! ' + $_ } }

  if ($code -eq 0 -and $setupFiles.Count -eq 0) {
    W '  exit 0 but no setup exe — listing release root:'
    Get-ChildItem (Join-Path $DSK 'release') -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name + '  ' + [math]::Round($_.Length/1MB,2) + 'MB' }
    $FinalExit = 0
    break
  }
  W '  backoff 18s ...'
  Start-Sleep -Seconds 18
}

W ''
W ('STEP5 DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' FinalExit=' + $FinalExit)
exit $FinalExit
