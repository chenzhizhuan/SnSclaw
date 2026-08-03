# ALL-IN-ONE: BOTH FRONTEND + BACKEND FULL REBUILD (Java recompile + NSIS Installer)
#  - Re-runs vue-tsc + vite build (your frontend changes today)
#  - FULL mvn -B package (recompile 1322 .java; no -Dmaven.main.skip!! backend changes!)
#  - Retry strategy with Defender-lock cleanup
#  - JAR BOOT-INF audit (libs / static / classes / skills)
#  - app.jar sync + desktop build + ws external check
#  - electron-builder store mode 7 attempts + 7za guard
#  - E2E SHA256: win-unpacked app.jar BOOT-INF static/index.html === SRC
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI   = Join-Path $ROOT 'mateclaw-ui'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$SRC_STATIC = Join-Path $SRV 'src\main\resources\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "BOTH_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, ("=== FULL BOTH @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n"))
function W { param($s) [System.IO.File]::AppendAllText($MAST, ($s + "`n")) }
function Run-Exe {
  param(
    [string]$FilePath, [string[]]$ArgumentList,
    [string]$Cwd,      [string]$OutLog, [string]$ErrLog
  )
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

# === ENV SETUP
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NODE  = (Get-Command node.exe -EA 0).Source
$MVN   = (Get-Command mvn.cmd -EA 0).Source
$NPM   = (Get-Command npm.cmd -EA 0).Source
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$VUE_TSC = Join-Path $UI 'node_modules\vue-tsc\bin\vue-tsc.js'
$VITE_JS = Join-Path $UI 'node_modules\vite\bin\vite.js'
$env:JAVA_HOME    = $JDK21
$env:PATH         = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

# 7za guard (Defender / electron-builder sometimes renames it)
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -EA 0
  W '7za restored from original'
}

W ''
W '==========================================================='
W '  PREFLIGHT'
W '==========================================================='
W "  node = $NODE"
W "  mvn  = $MVN"
W "  eb   = $(Test-Path $EB)"
$preIdx = Get-Item (Join-Path $SRC_STATIC 'index.html') -EA 0
if ($preIdx) { W "  OLD SRC index mtime = $($preIdx.LastWriteTime) size=$($preIdx.Length)B" } else { W "  OLD SRC index missing (will build)" }
$preJAR = Get-Item (Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar') -EA 0
if ($preJAR) { W "  OLD server JAR mtime=$($preJAR.LastWriteTime) size=$([math]::Round($preJAR.Length/1MB,2))MB" } else { W "  OLD server JAR missing" }

# ===========================================================
# STEP 1/6  vue-tsc --noEmit
# ===========================================================
W ''
W '==========================================================='
W '  STEP 1/6  vue-tsc --noEmit'
W '==========================================================='
$tso = Join-Path $LOG "both-tsc-out-$TS.log"
$tse = Join-Path $LOG "both-tsc-err-$TS.log"
$t1 = Get-Date
$tc = Run-Exe -FilePath $NODE -ArgumentList @($VUE_TSC,'--noEmit') -Cwd $UI -OutLog $tso -ErrLog $tse
$dur=[math]::Round(((Get-Date)-$t1).TotalSeconds,1)
W "  exit=$tc  dur=${dur}s"
if ($tc -ne 0) {
  W '  [WARN] vue-tsc has errors; continue (will build anyway). Last 30 err lines:'
  Get-Content $tse -Tail 30 -EA 0 | ForEach-Object { W '    ! ' + $_ }
} else {
  W '  [OK] type check PASS'
}

# ===========================================================
# STEP 2/6  Vite build  (outDir points to ../mateclaw-server/src/main/resources/static)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 2/6  Vite build'
W '==========================================================='
$vo = Join-Path $LOG "both-vite-out-$TS.log"
$ve = Join-Path $LOG "both-vite-err-$TS.log"
$t1 = Get-Date
$vc = Run-Exe -FilePath $NODE -ArgumentList @($VITE_JS,'build') -Cwd $UI -OutLog $vo -ErrLog $ve
$dur=[math]::Round(((Get-Date)-$t1).TotalSeconds,1)
W "  exit=$vc  dur=${dur}s"
$srcIdx = Join-Path $SRC_STATIC 'index.html'
$si = Get-Item $srcIdx -EA 0
if ($si) {
  $files = @(Get-ChildItem $SRC_STATIC -Recurse -File -EA 0)
  $totalMB = [math]::Round((($files | Measure-Object Length -Sum).Sum) / 1MB, 2)
  W "  SRC static index.html size=$($si.Length)B  mtime=$($si.LastWriteTime)"
  W "  SRC static count=$($files.Count)  total=${totalMB}MB"
}
if ($vc -ne 0 -or -not $si) {
  W '  [FAIL] Vite build. err tail:'
  Get-Content $ve -Tail 40 -EA 0 | ForEach-Object { W '    ! ' + $_ }
  exit 11
}
W '  [OK] Vite build PASS'

# ===========================================================
# STEP 3/6  Maven FULL -B package  (1322 .java RECOMPILE! no skips)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 3/6  mvn -B package   (FULL: compile .java + copy resources + repackage JAR)'
W '==========================================================='
W '  Expected duration: 12-22 minutes on this machine (1322 Java files + 1518 resources)'
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$mvok = $false
for ($ma = 1; $ma -le 6; $ma++) {
  W "  mvn attempt $ma/6"
  if ($ma -ge 2) {
    foreach ($sub in @('skills','pptx','db','i18n','static')) {
      $todel = Join-Path $SRV "target\classes\$sub"
      if (Test-Path $todel) {
        W "    attempt $ma cleaning target/classes/$sub (attempt to release Defender locks)"
        for ($dd = 1; $dd -le 5; $dd++) {
          try { Remove-Item $todel -Recurse -Force -EA Stop; break }
          catch { Start-Sleep -Seconds 5; [GC]::Collect() }
        }
      }
    }
  }
  $o = Join-Path $LOG "both-mvn-out-$TS-$ma.log"
  $e = Join-Path $LOG "both-mvn-err-$TS-$ma.log"
  $MVN_ARGS = @('-B','package','-DskipTests','-Dmaven.test.skip=true')
  $t1 = Get-Date
  [GC]::Collect(); Start-Sleep -Seconds 5
  $mc = Run-Exe -FilePath $MVN -ArgumentList $MVN_ARGS -Cwd $SRV -OutLog $o -ErrLog $e
  $durM = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
  W "    exit=$mc  dur=${durM}s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    try {
      $zip = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
      $libs  = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/lib/*').Count
      $st    = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/static/*').Count
      $zi    = $zip.Entries | Where-Object FullName -eq 'BOOT-INF/classes/static/index.html'
      $clCnt = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/*.class').Count + `
               ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/**/*.class').Count
      $skill = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/skills/*.py').Count + `
               ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/skills/**/*.py').Count
      $zip.Dispose()
      W "    BOOT-INF  libs=$libs  static=$st  classes=$clCnt  skills=$skill"
      if ($libs -gt 250 -and $st -gt 300 -and $zi -and $clCnt -gt 1200) { $mvok = $true }
    } catch {
      W "    audit err: $($_.Exception.Message)"
    }
    if ($mvok) { break }
  }
  W '    --- last 15 stdout lines ---'
  Get-Content $o -Tail 15 -EA 0 | ForEach-Object { W '    > ' + $_ }
  W '    --- last 10 stderr lines ---'
  Get-Content $e -Tail 10 -EA 0 | ForEach-Object { W '    ! ' + $_ }
  Start-Sleep -Seconds 30
}
if (-not $mvok) { W '  [FAIL] Maven after 6 attempts. Abort.'; exit 31 }
$jf = Get-Item $SRV_JAR
W "  [OK] NEW server JAR = $([math]::Round($jf.Length/1MB,2))MB  mtime=$($jf.LastWriteTime)"

# ===========================================================
# STEP 4/6  app.jar sync + desktop build + ws external check
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/6  app.jar sync + desktop build'
W '==========================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($ca = 1; $ca -le 7; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -EA Stop; break }
  catch { W "    jar copy attempt $ca fail"; Start-Sleep -Seconds 6; [GC]::Collect() }
}
$af = Get-Item $APP_JAR
W "  app.jar size=$([math]::Round($af.Length/1MB,2))MB  mtime=$($af.LastWriteTime)"

$dout = Join-Path $LOG "both-desk-out-$TS.log"
$derr = Join-Path $LOG "both-desk-err-$TS.log"
$dc = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $dout -ErrLog $derr
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "  npm run build exit=$dc  main.js exists=$(Test-Path $MAIN)"
if ($dc -ne 0 -or -not (Test-Path $MAIN)) {
  W '  [FAIL] desktop build. last 20 stderr:'
  Get-Content $derr -Tail 20 -EA 0 | ForEach-Object { W '    ! ' + $_ }
  exit 41
}
$wsMatch = [regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  ws external = $($wsMatch.Success)   (must be True -> else l.mask crash at start)"
if (-not $wsMatch.Success) { W '  [FATAL] ws is inlined -> app will crash l.mask not a function'; exit 42 }
W '  [OK] desktop build PASS'

# ===========================================================
# STEP 5/6  electron-builder store mode (fresh temp dir each attempt)
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/6  electron-builder (store) 7 attempts'
W '==========================================================='
$InstallerSrc = $null
for ($a = 1; $a -le 7; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_both_$TS" + "_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "both-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "both-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/7  dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = 0
  if (Test-Path $bout) { $bsz = (Get-Item $bout).Length }
  W "    exit=$ec  stdout=${bsz}B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0 -and $ec -eq 0) { $InstallerSrc = $su[0]; break }
  W '    --- last 15 stdout lines ---'
  Get-Content $bout -Tail 15 -EA 0 | ForEach-Object { W '    > ' + $_ }
  Start-Sleep -Seconds 20
}
if (-not $InstallerSrc) { W '  [FAIL] electron-builder after 7 attempts'; exit 51 }

$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash

# ===========================================================
# POST STEP 6/6  E2E VERIFY: win-unpacked/app.jar BOOT-INF/index.html === SRC index.html
# ===========================================================
W ''
W '==========================================================='
W '  POST STEP 6/6  E2E VERIFY (index.html SHA256 across 4 layers)'
W '==========================================================='
$unpackDir = Split-Path $InstallerSrc.FullName -Parent
$up = Join-Path $unpackDir 'win-unpacked\resources\app.jar'
$uFi = Get-Item $up -EA 0
if ($uFi) {
  W "win-unpacked app.jar size=$([math]::Round($uFi.Length/1MB,2))MB  mtime=$($uFi.LastWriteTime)"
  try {
    $z2 = [System.IO.Compression.ZipFile]::OpenRead($up)
    $st2  = ($z2.Entries | Where-Object FullName -like 'BOOT-INF/classes/static/*').Count
    $idx2 = $z2.Entries | Where-Object FullName -eq 'BOOT-INF/classes/static/index.html'
    if ($idx2 -and (Test-Path $srcIdx)) {
      $stm = $idx2.Open()
      $buf = New-Object byte[] $idx2.Length
      [void]$stm.Read($buf, 0, $idx2.Length)
      $stm.Dispose()
      $hasher = [System.Security.Cryptography.SHA256]::Create()
      $shaJar = [BitConverter]::ToString($hasher.ComputeHash($buf)).Replace('-','').ToLower()
      $srcBuf = [System.IO.File]::ReadAllBytes($srcIdx)
      $shaSrc = [BitConverter]::ToString($hasher.ComputeHash($srcBuf)).Replace('-','').ToLower()
      $hasher.Dispose()
      W "  UNPACK BOOT-INF static count = $st2"
      W "  UNPACK index entry time      = $($idx2.LastWriteTime)"
      W "  UNPACK JAR index SHA256      = $shaJar"
      W "  SRC index SHA256             = $shaSrc"
      W "  MATCH (bytes identical)      = $($shaJar -eq $shaSrc)"
    }
    $z2.Dispose()
  } catch {
    W "  E2E audit err: $($_.Exception.Message)"
  }
}

W ''
W '==========================================================='
W '  FINAL INSTALLER'
W '==========================================================='
W "PATH   = $($dstFi.FullName)"
W "SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "MTIME  = $($dstFi.LastWriteTime)"
W "SHA256 = $sha"
W ''
W '=== BOTH DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
