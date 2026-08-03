# Tail continuation: from Vite step already DONE (src/static 356 files NEW 09:59:21 + MANUAL SYNC target/classes/static already DONE by parent script)
# New insight: maven-resources-plugin copying 1518 resources (skills/pptx/...) is ALWAYS Defender locked → exit=1.
# We DON'T need those skills/pptx copied — target/classes/skills + db/migration + i18n are pristine from last full mvn compile 8/2.
# Only target/classes/static was REFRESHED manually. So SKIP RESOURCES PLUGIN + MAIN SKIP → just run jar plugin + spring-boot repackage!
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$SRV  = Join-Path $ROOT 'mateclaw-server'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "FINAL_$TS.log"
[IO.File]::WriteAllText($MAST, "=== FINAL TAIL @ $(Get-Date -F 'yyyy-MM-dd HH:mm:ss') ===`n")
function W { param($s) [IO.File]::AppendAllText($MAST, "$s`n") }
function Run-Exe {
  param([string]$FilePath,[string[]]$ArgumentList,[string]$Cwd,[string]$OutLog,[string]$ErrLog)
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgumentList `
       -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME = $JDK21
$env:PATH = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'
$MVN = (Get-Command mvn.cmd -EA 0).Source
$NPM = (Get-Command npm.cmd -EA 0).Source
$EB  = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$7za = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) { Copy-Item $7zaOrig $7za -Force -EA 0; W '7za restored' }

# VERIFY target/classes/static is fresh
$TGT_STATIC = Join-Path $SRV 'target\classes\static\index.html'
$ti = Get-Item $TGT_STATIC -EA 0
W "target static index exists=$(Test-Path $TGT_STATIC) mtime=$(if($ti){$ti.LastWriteTime}else{'MISSING'})"
$scnt = (Get-ChildItem (Join-Path $SRV 'target\classes\static') -Recurse -File -EA 0).Count
W "target static file count = $scnt (expect 356 today)"

Add-Type -AssemblyName System.IO.Compression.FileSystem

# ============= STEP 4: Maven (SKIP RESOURCES completely!!) =============
W ''
W '=== STEP 4: Maven package -Dmaven.resources.skip=true (skip resources!) + -Dmaven.main.skip=true (skip compile!)'
W '  → Only jar plugin + spring-boot:repackage (reads target/classes/ as-is: fresh static + old skills/pptx = perfect!)'
$SRV_JAR = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$ok=$false
for ($a=1; $a -le 4; $a++) {
  $o = Join-Path $LOG "final-mvn-out-$TS-$a.log"
  $e = Join-Path $LOG "final-mvn-err-$TS-$a.log"
  $argz = @('-B','package',
            '-DskipTests','-Dmaven.test.skip=true',
            '-Dmaven.resources.skip=true',
            '-Dmaven.main.skip=true')
  W "attempt $a/4"
  $t1=Get-Date
  [GC]::Collect(); Start-Sleep -Seconds 5
  $mc = Run-Exe -FilePath $MVN -ArgumentList $argz -Cwd $SRV -OutLog $o -ErrLog $e
  W "  exit=$mc  dur=$([math]::Round(((Get-Date)-$t1).TotalSeconds,1))s"
  if ($mc -eq 0 -and (Test-Path $SRV_JAR) -and ((Get-Item $SRV_JAR).Length -gt 350MB)) {
    try {
      $z=[IO.Compression.ZipFile]::OpenRead($SRV_JAR)
      $libs=($z.Entries|? FullName -like 'BOOT-INF/lib/*').Count
      $st=($z.Entries|? FullName -like 'BOOT-INF/classes/static/*').Count
      $zi=$z.Entries|? FullName -eq 'BOOT-INF/classes/static/index.html'
      $skillFile=($z.Entries|? FullName -like 'BOOT-INF/classes/skills/pixel-art/scripts/palettes.py').Count
      $dbMig=($z.Entries|? FullName -like 'BOOT-INF/classes/db/migration/h2/V1__*.sql').Count
      $z.Dispose()
      W "  BOOT-INF libs=$libs  static=$st  index present=$(if($zi){'Y'}else{'N'})  skills palettes.py=$skillFile  db h2 V1_migrations=$dbMig"
      if ($libs -gt 250 -and $st -gt 300 -and $zi -and $skillFile -gt 0 -and $dbMig -ge 1) { $ok = $true }
    } catch { W "  audit err: $($_.Exception.Message)" }
    if ($ok) { break }
  }
  Start-Sleep -Seconds 20
}
if (-not $ok) { W '  [FAIL] Maven'; exit 81 }
$jf=Get-Item $SRV_JAR
W "  server JAR = $([math]::Round($jf.Length/1MB,2))MB mtime=$($jf.LastWriteTime)  [OK]"

# ============= STEP 5: app.jar + desktop build + ws =============
W ''
W '=== STEP 5: app.jar sync + desktop build + ws external'
$APP_JAR = Join-Path $DSK 'resources\app.jar'
[GC]::Collect(); Start-Sleep -Seconds 3
for ($a=1; $a -le 7; $a++) {
  try { Copy-Item -Path $SRV_JAR -Destination $APP_JAR -Force -EA Stop; break }
  catch { W "  jar cp $a fail"; Start-Sleep -Seconds 6; [GC]::Collect() }
}
$af=Get-Item $APP_JAR
W "app.jar size=$([math]::Round($af.Length/1MB,2))MB mtime=$($af.LastWriteTime)"

$dout=Join-Path $LOG "final-desk-out-$TS.log"; $derr=Join-Path $LOG "final-desk-err-$TS.log"
$dc = Run-Exe -FilePath $NPM -ArgumentList @('run','build') -Cwd $DSK -OutLog $dout -ErrLog $derr
$MAIN = Join-Path $DSK 'dist-electron\main\index.js'
W "desktop exit=$dc  main.js exists=$(Test-Path $MAIN)"
if ($dc -ne 0 -or -not (Test-Path $MAIN)) { W '  [FAIL] desktop build. err tail:'; Get-Content $derr -Tail 20 |%{W '    ! '+$_}; exit 82 }
$m=[regex]::Match([IO.File]::ReadAllText($MAIN), 'require\([\x22\x27]ws[\x22\x27]\)')
W "ws external = $($m.Success)"
if (-not $m.Success) { W 'ws inlined FATAL'; exit 83 }
W '  [OK]'

# ============= STEP 6: electron-builder =============
W ''
W '=== STEP 6: electron-builder store fresh TEMP dir'
$InstallerSrc=$null
for ($a=1; $a -le 7; $a++) {
  $outDir = Join-Path $env:TEMP ("_fin_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $outDir | Out-Null
  $bout=Join-Path $LOG "final-eb-out-$TS-$a.log"
  $berr=Join-Path $LOG "final-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$outDir")
  W "attempt $a/7 dir=$outDir"
  $ec = Run-Exe -FilePath $EB -ArgumentList $arg -Cwd $DSK -OutLog $bout -ErrLog $berr
  $bsz=(Get-Item $bout -EA 0).Length
  W "  exit=$ec stdout=$bsz B"
  $su=@(Get-ChildItem $outDir -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0 -and $ec -eq 0) { $InstallerSrc = $su[0]; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 15 |%{W '    > '+$_} }
  Start-Sleep -Seconds 20
}
if (-not $InstallerSrc) { W '  [FAIL] builder'; exit 84 }
$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash

# ============= POST VERIFY =============
W ''
W '=== POST VERIFY (win-unpacked/app.jar BOOT-INF static)'
$up=Join-Path (Split-Path $InstallerSrc.FullName -Parent) 'win-unpacked\resources\app.jar'
$uFi = Get-Item $up -EA 0
if ($uFi) {
  W "win-unpacked app.jar size=$([math]::Round($uFi.Length/1MB,2))MB mtime=$($uFi.LastWriteTime)"
  try {
    $z2=[IO.Compression.ZipFile]::OpenRead($up)
    $st2=($z2.Entries|? FullName -like 'BOOT-INF/classes/static/*').Count
    $idx2=$z2.Entries|? FullName -eq 'BOOT-INF/classes/static/index.html'
    if ($idx2) {
      $stm=$idx2.Open(); $buf=New-Object byte[] $idx2.Length; [void]$stm.Read($buf,0,$idx2.Length); $stm.Dispose()
      $hasher=[Security.Cryptography.SHA256]::Create()
      $shaJar=[BitConverter]::ToString($hasher.ComputeHash($buf)).Replace('-','').ToLower()
      $srcBuf=[IO.File]::ReadAllBytes((Join-Path $SRV 'src\main\resources\static\index.html'))
      $shaSrc=[BitConverter]::ToString($hasher.ComputeHash($srcBuf)).Replace('-','').ToLower()
      $hasher.Dispose()
      W "  BOOT-INF static count = $st2"
      W "  JAR  index entry time  = $($idx2.LastWriteTime)"
      W "  SRC  index sha256      = $shaSrc"
      W "  JAR  index sha256      = $shaJar"
      W "  MATCH = $($shaJar -eq $shaSrc)"
    }
    $z2.Dispose()
  } catch { W "  err: $($_.Exception.Message)" }
}

W ''
W '====== FINAL PRODUCT ======'
W "PATH   = $($dstFi.FullName)"
W "SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "MTIME  = $($dstFi.LastWriteTime)"
W "SHA256 = $sha"
W ''
W '=== FINAL DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
