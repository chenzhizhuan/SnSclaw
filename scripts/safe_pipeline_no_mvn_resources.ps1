# =============================================================================
#  ULTRA-SAFE PIPELINE (bypasses maven-resources-plugin completely)
#  Key insight: maven-resources-plugin copying 1443 tiny files (skills/pptx/...)
#  ALWAYS hits Defender lock FileSystemException. mvn clean/package takes 26+ min.
#  Solution:
#    (A) MANUAL COPY new Vite static/ from src → target/classes/static (only 281 files)
#    (B) RUN mvn -B package -Dmaven.main.skip=true  → NO process-resources, NO compile
#        → only jars together + spring-boot repackage in same lifecycle → BOOT-INF inject.
#    (C) audit → app.jar sync → desktop build → ws → electron-builder → publish
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
$MAST = Join-Path $LOG "SAFE_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== SAFE PIPELINE @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
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
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NODE  = (Get-Command node.exe -EA 0).Source
$MVN   = (Get-Command mvn.cmd -EA 0).Source
$NPM   = (Get-Command npm.cmd -EA 0).Source
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) { Copy-Item -Path $7zaOrig -Destination $7za -Force -EA SilentlyContinue }

W ''
W '==========================================================='
W '  PREFLIGHT'
W '==========================================================='
W "  node   = $NODE"
W "  mvn    = $MVN"
W "  npm    = $NPM"
W "  e-b    = $(Test-Path $EB)"
W "  7za    = $(Test-Path $7za)"
W "  SRC_STATIC index mtime = $((Get-Item (Join-Path $SRC_STATIC 'index.html') -EA 0).LastWriteTime)"
$cnt = 0
if (Test-Path $TGT_STATIC) { $cnt = (Get-ChildItem $TGT_STATIC -Recurse -File -EA 0).Count }
W "  TGT_STATIC old file count = $cnt"

# ===========================================================
# STEP (A) MANUAL SYNC: copy Vite-output static/ into target/classes/static
#    (bypass maven-resources-plugin 1443 files → Defender lock)
# ===========================================================
W ''
W '==========================================================='
W '  STEP (A)  MANUAL COPY src static → target/classes/static'
W '==========================================================='
[GC]::Collect()
if (Test-Path $TGT_STATIC) {
  W "  clearing old target/classes/static"
  # retry delete 5x for transient Defender locks
  for ($d=1; $d -le 6; $d++) {
    try {
      Remove-Item -Path $TGT_STATIC -Recurse -Force -ErrorAction Stop
      break
    } catch {
      W "    delete attempt $d/6 fail: $($_.Exception.Message)"
      [GC]::Collect()
      Start-Sleep -Seconds 5
    }
  }
}
New-Item -ItemType Directory -Force -Path $TGT_STATIC | Out-Null
W "  copy: $SRC_STATIC  →  $TGT_STATIC"
$tries = 0
$copyOK = $false
while ($tries++ -lt 5 -and -not $copyOK) {
  try {
    Copy-Item -Path (Join-Path $SRC_STATIC '*') -Destination $TGT_STATIC -Recurse -Force -ErrorAction Stop
    $copyOK = $true
  } catch {
    W "  copy attempt $tries fail: $($_.Exception.Message)"
    [GC]::Collect()
    Start-Sleep -Seconds 5
  }
}
if (-not $copyOK) { W '  [FAIL] static sync copy'; exit 51 }
$newCnt = (Get-ChildItem $TGT_STATIC -Recurse -File -EA 0).Count
$newIdxTime = (Get-Item (Join-Path $TGT_STATIC 'index.html') -EA 0).LastWriteTime
$totalBytes = (Get-ChildItem $TGT_STATIC -Recurse -File -EA 0 | Measure-Object Length -Sum).Sum
W "  copied $newCnt files  $([math]::Round($totalBytes/1MB,2)) MB"
W "  index.html new mtime = $newIdxTime"
if ($newCnt -ne 281) { W "  [WARN] expected 281 got $newCnt — check if Vite changed chunk count (OK if only chunks merged)" }

# ===========================================================
# STEP (B) MAVEN: -Dmaven.main.skip=true (skip process-resources + compile)
#    → ONLY maven-jar-plugin + spring-boot:repackage inject BOOT-INF/*
# ===========================================================
W ''
W '==========================================================='
W '  STEP (B)  mvn -B package -DskipTests -Dmaven.test.skip=true -Dmaven.main.skip=true'
W '==========================================================='
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$MvnOK = $false
for ($ma=1; $ma -le 4; $ma++) {
  $o = Join-Path $LOG "safe-mvn-out-$TS-$ma.log"
  $e = Join-Path $LOG "safe-mvn-err-$TS-$ma.log"
  W "  attempt $ma/4"
  $t1 = Get-Date
  [GC]::Collect(); Start-Sleep -Seconds 3
  $MVN_ARGS = @('-B','package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.main.skip=true')
  $mc = Run-Exe -FilePath $MVN -ArgumentList $MVN_ARGS -Cwd $SRV -OutLog $o -ErrLog $e
  W "    exit=$mc  dur=$([math]::Round(((Get-Date)-$t1).TotalSeconds,1))s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    # BOOT-INF audit inline
    try {
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
      $zI = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
      $zL = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
      $zS = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
      $z.Dispose()
      if ($zI -and $zL -gt 200) { $MvnOK = $true }
      W "    BOOT-INF static/index = $(if($zI){'OK'}else{'MISSING'}) libs=$zL static/*=$zS"
    } catch { W "    audit err: $($_.Exception.Message)" }
    if ($MvnOK) { break }
  }
  W "    not yet — Defender GC 30s"
  [GC]::Collect()
  Start-Sleep -Seconds 30
}
if (-not $MvnOK) { W '  [FAIL] Maven. last tail:'; Get-Content $o -Tail 30 | % { W '    > ' + $_ }; exit 52 }
$srvFi = Get-Item $SRV_JAR
W "  [OK] server Fat JAR = $([math]::Round($srvFi.Length/1MB,2)) MB  mtime=$($srvFi.LastWriteTime)"

# ===========================================================
# STEP (C) app.jar sync
# ===========================================================
W ''
W '==========================================================='
W '  STEP (C)  app.jar copy → desktop/resources/app.jar'
W '==========================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 2
for ($ca=1; $ca -le 6; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "    attempt $ca/6 fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 5 }
}
$appFi = Get-Item $APP_JAR
W "  app.jar now = $([math]::Round($appFi.Length/1MB,2)) MB  mtime=$($appFi.LastWriteTime)"

# ===========================================================
# STEP (D) desktop build
# ===========================================================
W ''
W '==========================================================='
W '  STEP (D)  npm run build → desktop main'
W '==========================================================='
$d_out = Join-Path $LOG "safe-desk-out-$TS.log"
$d_err = Join-Path $LOG "safe-desk-err-$TS.log"
$dcode = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $d_out -ErrLog $d_err
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "  exit=$dcode  main.js ok=$(Test-Path $MAIN)"
if (Test-Path $MAIN) { W "  main.js size = $([math]::Round(((Get-Item $MAIN).Length/1KB),2)) KB" }
if ($dcode -ne 0) { W '  [FAIL] desktop build. tail:'; Get-Content $d_err -Tail 20 |%{W '    ! '+$_}; exit 53 }

# ===========================================================
# STEP (E) ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP (E)  ws external require("ws") regex'
W '==========================================================='
$m = [regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  match = $($m.Success)"
if (-not $m.Success) { W '  ws inlined! FATAL'; exit 54 }
W '  [OK]'

# ===========================================================
# STEP (F) electron-builder NSIS (clean temp dir / store)
# ===========================================================
W ''
W '==========================================================='
W '  STEP (F)  electron-builder (clean temp dir store)'
W '==========================================================='
$InstallerSrc = $null
for ($a=1; $a -le 6; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_safe_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "safe-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "safe-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/6  dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = (Get-Item $bout -EA 0).Length
  W "    exit=$ec  stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0) { $InstallerSrc = $su[0]; break }
  if ($ec -eq 0) { W '    exit=0 no setup — listing:'; Get-ChildItem $OUT_DIR -EA 0 |%{ W '      '+$_.Name }; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 |%{ W '    > '+$_ } }
  Start-Sleep -Seconds 22
}
if (-not $InstallerSrc) { W '  [FAIL] builder exit all attempts'; exit 55 }
W "  [OK] installer = $($InstallerSrc.Name)  $([math]::Round($InstallerSrc.Length/1MB,2)) MB"

# Publish Setup.exe
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
W ''
W '==========================================================='
W '  FINAL PRODUCT (WITH NEW VITE FRONTEND)'
W '==========================================================='
W "  PATH   = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
W ''
W '  Release root:'
Get-ChildItem $REL -EA 0 | ForEach-Object {
  if ($_.PSIsContainer) {
    $sz = (Get-ChildItem $_.FullName -Recurse -File -EA 0 | Measure-Object Length -Sum).Sum
    W ("    [DIR ] {0,-25} {1,10:N2} MB" -f $_.Name, ($sz/1MB))
  } else {
    W ("    [FILE] {0,-25} {1,10:N2} MB" -f $_.Name, ($_.Length/1MB))
  }
}
W ''
W '=== SAFE PIPELINE DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
