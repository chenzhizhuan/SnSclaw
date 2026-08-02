# =============================================================================
#  MINIMAL MANUAL PIPELINE — Steps 2-6 ONLY
#  Preconditions already met:
#    ✓ mateclaw-server/target/mateclaw-server-1.0.0-SNAPSHOT.jar (405MB, fat)
#    ✓ All 1998 *.class compiled, static/ frontend 281 files present
# =============================================================================
$ErrorActionPreference = 'Stop'

$ROOT    = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$SRV     = Join-Path $ROOT 'mateclaw-server'
$DSK     = Join-Path $ROOT 'mateclaw-desktop'
$RELEASE = Join-Path $DSK  'release_takeover_final'
$LOGDIR  = Join-Path $ROOT '.build-logs'
$TS      = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MASTER  = Join-Path $LOGDIR "MANUAL_FINAL_$TS.log"
New-Item $LOGDIR -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MASTER, "Manual final pipeline start`n")

function W { param([string]$s) [System.IO.File]::AppendAllText($MASTER, "$s`n") }
function Run-Exe {
  param([string]$Exe,[string[]]$ArgList=@(),[string]$Cwd=$ROOT,[string]$LogOut,[string]$LogErr)
  $std = if ($LogOut) { $LogOut } else { Join-Path $LOGDIR "m_$TS_out.log" }
  $ste = if ($LogErr) { $LogErr } else { Join-Path $LOGDIR "m_$TS_err.log" }
  W "  RUN: $Exe $($ArgList -join ' ')"
  W "    cwd = $Cwd"
  W "    out = $std"
  W "    err = $ste"
  $p = Start-Process -FilePath $Exe -ArgumentList $ArgList `
         -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $std -RedirectStandardError $ste
  W "    exit = $($p.ExitCode)"
  return $p.ExitCode
}

W '==============================================================='
W '  PRE: Force JDK 21 @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME = $JDK21
$env:PATH      = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE    = 'local'
W '  JAVA_HOME = ' + $env:JAVA_HOME
W '  JDK OK'

# --- STEP 1/5: verify JAR BOOT-INF/classes/static/index.html present ---------
W ''
W '==============================================================='
W '  STEP 1/5 JAR static/index.html audit @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
$JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path $JAR)) { W '[FATAL] jar missing'; exit 10 }
$ji = Get-Item $JAR
W "  JAR = $JAR"
W "  Size = $([math]::Round($ji.Length/1MB,2)) MB"
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($JAR)
try {
  $idx  = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
  $libs = $z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' } | Measure-Object | Select-Object -ExpandProperty Count
  $boot = $z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/*' } | Measure-Object | Select-Object -ExpandProperty Count
  if (-not $idx) { W '[FAIL] no BOOT-INF/classes/static/index.html'; exit 11 }
  W "  [OK] static/index.html in JAR ($($idx.Length) B)"
  W "  BOOT-INF entries = $boot"
  W "  BOOT-INF/lib jars = $libs"
} finally { $z.Dispose() }

# --- STEP 2/5: Copy → app.jar -------------------------------------------------
W ''
W '==============================================================='
W '  STEP 2/5 Copy JAR → desktop/resources/app.jar @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
New-Item (Split-Path $APP_JAR) -ItemType Directory -Force | Out-Null
[System.GC]::Collect()
Start-Sleep -Seconds 3
Copy-Item $JAR $APP_JAR -Force
$aj = Get-Item $APP_JAR
W "  [OK] app.jar size = $([math]::Round($aj.Length/1MB,2)) MB"
W "  app.jar LastWrite = $($aj.LastWriteTime)"

# --- STEP 3/5: Desktop build --------------------------------------------------
W ''
W '==============================================================='
W '  STEP 3/5 Desktop build (npm run build) @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
$dout = Join-Path $LOGDIR "manual-step3-desktop-out.log"
$derr = Join-Path $LOGDIR "manual-step3-desktop-err.log"
$code = Run-Exe npm.cmd @('run','build') -Cwd $DSK -LogOut $dout -LogErr $derr
if ($code -ne 0) { W '[FATAL] desktop build fail'; exit 23 }
$MAINJS = Join-Path $DSK 'dist-electron\main\index.js'
if (-not (Test-Path $MAINJS)) { W '[FATAL] main/index.js missing'; exit 24 }
$mj = Get-Item $MAINJS
W "  [OK] main/index.js = $([math]::Round($mj.Length/1KB,2)) KB"

# --- STEP 4/5: ws external check ---------------------------------------------
W ''
W '==============================================================='
W '  STEP 4/5 ws external check @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
$raw = [System.IO.File]::ReadAllText($MAINJS)
if ($raw -match 'require\([\x22\x27]ws[\x22\x27]\)') {
  W '  [OK] ws external verified: require("ws") present in main bundle'
  W "  main bundle size = $($mj.Length) B"
} else {
  W '[FAIL] ws NOT externalized. Will throw TypeError l.mask is not a function.'
  exit 25
}

# --- STEP 5/5: electron-builder ----------------------------------------------
W ''
W '==============================================================='
W '  STEP 5/5 electron-builder (win x64) @ ' + (Get-Date -F 'HH:mm:ss')
W '==============================================================='
New-Item $RELEASE -ItemType Directory -Force | Out-Null
$bout = Join-Path $LOGDIR "manual-step5-builder-out.log"
$berr = Join-Path $LOGDIR "manual-step5-builder-err.log"
$code = Run-Exe npx.cmd @('electron-builder','--win','--x64','--publish=never',"`-c.output=$RELEASE") `
                -Cwd $DSK -LogOut $bout -LogErr $berr
if ($code -ne 0) { W '[FATAL] electron-builder fail'; exit 26 }
W '  electron-builder exit 0'

W ''
W '==============================================================='
W '  FINAL RELEASE OUTPUT'
W '==============================================================='
$rels = Get-ChildItem $RELEASE -Recurse -File -ErrorAction SilentlyContinue
foreach ($r in $rels) {
  $rel = $r.FullName.Replace($ROOT, '.').Replace('\','/')
  W ("  {0,-70} {1,9:N2} MB   {2}" -f $rel, ($r.Length/1MB), $r.LastWriteTime)
}
$exe = $rels | Where-Object { $_.Extension -eq '.exe' } | Sort-Object Length -Descending | Select-Object -First 1
if ($exe) {
  W ''
  W ('[SUCCESS] FINAL EXE = ' + $exe.FullName + '  (' + [math]::Round($exe.Length/1MB,2) + ' MB)')
} else {
  W '[WARN] no .exe produced. Check manual-step5-builder-err.log'
}
W ''
W '=== MANUAL FINAL PIPELINE DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ==='
exit 0
