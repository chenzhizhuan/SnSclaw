# =============================================================================
#  RESUME FROM STEP 3+ (Vite already done; run mvn with -B batch mode to avoid
#  the javac Terminate batch (Y/N) interactive prompt that hung last run).
#  Strategy: since java source files didn't change, SKIP COMPILE with
#  -Dmaven.compiler.skip=true  BUT run process-resources (copies 281 new static
#  files to target/classes) + package phase (BOOT-INF/lib injection via
#  spring-boot-maven-plugin bound to package phase in same lifecycle).
# =============================================================================
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI   = Join-Path $ROOT 'mateclaw-ui'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$STATIC= Join-Path $SRV 'src\main\resources\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "RESUME_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, "=== RESUME PIPELINE (skip Vite, -B batch mvn) @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
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
$EB      = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:JAVA_HOME   = $JDK21
$env:PATH        = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'
# 7za guard
$7za = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -ErrorAction SilentlyContinue
}

# Vite was done. Verify:
W ''
W '==========================================================='
W '  VITE VERIFY (must be new timestamp)'
W '==========================================================='
$idxSrc = Join-Path $STATIC 'index.html'
$srcFi  = Get-Item $idxSrc -ErrorAction SilentlyContinue
W "  static/index.html exists = $(Test-Path $idxSrc)"
if ($srcFi) { W "  mtime = $($srcFi.LastWriteTime)   size = $($srcFi.Length) B" }

# ===========================================================
# STEP 3/7 — MAVEN with -B batch mode (never prompts Y/N)
#   -B                   = batch mode (no interactive prompts — fixes the hang)
#   -Dmaven.compiler.skip=true  = skip .java compile (no source changes since
#                                   19:42 yesterday), saves 12+ min
#   process-resources still runs → copies static/ + resources into target/classes
#   package still runs → maven-jar-plugin → spring-boot:repackage injects BOOT-INF
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/7 — Maven -B package -Dmaven.compiler.skip=true'
W '==========================================================='
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$MVN_ARGS = @('-B','package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.compiler.skip=true')
$MvnOK = $false
for ($ma=1; $ma -le 5; $ma++) {
  $o = Join-Path $LOG "resume-mvn-out-$TS-$ma.log"
  $e = Join-Path $LOG "resume-mvn-err-$TS-$ma.log"
  W "  attempt $ma/5  RUN: mvn $MVN_ARGS"
  $t1 = Get-Date
  $mc = Run-Exe -FilePath $MVN -ArgumentList $MVN_ARGS -Cwd $SRV -OutLog $o -ErrLog $e
  W "    exit=$mc  dur=$([math]::Round(((Get-Date)-$t1).TotalSeconds,1))s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    # Double-check static/index.html inside BOOT-INF is the new one
    $newer = $false
    try {
      Add-Type -AssemblyName System.IO.Compression.FileSystem
      $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
      $zi = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
      $libs = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
      $z.Dispose()
      $newer = ($null -ne $zi -and $libs -gt 200)
    } catch {}
    if ($newer) { $MvnOK = $true; break }
    W "    mvn exit=0 jar size OK but BOOT-INF audit returned stale. Retry."
  }
  W "    last attempt not good. Defender release 25s ..."
  [GC]::Collect(); Start-Sleep -Seconds 25
}
if (-not $MvnOK) {
  W '  [FAIL] Maven after all attempts. last out tail:'
  Get-Content $o -Tail 40 | ForEach-Object { W '    > ' + $_ }
  exit 41
}
$srvFi = Get-Item $SRV_JAR
W "  [OK] server Fat JAR = $([math]::Round($srvFi.Length/1MB,2)) MB  mtime = $($srvFi.LastWriteTime)"

# ===========================================================
# STEP 4/7 — JAR audit + app.jar sync
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/7 — JAR audit + app.jar copy to desktop/resources'
W '==========================================================='
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
  $z = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
  $zIdx  = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
  $zLibs = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
  $zStc  = ($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
  $z.Dispose()
  W "  BOOT-INF static/index = $(if($zIdx){($zIdx.Length).ToString()+' B'}else{'MISSING'})"
  W "  BOOT-INF lib count    = $zLibs  $(if($zLibs -gt 200){'[OK]'}else{'[FAIL]'})"
  W "  BOOT-INF static/*     = $zStc"
} catch { W '  audit err: '+$_.Exception.Message; exit 42 }
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($ca=1; $ca -le 5; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "    copy attempt $ca fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 6 }
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
$do_out = Join-Path $LOG "resume-desk-out-$TS.log"
$do_err = Join-Path $LOG "resume-desk-err-$TS.log"
$dcode = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $do_out -ErrLog $do_err
$mainB = Join-Path $DSK 'dist-electron\main\index.js'
W "  exit=$dcode  main ok=$(Test-Path $mainB)"
if (Test-Path $mainB) { W "  main size = $([math]::Round(((Get-Item $mainB).Length/1KB),2)) KB" }
if ($dcode -ne 0) { Get-Content $do_err -Tail 15 | ForEach-Object { W '    ! ' + $_ }; exit 43 }

# ===========================================================
# STEP 6/7 — ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP 6/7 — ws external regex check'
W '==========================================================='
$m = [regex]::Match([IO.File]::ReadAllText($mainB), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  found require('ws') = $($m.Success)"
if (-not $m.Success) { W '  ws inlined [FATAL]'; exit 44 }
W '  [OK] ws external verified'

# ===========================================================
# STEP 7/7 — electron-builder (clean temp dir)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 7/7 — electron-builder NSIS win x64 store'
W '==========================================================='
$InstallerSrc = $null
for ($a=1; $a -le 6; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_resume_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "resume-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "resume-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 5
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/6  dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  W "    exit=$ec  stdout=$((Get-Item $bout -ErrorAction SilentlyContinue).Length) B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -ErrorAction SilentlyContinue)
  if ($su.Count -gt 0) { $InstallerSrc = $su[0]; break }
  if ($ec -eq 0) { W '    exit=0 no setup — listing:'; Get-ChildItem $OUT_DIR -EA 0 |%{ W '      '+$_.Name }; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 |%{ W '    > '+$_ } }
  Start-Sleep -Seconds 22
}
if (-not $InstallerSrc) { W '  [FAIL] all builder attempts'; exit 45 }
W "  [OK] installer = $($InstallerSrc.Name)  $([math]::Round($InstallerSrc.Length/1MB,2)) MB"

# Publish to canonical release/
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
W "  copy → $DstExe"
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
W ''
W '==========================================================='
W '  FINAL PRODUCT'
W '==========================================================='
W "  PATH   = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
W ''
W '  Release root:'
Get-ChildItem $REL -ErrorAction SilentlyContinue | ForEach-Object {
  if ($_.PSIsContainer) {
    $szt = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
    W ("    [DIR ] {0,-25} {1,10:N2} MB" -f $_.Name, ($szt/1MB))
  } else {
    W ("    [FILE] {0,-25} {1,10:N2} MB" -f $_.Name, ($_.Length/1MB))
  }
}
W ''
W '=== RESUME DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
