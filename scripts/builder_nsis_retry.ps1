# =============================================================================
#  FINAL RETRY — Re-run electron-builder with retries for NSIS spawn EPERM.
#  win-unpacked/ is already fully populated. Re-running is fast: builder skips
#  packaging and goes straight to NSIS installer generation. Each retry
#  gives Defender ~10 s to release the 7za.exe/makensis.exe spawn lock.
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$M    = Join-Path $LOG "NSIS_RETRY_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($M, "nsis retry start`n")
function W { param($s) [System.IO.File]::AppendAllText($M, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

$MaxAttempts = 5
$FinalExit   = 99
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('=' * 63)
  W ('  electron-builder NSIS attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss'))
  W ('=' * 63)
  $bout = Join-Path $LOG ("nsis-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("nsis-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 8

  W '  RUN: npx electron-builder --win --x64 --publish=never'
  W '    cwd = ' + $DSK
  $p = Start-Process -FilePath npx.cmd `
         -ArgumentList @('electron-builder','--win','--x64','--publish=never') `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  W '  exit = ' + $code

  $exes = @(Get-ChildItem (Join-Path $DSK 'release') -Filter '*.exe' -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.Directory.Name -ne 'win-unpacked' })
  $installer = $exes | Sort-Object Length -Descending | Select-Object -First 1
  if ($installer) {
    W ''
    W ('[SUCCESS] INSTALLER EXE FOUND!')
    W ('  path = ' + $installer.FullName)
    W ('  size = ' + [math]::Round($installer.Length/1MB,2) + ' MB')
    W ('  last = ' + $installer.LastWriteTime)
    W ''
    $rels = Get-ChildItem (Join-Path $DSK 'release') -Recurse -File -ErrorAction SilentlyContinue
    foreach ($r in $rels) {
      $rel = $r.FullName.Replace($ROOT, '.').Replace('\','/')
      W ("  {0,-70} {1,9:N2} MB" -f $rel, ($r.Length/1MB))
    }
    $FinalExit = 0
    break
  }

  if ($code -eq 0) {
    W '  exit 0 but no setup .exe in release/ — listing release tree:'
    Get-ChildItem (Join-Path $DSK 'release') -Recurse -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.FullName.Replace($ROOT,'.') }
    $FinalExit = 0
    break
  }

  # Read failure tail
  W '  --- builder stdout tail ---'
  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  W '  --- builder stderr tail ---'
  if (Test-Path $berr) { Get-Content $berr -Tail 10 | ForEach-Object { W '    ! ' + $_ } }
  W '  retry in 12s ...'
  Start-Sleep -Seconds 12
}

if ($FinalExit -ne 0) {
  W ''
  W ('[FAIL] Giving up after ' + $MaxAttempts + ' attempts.')
  exit 27
}
W ''
W ('=== NSIS DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' exit=' + $FinalExit + ' ===')
exit 0
