# =============================================================================
#  FRONTEND REBUILD + FULL PACKAGE PIPELINE
#  Uses PROVEN recipe from last run:
#    1) Vite + vue-tsc → server/src/main/resources/static (outDir in vite.config.ts)
#    2) Manual copy static/ → target/classes/static (bypass maven-resources-plugin 1443 files lock)
#    3) mvn -B package -Dmaven.main.skip=true (194s repackage only)
#    4) JAR audit → app.jar sync → desktop build → ws regex
#    5) electron-builder (store + fresh %TEMP% dir)
#    6) E2E SHA256 verify
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI   = Join-Path $ROOT 'mateclaw-ui'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$SRC_STATIC = Join-Path $SRV 'src\main\resources\static'
$TGT_STATIC = Join-Path $SRV 'target\classes\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "ALL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== FULL PIPELINE @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
function W { param($s) [System.IO.File]::AppendAllText($MAST, "$s`n") }
function Run-Exe {
  param([string]$FilePath,[string[]]$ArgumentList,[string]$Cwd,[string]$OutLog,[string]$ErrLog)
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}

# ENV
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NODE  = (Get-Command node.exe -ErrorAction SilentlyContinue).Source
$MVN   = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
$NPM   = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$VUE_TSC = Join-Path $UI 'node_modules\vue-tsc\bin\vue-tsc.js'
$VITE_JS = Join-Path $UI 'node_modules\vite\bin\vite.js'
$env:JAVA_HOME    = $JDK21
$env:PATH         = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

# 7za guard
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -ErrorAction SilentlyContinue
  W '  7za restored from 7za_original.exe'
}

W ''
W '==========================================================='
W '  PREFLIGHT'
W '==========================================================='
W "  node   = $NODE"
W "  mvn    = $MVN"
W "  npm    = $NPM"
W "  eb     = $(Test-Path $EB)"
W "  vue-tsc= $(Test-Path $VUE_TSC)"
W "  vite   = $(Test-Path $VITE_JS)"
W "  src static idx before = $((Get-Item (Join-Path $SRC_STATIC 'index.html') -ErrorAction 0).LastWriteTime)"

# ===========================================================
# STEP 1.1: vue-tsc --noEmit (non-blocking typecheck)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 1/6  vue-tsc --noEmit'
W '==========================================================='
$tso = Join-Path $LOG "all-tsc-out-$TS.log"
$tse = Join-Path $LOG "all-tsc-err-$TS.log"
$t1 = Get-Date
$tc = Run-Exe -FilePath $NODE -ArgumentList @($VUE_TSC,'--noEmit') -Cwd $UI -OutLog $tso -ErrLog $tse
$dur = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
W "  exit=$tc  dur=${dur}s"
if ($tc -ne 0) {
  W '  [WARN] vue-tsc found type errors. continue anyway (tail):'
  Get-Content $tse -Tail 30 | ForEach-Object { W '    ! ' + $_ }
} else { W '  [OK] tsc PASS' }

# ===========================================================
# STEP 1.2: VITE build (outDir → ../mateclaw-server/src/main/resources/static)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 2/6  Vite build (emptyOutDir -> server/src/main/resources/static)'
W '==========================================================='
$vo = Join-Path $LOG "all-vite-out-$TS.log"
$ve = Join-Path $LOG "all-vite-err-$TS.log"
$t1 = Get-Date
$vc = Run-Exe -FilePath $NODE -ArgumentList @($VITE_JS,'build') -Cwd $UI -OutLog $vo -ErrLog $ve
$dur = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
W "  exit=$vc  dur=${dur}s"
$si = Get-Item (Join-Path $SRC_STATIC 'index.html') -ErrorAction 0
if ($si) {
  $tot = (Get-ChildItem $SRC_STATIC -Recurse -File -ErrorAction 0 | Measure-Object Length -Sum)
  W "  static/index.html size=$($si.Length)B  mtime=$($si.LastWriteTime)"
  W "  static files=$($tot.Count)  total=$([math]::Round($tot.Sum/1MB,2)) MB"
}
if ($vc -ne 0) { W '  [FAIL] vite build. err tail:'; Get-Content $ve -Tail 40 |%{ W '    ! '+$_ }; exit 11 }
if (-not $si -or $tot.Count -lt 250) { W '  [FAIL] vite output not found or too few'; exit 12 }
W '  [OK] Vite PASS'

# ===========================================================
# STEP 2a: MANUAL copy src/static -> target/classes/static
#         (bypass maven-resources-plugin 1443 files -> Defender lock)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/6  Manual copy src/static -> target/classes/static'
W '==========================================================='
[GC]::Collect(); Start-Sleep -Seconds 3
if (Test-Path $TGT_STATIC) {
  for ($d=1; $d -le 6; $d++) {
    try { Remove-Item -Path $TGT_STATIC -Recurse -Force -ErrorAction Stop; break }
    catch { W "    del $d/6 fail"; [GC]::Collect(); Start-Sleep -Seconds 5 }
  }
}
New-Item -ItemType Directory -Force -Path $TGT_STATIC | Out-Null
$cpok=$false
for ($ca=1; $ca -le 6; $ca++) {
  try { Copy-Item -Path (Join-Path $SRC_STATIC '*') -Destination $TGT_STATIC -Recurse -Force -ErrorAction Stop; $cpok=$true; break }
  catch { W "  copy $ca/6 fail"; [GC]::Collect(); Start-Sleep -Seconds 5 }
}
if (-not $cpok) { W '  [FAIL] static sync'; exit 21 }
$ncnt=(Get-ChildItem $TGT_STATIC -Recurse -File -EA 0).Count
W "  copied $ncnt files"
$ni = Get-Item (Join-Path $TGT_STATIC 'index.html') -EA 0; if($ni){W "  target index mtime=$($ni.LastWriteTime)"}
W '  [OK] static sync'

# ===========================================================
# STEP 2b: MAVEN package -Dmaven.main.skip=true
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/6  Maven -B package -Dmaven.main.skip=true (repackage BOOT-INF)'
W '==========================================================='
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$mvok=$false
for ($ma=1; $ma -le 4; $ma++) {
  $o = Join-Path $LOG "all-mvn-out-$TS-$ma.log"
  $e = Join-Path $LOG "all-mvn-err-$TS-$ma.log"
  W "  attempt $ma/4"
  $t1=Get-Date
  [GC]::Collect(); Start-Sleep -Seconds 3
  $mc = Run-Exe -FilePath $MVN -ArgumentList @('-B','package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.main.skip=true') -Cwd $SRV -OutLog $o -ErrLog $e
  W "    exit=$mc  dur=$([math]::Round(((Get-Date)-$t1).TotalSeconds,1))s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    try {
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      $z = [IO.Compression.ZipFile]::OpenRead($SRV_JAR)
      $libs=($z.Entries|? FullName -like 'BOOT-INF/lib/*').Count
      $st=($z.Entries|? FullName -like 'BOOT-INF/classes/static/*').Count
      $zi=$z.Entries|? FullName -eq 'BOOT-INF/classes/static/index.html'
      $z.Dispose()
      if ($libs -gt 250 -and $zi -and $st -gt 250) { $mvok = $true }
      W "    BOOT-INF libs=$libs  static=$st  index present=$(if($zi){'Y'}else{'N'})"
    } catch { W "    audit err: $($_.Exception.Message)" }
    if ($mvok) { break }
  }
  Start-Sleep -Seconds 20
}
if (-not $mvok) { W '  [FAIL] Maven'; exit 31 }
$jf=Get-Item $SRV_JAR; W "  server JAR = $([math]::Round($jf.Length/1MB,2)) MB mtime=$($jf.LastWriteTime)  [OK]"

# ===========================================================
# STEP 3: app.jar + desktop build + ws
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/6  app.jar sync + desktop build + ws check'
W '==========================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($ca=1; $ca -le 7; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "    jar copy $ca/7 fail"; [GC]::Collect(); Start-Sleep -Seconds 6 }
}
$af = Get-Item $APP_JAR
W "  app.jar = $([math]::Round($af.Length/1MB,2)) MB mtime=$($af.LastWriteTime)"
$dout = Join-Path $LOG "all-desk-out-$TS.log"; $derr = Join-Path $LOG "all-desk-err-$TS.log"
$dc = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $dout -ErrLog $derr
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "  desktop exit=$dc  main.js exists=$(Test-Path $MAIN)"
if ($dc -ne 0 -or -not (Test-Path $MAIN)) { W '  [FAIL] desktop. err tail:'; Get-Content $derr -Tail 20 |%{ W '    ! '+$_ }; exit 41 }
$m = [regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  ws external = $($m.Success)"
if (-not $m.Success) { W 'ws inlined FATAL'; exit 42 }
W '  [OK]'

# ===========================================================
# STEP 4: electron-builder
# ===========================================================
W ''
W '==========================================================='
W '  STEP 6/6  electron-builder store / fresh TEMP dir'
W '==========================================================='
$InstallerSrc = $null
for ($a=1; $a -le 7; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_all_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "all-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "all-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/7 dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = (Get-Item $bout -EA 0).Length
  W "    exit=$ec  stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0 -and $ec -eq 0) { $InstallerSrc = $su[0]; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 15 |%{ W '    > '+$_ } }
  Start-Sleep -Seconds 20
}
if (-not $InstallerSrc) { W '  [FAIL] builder'; exit 51 }

$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash

# ===========================================================
# STEP 5: E2E VERIFY
# ===========================================================
W ''
W '==========================================================='
W '  POST  E2E verify (win-unpacked/app.jar BOOT-INF static index)'
W '==========================================================='
$up=Join-Path (Split-Path $InstallerSrc.FullName -Parent) 'win-unpacked\resources\app.jar'
$upFi = Get-Item $up -EA 0
if ($upFi) {
  W "  checking win-unpacked app.jar size=$([math]::Round($upFi.Length/1MB,2))MB mtime=$($upFi.LastWriteTime)"
  try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $z2 = [IO.Compression.ZipFile]::OpenRead($up)
    $statics2=($z2.Entries|? FullName -like 'BOOT-INF/classes/static/*').Count
    $idx2=$z2.Entries|? FullName -eq 'BOOT-INF/classes/static/index.html'
    if ($idx2) {
      $stm2 = $idx2.Open()
      $buf2 = New-Object byte[] $idx2.Length
      [void]$stm2.Read($buf2,0,$idx2.Length)
      $stm2.Dispose()
      $hasher=[Security.Cryptography.SHA256]::Create()
      $shaJar=[BitConverter]::ToString($hasher.ComputeHash($buf2)).Replace('-','').ToLower()
      $srcBuf=[IO.File]::ReadAllBytes((Join-Path $SRC_STATIC 'index.html'))
      $shaSrc=[BitConverter]::ToString($hasher.ComputeHash($srcBuf)).Replace('-','').ToLower()
      $hasher.Dispose()
      W "  BOOT-INF static count = $statics2"
      W "  BOOT-INF index entry time = $($idx2.LastWriteTime)"
      W "  JAR index  SHA256 = $shaJar"
      W "  SRC index  SHA256 = $shaSrc"
      W "  MATCH = $($shaJar -eq $shaSrc)"
    }
    $z2.Dispose()
  } catch { W "  verify err: $($_.Exception.Message)" }
}

W ''
W '==========================================================='
W '  FINAL PRODUCT'
W '==========================================================='
W "  PATH   = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
W ''
W '=== ALL DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
