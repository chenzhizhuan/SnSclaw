# =============================================================================
#  COMPLETE REBUILD PIPELINE WITH VITE FRONTEND (包含前端重构建)
#  = 1. Vite build (emptyOutDir → fresh static/)
#  = 2. Maven package (-Dmaven.main.skip=true 跳过 compile, 只 copy resources + repackage)
#  = 3. JAR audit + app.jar sync
#  = 4. Desktop main build (npm run build)
#  = 5. ws external regex check
#  = 6. electron-builder NSIS win x64 (clean temp dir + store mode)
#  = 7. Publish Setup.exe to release/ + SHA256 report
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI   = Join-Path $ROOT 'mateclaw-ui'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$STATIC= Join-Path $SRV 'src\main\resources\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "WITH_VITE_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== WITH-VITE REBUILD START @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
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

# PRE — tooling + JDK21
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$javaExe= Join-Path $JDK21 'bin\java.exe'
$EB     = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$PNPM   = (Get-Command pnpm.cmd -ErrorAction SilentlyContinue).Source
if (-not $PNPM) { $PNPM = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source; $Global:IsNpm=$true } else { $Global:IsNpm=$false }
$NPM    = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
$MVN    = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

W ''
W '==========================================================='
W '  PRE CHECK'
W '==========================================================='
W "  JDK21 ok    = $((Test-Path $javaExe))"
W "  package mgr = $(if ($IsNpm) {'npm'} else {'pnpm'})  $PNPM"
W "  npm.cmd     = $NPM"
W "  mvn.cmd     = $MVN"
W "  electron-b  = $(Test-Path $EB)"
# 7za restore guard
$7za = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) { Copy-Item -Path $7zaOrig -Destination $7za -Force; W '  [RESTORE] 7za.exe' }
W "  7za.exe     = $(Test-Path $7za)"

# ===========================================================
# STEP 1/7 — VITE FRONTEND BUILD
# ===========================================================
W ''
W '==========================================================='
W '  STEP 1/7 — Vite frontend build (OUTPUT to server/static)'
W '==========================================================='
$out1 = Join-Path $LOG "vite-out-$TS.log"
$err1 = Join-Path $LOG "vite-err-$TS.log"
$cmd1Args = if ($IsNpm) { @('run','build') } else { @('build') }
$pkgMgr1  = if ($IsNpm) { $NPM } else { $PNPM }
$t1Start = Get-Date
W "  RUN: $(if($IsNpm){'npm'}else{'pnpm'}) $cmd1Args  cwd=$UI"
$code1 = Run-Exe -FilePath $pkgMgr1 -ArgumentList $cmd1Args -Cwd $UI -OutLog $out1 -ErrLog $err1
$t1Dur = ((Get-Date) - $t1Start).TotalSeconds
$cntStatic = 0
$szStatic  = 0
if (Test-Path $STATIC) {
  $fi = Get-ChildItem $STATIC -Recurse -File -ErrorAction SilentlyContinue
  $cntStatic = $fi.Count
  $szStatic  = ($fi | Measure-Object Length -Sum).Sum
}
W "  exit=$code1  dur=$([math]::Round($t1Dur,1))s  static_count=$cntStatic  static_size=$([math]::Round($szStatic/1MB,2))MB"
if ($code1 -ne 0) {
  W '  VITE FAIL! stdout tail:'
  Get-Content $out1 -Tail 40 | ForEach-Object { W '    > ' + $_ }
  W '  VITE FAIL! stderr tail:'
  Get-Content $err1 -Tail 20 | ForEach-Object { W '    ! ' + $_ }
  exit 21
}
W "  [OK] Vite build index.html = $(Test-Path (Join-Path $STATIC 'index.html'))"

# ===========================================================
# STEP 2/7 — MAVEN PACKAGE (resources + package, skip compile)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 2/7 — Maven package (-Dmaven.main.skip skip compile/process-resources BUT do package/BOOT-INF repackage)'
W '  NOTE: because we emptyOutDir:true, we cannot skip process-resources — we NEED fresh copy. So use:'
W '        package -DskipTests -Dmaven.test.skip=true (compiles java fast, copies resources, repackages fat JAR)'
W '==========================================================='
# We must re-run process-resources because Vite emptied static/ under src/main/resources which feeds target/classes.
# Only test-compile/test phases are skipped.
$MVN_ARGS = @('package','-DskipTests','-Dmaven.test.skip=true')
$out2 = Join-Path $LOG "mvn-out-$TS.log"
$err2 = Join-Path $LOG "mvn-err-$TS.log"
$t2Start = Get-Date
$MaxMvnAttempts = 4
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$MvnOK = $false
for ($mvna = 1; $mvna -le $MaxMvnAttempts; $mvna++) {
  W "  --- mvn attempt $mvna/$MaxMvnAttempts @ $((Get-Date -F 'HH:mm:ss')) ---"
  W "  RUN: mvn.cmd $MVN_ARGS  cwd=$SRV"
  $code2 = Run-Exe -FilePath $MVN -ArgumentList $MVN_ARGS -Cwd $SRV -OutLog $out2 -ErrLog $err2
  W "  exit=$code2"
  if ($code2 -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) { $MvnOK = $true; break }
  W '    last attempt not good — Defender retry in 15s ...'
  [GC]::Collect()
  Start-Sleep -Seconds 15
}
$t2Dur = ((Get-Date) - $t2Start).TotalSeconds
W "  dur=$([math]::Round($t2Dur,1))s"
if (-not $MvnOK) {
  W '  [FAIL] Maven package after all attempts.'
  Get-Content $out2 -Tail 30 | ForEach-Object { W '    > ' + $_ }
  exit 22
}
$srvFi = Get-Item $SRV_JAR
W "  [OK] server JAR = $([math]::Round($srvFi.Length/1MB,2)) MB  mtime=$($srvFi.LastWriteTime)"

# ===========================================================
# STEP 3/7 — JAR audit + app.jar sync
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/7 — JAR audit + app.jar sync to desktop/resources'
W '==========================================================='
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
  $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
  $idx = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
  $libs = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
  $staticEntries = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
  $z.Dispose()
  W "  BOOT-INF/classes/static/index.html = $(if($idx){'OK (' + $idx.Length + ' B)'}else{'MISSING'})"
  W "  BOOT-INF/lib jar count             = $libs"
  W "  BOOT-INF/classes/static/*          = $staticEntries"
} catch { W '  audit err: ' + $_.Exception.Message; exit 23 }
# Sync
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
$copyAttempts = 0
while ($copyAttempts++ -lt 5) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "  copy jar attempt $copyAttempts fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 5 }
}
W "  app.jar = $([math]::Round(((Get-Item $APP_JAR).Length/1MB),2)) MB"

# ===========================================================
# STEP 4/7 — Desktop main process build
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/7 — Desktop main build'
W '==========================================================='
$out4 = Join-Path $LOG "desk-out-$TS.log"
$err4 = Join-Path $LOG "desk-err-$TS.log"
$code4 = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $out4 -ErrLog $err4
$mainB = Join-Path $DSK 'dist-electron\main\index.js'
W "  exit=$code4  main ok=$(Test-Path $mainB)"
if (Test-Path $mainB) { W "  main size = $([math]::Round(((Get-Item $mainB).Length/1KB),2)) KB" }
if ($code4 -ne 0) { exit 24 }

# ===========================================================
# STEP 5/7 — ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/7 — ws external regex check'
W '==========================================================='
$m = [regex]::Match([IO.File]::ReadAllText($mainB), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  require('ws') found = $($m.Success)"
if (-not $m.Success) { W '  ws inlined — FATAL'; exit 25 }
W '  [OK] ws external verified'

# ===========================================================
# STEP 6/7 — electron-builder (clean output dir per attempt)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 6/7 — electron-builder NSIS win x64 store'
W '==========================================================='
$InstallerSrc = $null
for ($attempt = 1; $attempt -le 6; $attempt++) {
  $O = Join-Path $env:TEMP ("_vite_rbld_" + $TS + "_" + $attempt)
  New-Item -ItemType Directory -Force -Path $O | Out-Null
  $bout = Join-Path $LOG ("eb-out-$TS-$attempt.log")
  $berr = Join-Path $LOG ("eb-err-$TS-$attempt.log")
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$O")
  W "  --- attempt $attempt/6  RUN: electron-builder $arg"
  $code = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $oSz = (Get-Item $bout -ErrorAction SilentlyContinue).Length
  W "  exit=$code  stdout=$oSz B"
  $setups = @(Get-ChildItem $O -Filter '*Setup*.exe' -Recurse -File -ErrorAction SilentlyContinue)
  if ($setups.Count -gt 0) {
    $InstallerSrc = $setups[0]
    W "  [OK] Installer = $($InstallerSrc.FullName)   $([math]::Round($InstallerSrc.Length/1MB,2)) MB"
    break
  }
  if ($code -eq 0) {
    W '  exit=0 but no Setup.exe, listing output dir:'
    Get-ChildItem $O -ErrorAction SilentlyContinue | ForEach-Object { W '    ' + $_.Name }
    break
  }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  Start-Sleep -Seconds 20
}
if (-not $InstallerSrc) { W '  [FAIL] builder exit without installer'; exit 26 }

# ===========================================================
# STEP 7/7 — Publish to canonical release/ + SHA256
# ===========================================================
W ''
W '==========================================================='
W '  STEP 7/7 — Publish + SHA256'
W '==========================================================='
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
W "  COPY $($InstallerSrc.Name)  →  release/"
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
W "  PATH   = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
W ''
W '  Release root:'
Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
  if ($_.PSIsContainer) {
    $sz = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    W ("    [DIR ] {0,-25} {1,10:N2} MB" -f $_.Name, ($sz/1MB))
  } else {
    W ("    [FILE] {0,-25} {1,10:N2} MB" -f $_.Name, ($_.Length/1MB))
  }
}
W ''
W '=== WITH-VITE REBUILD DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
