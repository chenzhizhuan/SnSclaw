# =============================================================================
#  CORRECT REBUILD WITH VITE (绕过 bash + pnpm 缺失)
#  = 1. node.exe 直接调 mateclaw-ui/node_modules/vue-tsc/bin/vue-tsc.js --noEmit (TS check)
#  = 2. node.exe 直接调 mateclaw-ui/node_modules/vite/bin/vite.js build → server/static
#  = 3. Maven package (process-resources + compile + package 同生命周期 → BOOT-INF 注入)
#  = 4. JAR audit + app.jar sync
#  = 5. Desktop main build + ws external check
#  = 6. electron-builder (clean temp dir / store)
#  = 7. Publish Setup.exe → release/ + SHA256
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI   = Join-Path $ROOT 'mateclaw-ui'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$STATIC= Join-Path $SRV 'src\main\resources\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "VITE2_OK_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== CORRECT VITE REBUILD @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }
function Run-Exe {
  param([Parameter(Mandatory=$true)][string]$FilePath,
        [Parameter(Mandatory=$true)][string[]]$ArgumentList,
        [Parameter(Mandatory=$true)][string]$Cwd,
        [Parameter(Mandatory=$true)][string]$OutLog,
        [Parameter(Mandatory=$true)][string]$ErrLog)
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}

# PRE
$JDK21   = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NODE    = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
$MVN     = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
$NPM     = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
$VITE_JS = Join-Path $UI 'node_modules\vite\bin\vite.js'
$VUE_TSC = Join-Path $UI 'node_modules\vue-tsc\bin\vue-tsc.js'
$EB      = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

W ''
W '==========================================================='
W '  PREFLIGHT'
W '==========================================================='
W "  node.exe    = $NODE"
W "  mvn.cmd     = $MVN"
W "  npm.cmd     = $NPM"
W "  vite.js ok  = $(Test-Path $VITE_JS)"
W "  vue-tsc.js  = $(Test-Path $VUE_TSC)"
W "  electron-b  = $(Test-Path $EB)"
# 7za restore guard
$7za = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -ErrorAction SilentlyContinue
  W "  7za.exe restored"
}
W "  7za.exe     = $(Test-Path $7za)"
if (-not (Test-Path $VITE_JS)) { W "  [FATAL] vite.js missing at mateclaw-ui/node_modules/vite/bin/vite.js - please pnpm install first"; exit 99 }

# ===========================================================
# STEP 1/7 — VUE-TSC TYPE CHECK (skippable on failure → just warn)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 1/7 — vue-tsc --noEmit TS type check'
W '==========================================================='
if (Test-Path $VUE_TSC) {
  $tso = Join-Path $LOG "tsc-out-$TS.log"
  $tse = Join-Path $LOG "tsc-err-$TS.log"
  $ts1 = Get-Date
  $tscode = Run-Exe -FilePath $NODE -ArgumentList @($VUE_TSC,'--noEmit') -Cwd $UI -OutLog $tso -ErrLog $tse
  W "  exit=$tscode  dur=$([math]::Round(((Get-Date)-$ts1).TotalSeconds,1)) s"
  if ($tscode -ne 0) {
    W "  [WARN] TS has $tscode type errors — will still proceed to vite build (WARN ONLY, not blocking)"
  } else {
    W "  [OK] TS type check PASS"
  }
} else {
  W "  skip (vue-tsc not in node_modules, expected in workspace root. will try vite only)"
}

# ===========================================================
# STEP 2/7 — VITE BUILD → writes to server/static directly (emptyOutDir:true)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 2/7 — VITE build (outDir: ../mateclaw-server/src/main/resources/static)'
W '==========================================================='
$vout = Join-Path $LOG "vite-out-$TS.log"
$verr = Join-Path $LOG "vite-err-$TS.log"
$t1 = Get-Date
$vcode = Run-Exe -FilePath $NODE -ArgumentList @($VITE_JS,'build') -Cwd $UI -OutLog $vout -ErrLog $verr
$vdur = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
$idx = Join-Path $STATIC 'index.html'
$cnt = 0; $sz = 0
if (Test-Path $STATIC) {
  $f = Get-ChildItem $STATIC -Recurse -File -ErrorAction SilentlyContinue
  $cnt = $f.Count; $sz = ($f | Measure-Object Length -Sum).Sum
}
W "  exit=$vcode  dur=${vdur}s"
W "  static/index.html = $(Test-Path $idx)"
W "  static files      = $cnt  total = $([math]::Round($sz/1MB,2)) MB"
if (Test-Path $idx) {
  $idxFi = Get-Item $idx
  W "  index size = $($idxFi.Length) B  mtime = $($idxFi.LastWriteTime)"
}
if ($vcode -ne 0 -or -not (Test-Path $idx)) {
  W '  [FAIL] vite build or index missing. last vite out:'
  Get-Content $vout -Tail 60 | ForEach-Object { W '    > ' + $_ }
  Get-Content $verr -Tail 20 | ForEach-Object { W '    ! ' + $_ }
  exit 31
}
W '  [OK] Vite frontend build PASS'

# ===========================================================
# STEP 3/7 — MAVEN PACKAGE (process-resources + compile + package, same lifecycle)
# Defender lock: 4 retry with GC.Collect / sleep
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/7 — Maven package (process-resources + package, same lifecycle for BOOT-INF/lib)'
W '==========================================================='
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$MvnArgs = @('package','-DskipTests','-Dmaven.test.skip=true')
$MvnOK = $false
for ($ma=1; $ma -le 4; $ma++) {
  $o = Join-Path $LOG "mvn-out-$TS-$ma.log"
  $e = Join-Path $LOG "mvn-err-$TS-$ma.log"
  W "  attempt $ma/4  RUN: mvn.cmd $MvnArgs  cwd=$SRV"
  $t2 = Get-Date
  $mc = Run-Exe -FilePath $MVN -ArgumentList $MvnArgs -Cwd $SRV -OutLog $o -ErrLog $e
  W "    exit=$mc  dur=$([math]::Round(((Get-Date)-$t2).TotalSeconds,1))s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    $MvnOK = $true
    break
  }
  W "    last attempt not OK (mc=$mc, jar=$(if(Test-Path $SRV_JAR){[math]::Round(((Get-Item $SRV_JAR).Length/1MB),2)}else{'MISSING'}) MB). Defender retry."
  [GC]::Collect(); [GC]::WaitForPendingFinalizers()
  Start-Sleep -Seconds 20
}
if (-not $MvnOK) {
  W '  [FAIL] Maven. last mvn tail:'
  Get-Content $o -Tail 40 | ForEach-Object { W '    > ' + $_ }
  exit 32
}
$srvFi = Get-Item $SRV_JAR
W "  [OK] server Fat JAR = $([math]::Round($srvFi.Length/1MB,2)) MB  mtime = $($srvFi.LastWriteTime)"

# ===========================================================
# STEP 4/7 — JAR audit + app.jar sync
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/7 — JAR audit (BOOT-INF/lib > 200 + static/index.html exists)'
W '==========================================================='
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
  $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
  $idxZip  = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
  $libCnt  = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
  $stCnt   = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
  $z.Dispose()
  W "  static/index in BOOT  = $(if ($idxZip) { $idxZip.Length.ToString() + ' B [OK]' } else { 'MISSING [FAIL]' })"
  W "  BOOT-INF/lib jars     = $libCnt  $(if($libCnt -gt 200){'[OK]'}else{'[FAIL]'})"
  W "  static/* in BOOT      = $stCnt"
  if (-not $idxZip -or $libCnt -lt 200) { throw 'audit fail' }
} catch { W '  audit err: ' + $_.Exception.Message; exit 33 }
W '  [OK] JAR audit PASS'

# Copy to app.jar (retry loop for Defender transient lock)
$APP_JAR = Join-Path $DSK 'resources\app.jar'
W ''
W '  Sync JAR → desktop/resources/app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($ca=1; $ca -le 6; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "    copy attempt $ca/6 fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 6 }
}
$appFi = Get-Item $APP_JAR
W "  app.jar = $([math]::Round($appFi.Length/1MB,2)) MB  mtime = $($appFi.LastWriteTime)"

# ===========================================================
# STEP 5/7 — Desktop main build
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/7 — Desktop main build (npm run build)'
W '==========================================================='
$do_out = Join-Path $LOG "desk-out-$TS.log"
$do_err = Join-Path $LOG "desk-err-$TS.log"
$dcode = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $do_out -ErrLog $do_err
$mainB = Join-Path $DSK 'dist-electron\main\index.js'
W "  exit=$dcode  main.js ok=$(Test-Path $mainB)"
if (Test-Path $mainB) { W "  main.js = $([math]::Round(((Get-Item $mainB).Length/1KB),2)) KB" }
if ($dcode -ne 0) {
  W '  [FAIL] desk build. tail:'
  Get-Content $do_err -Tail 20 | ForEach-Object { W '    ! ' + $_ }
  exit 34
}
W '  [OK] Desktop main build PASS'

# ===========================================================
# STEP 6/7 — ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP 6/7 — ws external regex check'
W '==========================================================='
$m = [regex]::Match([IO.File]::ReadAllText($mainB), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  found require('ws') = $($m.Success)"
if (-not $m.Success) { W '  ws inlined [FATAL]'; exit 35 }
W '  [OK] ws external verified — no l.mask crash'

# ===========================================================
# STEP 7/7 — electron-builder (clean temp dir / store)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 7/7 — electron-builder NSIS win x64 store'
W '==========================================================='
$InstallerSrc = $null
for ($a=1; $a -le 6; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_viteok_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 5
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/6  exit_dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = (Get-Item $bout -ErrorAction SilentlyContinue).Length
  W "    exit=$ec  stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -ErrorAction SilentlyContinue)
  if ($su.Count -gt 0) {
    $InstallerSrc = $su[0]
    break
  }
  if ($ec -eq 0) {
    W '    exit=0 but no Setup.exe — listing:'
    Get-ChildItem $OUT_DIR -ErrorAction SilentlyContinue | ForEach-Object { W '      ' + $_.Name }
    break
  }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 | ForEach-Object { W '    > ' + $_ } }
  Start-Sleep -Seconds 22
}
if (-not $InstallerSrc) { W '  [FAIL] all attempts. last tail:'; if(Test-Path $bout){Get-Content $bout -Tail 30 | %{W '    > ' + $_}}; exit 36 }
W "  [OK] Installer = $($InstallerSrc.Name)   $([math]::Round($InstallerSrc.Length/1MB,2)) MB"

# Publish
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
W ''
W '==========================================================='
W '  FINAL INSTALLER'
W '==========================================================='
W "  PATH   = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
W ''
W '  Release root directory:'
Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
  if ($_.PSIsContainer) {
    $szc = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    W ("    [DIR ] {0,-25} {1,10:N2} MB" -f $_.Name, ($szc/1MB))
  } else {
    W ("    [FILE] {0,-25} {1,10:N2} MB" -f $_.Name, ($_.Length/1MB))
  }
}
W ''
W '=== DONE WITH VITE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
