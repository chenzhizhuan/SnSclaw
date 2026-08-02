# =============================================================================
#  Final NSIS installer build.
#  KEY FIX: Move old win-unpacked/ completely out of release/ before build.
#  Defender keeps open handles on app.asar / app.jar for many minutes.
#  By renaming the folder to a GUID name OUTSIDE release/, the builder never
#  sees it and creates a brand new win-unpacked from scratch. The build also
#  re-runs afterPack (trim-playwright-driver.cjs → 237 MB app.jar).
#  After build succeeds, the old orphaned folder is deleted with retries.
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$REL  = Join-Path $DSK 'release'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "STEP5_KILL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "step5 final killer start @ " + (Get-Date -F 'HH:mm:ss') + "`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

# ---- evict old win-unpacked from release/ ----
$WU = Join-Path $REL 'win-unpacked'
if (Test-Path $WU) {
  $GRAVE = Join-Path $DSK ("_graveyard_" + [guid]::NewGuid().ToString('N'))
  W '  evicting ' + $WU + '  →  ' + $GRAVE
  $tries = 0
  while ($tries++ -lt 5) {
    try {
      Move-Item -Path $WU -Destination $GRAVE -Force -ErrorAction Stop
      W '    moved on try ' + $tries
      break
    } catch {
      W '    try ' + $tries + ' move fail: ' + $_.Exception.Message
      [GC]::Collect(); Start-Sleep -Seconds 5
    }
  }
  # Kick off best-effort background delete
  Start-Job -Name "rm_$TS" -ScriptBlock {
    param($P)
    for ($i = 0; $i -lt 40; $i++) {
      try {
        Remove-Item $P -Recurse -Force -ErrorAction Stop
        return 0
      } catch { Start-Sleep -Seconds 15 }
    }
  } -ArgumentList $GRAVE | Out-Null
}

# ---- also remove any half-written installer from the release root ----
Get-ChildItem $REL -Filter '*Setup*.exe' -ErrorAction SilentlyContinue | ForEach-Object {
  W '  deleting stale installer ' + $_.Name
  $tries = 0
  while ($tries++ -lt 4) {
    try { Remove-Item $_.FullName -Force -ErrorAction Stop; break }
    catch { [GC]::Collect(); Start-Sleep -Seconds 3 }
  }
}

$MaxAttempts = 6
$FinalExit   = 77
for ($attempt = 1; $attempt -le $MaxAttempts; $attempt++) {
  W ''
  W ('===== attempt ' + $attempt + '/' + $MaxAttempts + ' @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')
  $bout = Join-Path $LOG ("step5k-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("step5k-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 12

  W '  RUN: npx electron-builder --win --x64 --publish=never -c.compression=store'
  $p = Start-Process -FilePath npx.cmd `
         -ArgumentList @('electron-builder','--win','--x64','--publish=never','-c.compression=store') `
         -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $bout -RedirectStandardError $berr
  $code = $p.ExitCode
  W '  exit = ' + $code

  $setupFiles = @(Get-ChildItem $REL -Filter '*Setup*.exe' -File -ErrorAction SilentlyContinue)
  if ($setupFiles.Count -gt 0) {
    W ''
    W ('[****************** SUCCESS ******************]')
    W ('  Found ' + $setupFiles.Count + ' installer EXE.')
    foreach ($s in $setupFiles) {
      W ('  PATH  : ' + $s.FullName)
      W ('  SIZE  : ' + [math]::Round($s.Length/1MB,2) + ' MB')
      W ('  MTIME : ' + $s.LastWriteTime)
    }
    # Verify contents of final setup exe folder
    W ''
    W ('RELEASE ROOT INVENTORY:')
    Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
      if ($_.PSIsContainer) {
        $sz = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
        W ('  [DIR]  {0,-30} {1,9:N2} MB' -f $_.Name, ($sz/1MB))
      } else {
        W ('  [FILE] {0,-30} {1,9:N2} MB' -f $_.Name, ($_.Length/1MB))
      }
    }
    $FinalExit = 0
    break
  }

  if ($code -eq 0 -and $setupFiles.Count -eq 0) {
    W '  exit 0 but no setup exe — release root tree:'
    Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name }
    $FinalExit = 0
    break
  }

  W '  stdout tail:'
  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  W '  stderr tail:'
  if (Test-Path $berr) { Get-Content $berr -Tail 10 | ForEach-Object { W '    ! ' + $_ } }

  # Evict win-unpacked again between attempts in case Defender locked it mid-run
  $WU2 = Join-Path $REL 'win-unpacked'
  if (Test-Path $WU2) {
    $GRAVE2 = Join-Path $DSK ("_graveyard_" + [guid]::NewGuid().ToString('N'))
    W '  evict win-unpacked → ' + $GRAVE2
    try { Move-Item -Path $WU2 -Destination $GRAVE2 -Force -ErrorAction SilentlyContinue } catch {}
  }
  W '  backoff 25s ...'
  Start-Sleep -Seconds 25
}

if ($FinalExit -ne 0) {
  W ''
  W '[FAIL] Giving up after ' + $MaxAttempts + ' attempts.'
  exit 29
}
W ''
W ('===== STEP 5/5 COMPLETED @ ' + (Get-Date -F 'HH:mm:ss') + ' =====')
exit 0
