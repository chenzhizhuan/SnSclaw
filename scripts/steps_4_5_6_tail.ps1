# Steps 4,5,6 only: JAR audit + app.jar copy + desktop build + ws + electron-builder
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "TAIL_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[IO.File]::WriteAllText($MAST, "=== TAIL pipeline @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
function W { param($s) [IO.File]::AppendAllText($MAST, "$s`n") }
function Run-Exe {
  param([string]$FilePath,[string[]]$ArgumentList,[string]$Cwd,[string]$OutLog,[string]$ErrLog)
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$NODE  = (Get-Command node.exe -EA 0).Source
$NPM   = (Get-Command npm.cmd -EA 0).Source
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:JAVA_HOME = $JDK21
$env:PATH = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) { Copy-Item -Path $7zaOrig -Destination $7za -Force -EA SilentlyContinue }
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$APP_JAR = Join-Path $DSK 'resources\app.jar'

# ============= STEP 4 JAR AUDIT =============
W ''
W '==========================================================='
W '  STEP 4  JAR BOOT-INF audit + app.jar sync'
W '==========================================================='
$bootLibOK = $false; $bootIdxOK = $false; $bootStaticCnt = 0
Add-Type -AssemblyName System.IO.Compression.FileSystem
[GC]::Collect(); Start-Sleep -Seconds 3
try {
  $z = [IO.Compression.ZipFile]::OpenRead($SRV_JAR)
  $libs = ($z.Entries | Where-Object FullName -like 'BOOT-INF/lib/*').Count
  $statics = ($z.Entries | Where-Object FullName -like 'BOOT-INF/classes/static/*').Count
  $idx = $z.Entries | Where-Object FullName -eq 'BOOT-INF/classes/static/index.html'
  $allClasses = ($z.Entries | Where-Object FullName -like 'BOOT-INF/classes/*.class').Count
  W "  BOOT-INF/lib jars = $libs (need >250)"
  W "  BOOT-INF/classes/static/* = $statics (need ~281)"
  W "  BOOT-INF/classes/static/index.html = $(if($idx){'PRESENT'}else{'MISSING'})"
  W "  BOOT-INF/classes/*.class count = $allClasses"
  if ($idx) {
    $entryEpoch = [int64](($idx.LastWriteTime.DateTime - [DateTime]'1970-01-01').TotalSeconds)
    $local = [DateTimeOffset]::FromUnixTimeSeconds($entryEpoch).LocalDateTime
    W "    ZIP entry lastWriteTime = $local"
  }
  if ($libs -gt 250) { $bootLibOK = $true }
  if ($statics -gt 250) { $bootStaticCnt = $statics }
  if ($idx) { $bootIdxOK = $true }
  $z.Dispose()
} catch { W "  AUDIT ERR: $($_.Exception.Message)" }
$jarFi = Get-Item $SRV_JAR
W "  JAR = $([math]::Round($jarFi.Length/1MB,2)) MB  mtime=$($jarFi.LastWriteTime)"
if (-not ($bootLibOK -and $bootIdxOK)) { W '  [FAIL] BOOT-INF audit'; exit 61 }
W '  [OK] BOOT-INF audit PASS'

# copy app.jar with retry
W '  copy server JAR -> desktop/resources/app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($a=1; $a -le 7; $a++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -ErrorAction Stop; break }
  catch { W "    copy attempt $a fail: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds 6 }
}
$appFi = Get-Item $APP_JAR
W "  app.jar = $([math]::Round($appFi.Length/1MB,2)) MB mtime=$($appFi.LastWriteTime)"

# ============= STEP 5 =============
W ''
W '==========================================================='
W '  STEP 5  Desktop npm run build + ws check'
W '==========================================================='
$dout = Join-Path $LOG "tail-desk-out-$TS.log"; $derr = Join-Path $LOG "tail-desk-err-$TS.log"
$dcode = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $dout -ErrLog $derr
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "  desktop exit=$dcode  main.js exists=$(Test-Path $MAIN)"
if ($dcode -ne 0 -or -not (Test-Path $MAIN)) { W '  [FAIL] desktop. err tail:'; Get-Content $derr -Tail 20 |%{ W '    ! '+$_ }; exit 62 }
$m = [regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "  ws external = $($m.Success)"
if (-not $m.Success) { W '  ws inlined FATAL'; exit 63 }
W '  [OK]'

# ============= STEP 6 =============
W ''
W '==========================================================='
W '  STEP 6  electron-builder (clean TEMP dir store mode)'
W '==========================================================='
$InstallerSrc = $null
for ($a=1; $a -le 7; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_tail_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "tail-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "tail-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/7 dir=$OUT_DIR"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz = (Get-Item $bout -EA 0).Length
  W "    exit=$ec  stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0) { $InstallerSrc = $su[0]; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 |%{ W '    > '+$_ } }
  Start-Sleep -Seconds 22
}
if (-not $InstallerSrc) { W '  [FAIL] builder'; exit 64 }

$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
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
W '=== TAIL DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
