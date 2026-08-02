# =============================================================================
#  FINAL STEP ONLY — Run electron-builder to produce .exe installer
#  Preconditions (all done already):
#    ✓ server/target/mateclaw-server-1.0.0-SNAPSHOT.jar (405MB, 280 BOOT-INF libs)
#    ✓ desktop/resources/app.jar (copy of above, LastWrite updated)
#    ✓ desktop/dist-electron/main/index.js (24.56KB, require("ws") present)
#    ✓ BUILD_MODE=local → extraResources bundles JRE+JAR
#    ✓ afterPack hook runs trim-playwright-driver.cjs
# =============================================================================
$ErrorActionPreference = 'Stop'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$M    = Join-Path $LOG "BUILDER_FINAL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($M, "builder final step start`n")
function W { param($s) [System.IO.File]::AppendAllText($M, "$s`n") }

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

W '==============================================================='
W '  electron-builder (win x64) @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
W '  BUILD_MODE = ' + $env:BUILD_MODE
W '  output dir = ' + (Join-Path $DSK 'release')
$bout = Join-Path $LOG "builder-final-out-$TS.log"
$berr = Join-Path $LOG "builder-final-err-$TS.log"

# Use Start-Process for reliable stdout/stderr in sandbox
W '  RUN: npx electron-builder --win --x64 --publish never'
W '    cwd  = ' + $DSK
W '    out  = ' + $bout
W '    err  = ' + $berr
$p = Start-Process -FilePath npx.cmd `
       -ArgumentList @('electron-builder','--win','--x64','--publish=never') `
       -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $bout -RedirectStandardError $berr
$code = $p.ExitCode
W '  exit = ' + $code
$rels = Get-ChildItem (Join-Path $DSK 'release') -Recurse -File -ErrorAction SilentlyContinue
W ''
W '==============================================================='
W '  RELEASE OUTPUT'
W '==============================================================='
foreach ($r in $rels) {
  $rel = $r.FullName.Replace($ROOT, '.').Replace('\','/')
  W ("  {0,-70} {1,9:N2} MB   {2}" -f $rel, ($r.Length/1MB), $r.LastWriteTime)
}
$exe = $rels | Where-Object { $_.Extension -eq '.exe' } | Sort-Object Length -Descending | Select-Object -First 1
if ($exe) {
  W ''
  W ('[SUCCESS] FINAL EXE: ' + $exe.FullName + '  (' + [math]::Round($exe.Length/1MB,2) + ' MB)')
} elseif ($code -eq 0) {
  W ''
  W '[WARN] builder exit 0 but no .exe found — check release dir subfolders'
  $dirs = Get-ChildItem (Join-Path $DSK 'release') -Directory -ErrorAction SilentlyContinue
  foreach ($d in $dirs) { W '  subdir: ' + $d.Name }
} else {
  W '[FAIL] electron-builder FAILED (exit=' + $code + ')'
  if (Test-Path $bout) {
    W '--- builder stdout tail ---'
    Get-Content $bout -Tail 30 | ForEach-Object { W '  > ' + $_ }
  }
  if (Test-Path $berr) {
    W '--- builder stderr tail ---'
    Get-Content $berr -Tail 30 | ForEach-Object { W '  ! ' + $_ }
  }
  exit 26
}
W ''
W '=== BUILDER DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ==='
exit 0
