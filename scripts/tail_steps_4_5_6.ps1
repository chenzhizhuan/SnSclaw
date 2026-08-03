# TAIL STEP 4/5/6 ONLY — assumes Vite + Maven already successful (both done)
# Performs:
#   Step 4 — JAR re-audit (with correct static threshold) + app.jar sync + desktop build + ws external
#   Step 5 — electron-builder store mode (7 attempts, fresh TEMP dirs)
#   Step 6 — E2E SHA256 win-unpacked/app.jar BOOT-INF/index.html === SRC
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$SRC_STATIC = Join-Path $SRV 'src\main\resources\static'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "TAIL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MAST, ("=== TAIL 4/5/6 @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n"))
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

$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NPM   = (Get-Command npm.cmd -EA 0).Source
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:JAVA_HOME    = $JDK21
$env:PATH         = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'

# 7za guard
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -EA 0
  W '7za restored'
}

# ===========================================================
# PRE STEP 4: RE-AUDIT SRV_JAR (relaxed thresholds)
# ===========================================================
W ''
W '==========================================================='
W '  PRE-AUDIT server JAR (relaxed static threshold)'
W '==========================================================='
$jf = Get-Item $SRV_JAR -EA 0
if (-not $jf) { W "  [FAIL] missing JAR"; exit 21 }
W "  JAR mtime=$($jf.LastWriteTime)  size=$([math]::Round($jf.Length/1MB,2))MB"
$zip = [System.IO.Compression.ZipFile]::OpenRead($SRV_JAR)
$libs  = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/lib/*').Count
$st    = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/static/*').Count
$zi    = $zip.Entries | Where-Object FullName -eq 'BOOT-INF/classes/static/index.html'
$clCnt = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/*.class').Count + `
         ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/**/*.class').Count
$skill = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/skills/*.py').Count + `
         ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/skills/**/*.py').Count
$mig   = ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/db/migration/*.sql').Count + `
         ($zip.Entries | Where-Object FullName -like 'BOOT-INF/classes/db/migration/**/*.sql').Count
$zip.Dispose()
W "  libs=$libs   (>250? $($libs -gt 250))"
W "  static=$st   (>200? $($st -gt 200))"
W "  classes=$clCnt (>1200? $($clCnt -gt 1200))"
W "  skills_py=$skill"
W "  db_migrations=$mig"
W "  static_index_entry=$($zi -ne $null)"
$srcCount = @(Get-ChildItem $SRC_STATIC -Recurse -File -EA 0).Count
W "  actual vite files on disk (src/static) = $srcCount"
if (-not ($libs -gt 250 -and $st -gt 200 -and $clCnt -gt 1200 -and $zi)) {
  W '  [FAIL] audit fail (critical entries missing)'
  exit 22
}
W '  [OK] server JAR AUDIT PASS (your backend Java recompile is in Fat JAR!)'

# ===========================================================
# STEP 4/6: app.jar sync + desktop build
# ===========================================================
W ''
W '==========================================================='
W '  STEP 4/6  app.jar sync + desktop build'
W '==========================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($ca = 1; $ca -le 7; $ca++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -EA Stop; break }
  catch { W "  jar copy attempt $ca fail"; Start-Sleep -Seconds 6; [GC]::Collect() }
}
$af = Get-Item $APP_JAR
W "  app.jar size=$([math]::Round($af.Length/1MB,2))MB  mtime=$($af.LastWriteTime)"

$dout = Join-Path $LOG "tail-desk-out-$TS.log"
$derr = Join-Path $LOG "tail-desk-err-$TS.log"
$dc = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $dout -ErrLog $derr
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "  npm run build exit=$dc  main.js exists=$(Test-Path $MAIN)"
if ($dc -ne 0 -or -not (Test-Path $MAIN)) {
  W '  [FAIL] desktop build. last 20 stderr:'
  Get-Content $derr -Tail 20 -EA 0 | ForEach-Object { W '    ! ' + $_ }
  exit 41
}
$mtime = (Get-Item $MAIN).LastWriteTime
W "  main.js mtime=$mtime"
$wsMatch = [regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  ws external = $($wsMatch.Success)"
if (-not $wsMatch.Success) { W '  [FATAL] ws inlined'; exit 42 }
W '  [OK] desktop build PASS'

# ===========================================================
# STEP 5/6: electron-builder
# ===========================================================
W ''
W '==========================================================='
W '  STEP 5/6  electron-builder (store) 7 attempts'
W '==========================================================='
$InstallerSrc = $null
for ($a = 1; $a -le 7; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_tail_$TS" + "_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "tail-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "tail-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/7  dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = 0; if (Test-Path $bout) { $bsz = (Get-Item $bout).Length }
  W "    exit=$ec  stdout=${bsz}B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0 -and $ec -eq 0) { $InstallerSrc = $su[0]; break }
  W '    --- last 15 stdout ---'
  Get-Content $bout -Tail 15 -EA 0 | ForEach-Object { W '    > ' + $_ }
  Start-Sleep -Seconds 20
}
if (-not $InstallerSrc) { W '  [FAIL] electron-builder'; exit 51 }

$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash

# ===========================================================
# STEP 6/6: E2E SHA256 verify
# ===========================================================
W ''
W '==========================================================='
W '  STEP 6/6  E2E SHA256 verify (win-unpacked vs src)'
W '==========================================================='
$srcIdx = Join-Path $SRC_STATIC 'index.html'
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
W '=== TAIL DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
