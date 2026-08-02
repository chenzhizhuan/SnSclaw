# =============================================================================
#  NSIS installer build — EXPLICIT electron-builder.cmd path resolution.
#  Previous scripts used Start-Process npx.cmd which produced NULL ExitCode
#  and 0-byte logs (npx couldn't be resolved inside the sandbox child).
#  Here we resolve to .\node_modules\.bin\electron-builder.cmd explicitly.
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$REL  = Join-Path $DSK 'release'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "STEP5_RESOLVE_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "step5 explicit-path start @ " + (Get-Date -F 'HH:mm:ss') + "`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

# Explicitly resolve electron-builder.cmd
$EB = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
W "  electron-builder.cmd exists = $(Test-Path $EB)"
W "  path = $EB"
$NODE_EXE = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
W "  node.exe = $NODE_EXE"
$NPM_EXE  = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
W "  npm.cmd  = $NPM_EXE"

# Kick win-unpacked far out
$WU = Join-Path $REL 'win-unpacked'
if (Test-Path $WU) {
  $GRAVE = Join-Path $env:TEMP ("_sns_evict_" + [guid]::NewGuid().ToString('N'))
  W "  moving old win-unpacked → $GRAVE"
  $tries = 0
  while ($tries++ -lt 6) {
    try { Move-Item -Path $WU -Destination $GRAVE -Force -ErrorAction Stop; W "    move ok ($tries)"; break }
    catch { W "    move attempt $tries fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 6 }
  }
}

$MaxAttempts = 6
$FinalExit   = 88
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('===== attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')
  $bout = Join-Path $LOG ("rslv-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("rslv-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 10

  $args = @('--win','--x64','--publish=never','-c.compression=store')
  W "  RUN: $EB $args"
  $p = Start-Process -FilePath $EB `
         -ArgumentList $args `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  W "  exit = $code"
  W "  stdout bytes = $([IO.FileInfo]$bout).Length"
  W "  stderr bytes = $([IO.FileInfo]$berr).Length"

  $setupFiles = @(Get-ChildItem $REL -Filter '*Setup*.exe' -File -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    W ''
    W ('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    W ('!          SETUP EXE SUCCESS            !')
    W ('!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!')
    foreach ($s in $setupFiles) {
      W ('  FILE  : ' + $s.FullName)
      W ('  SIZE  : ' + [math]::Round($s.Length/1MB,2) + ' MB')
      W ('  MTIME : ' + $s.LastWriteTime)
    }
    W ''
    W ('Release root:')
    Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
      if ($_.PSIsContainer) {
        $sz = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        W ('  [DIR]  {0,-25} {1,10:N2} MB' -f $_.Name, ($sz/1MB))
      } else {
        W ('  [FILE] {0,-25} {1,10:N2} MB' -f $_.Name, ($_.Length/1MB))
      }
    }
    $FinalExit = 0
    break
  }

  if ($code -eq 0 -and $setupFiles.Count -eq 0) {
    W '  exit 0. release contents:'
    Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name }
    $FinalExit = 0
    break
  }

  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  if (Test-Path $berr) { Get-Content $berr -Tail 10 | ForEach-Object { W '    ! ' + $_ } }

  # Kick win-unpacked again if it got recreated and locked
  $WU2 = Join-Path $REL 'win-unpacked'
  if (Test-Path $WU2) {
    $GRAVE2 = Join-Path $env:TEMP ("_sns_evict_" + [guid]::NewGuid().ToString('N'))
    try { Move-Item -Path $WU2 -Destination $GRAVE2 -Force -ErrorAction SilentlyContinue; W '  evicted win-unpacked' } catch { W '  cannot evict win-unpacked' }
  }
  W '  backoff 28s ...'
  Start-Sleep -Seconds 28
}

if ($FinalExit -ne 0) {
  W ''
  W '[FAIL] Giving up.'
  exit 30
}
W ''
W ('== ALL DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ==')
exit 0
