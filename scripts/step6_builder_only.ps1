# Step 6 only: electron-builder (store mode, clean temp dir each attempt) then publish to release/
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "STEP6_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[IO.File]::WriteAllText($MAST, "=== STEP6 @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + " ===`n")
function W { param($s) [IO.File]::AppendAllText($MAST, "$s`n") }
$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

# 7za guard
$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) { Copy-Item -Path $7zaOrig -Destination $7za -Force -EA SilentlyContinue }
W "  EB exists = $(Test-Path $EB)"
W "  7za exists = $(Test-Path $7za)"

$APP_JAR = Join-Path $DSK 'resources\app.jar'
$fi = Get-Item $APP_JAR
W "  app.jar = $([math]::Round($fi.Length/1MB,2)) MB mtime=$($fi.LastWriteTime)"

$InstallerSrc = $null
for ($a=1; $a -le 8; $a++) {
  $OUT_DIR = Join-Path $env:TEMP ("_stp6_$TS`_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "step6-eb-out-$TS-$a.log"
  $berr = Join-Path $LOG "step6-eb-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 4
  $arg = @('--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  W "  attempt $a/8  dir=$OUT_DIR"
  $p = Start-Process -FilePath $EB -ArgumentList $arg -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow `
       -RedirectStandardOutput $bout -RedirectStandardError $berr
  $bsz = (Get-Item $bout -EA 0).Length
  W "    exit=$($p.ExitCode)  stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0) { $InstallerSrc = $su[0]; break }
  if (Test-Path $bout) { Get-Content $bout -Tail 20 |%{ W '    > '+$_ } }
  Start-Sleep -Seconds 24
}

if (-not $InstallerSrc) { W '  [FAIL] all attempts'; exit 71 }

$REL = Join-Path $DSK 'release'
New-Item -ItemType Directory -Force -Path $REL | Out-Null
$DstExe = Join-Path $REL $InstallerSrc.Name
Copy-Item -Path $InstallerSrc.FullName -Destination $DstExe -Force
$dstFi = Get-Item $DstExe
$sha = (Get-FileHash $DstExe -Algorithm SHA256).Hash
$u = Get-ChildItem (Join-Path $REL 'win-unpacked') -Recurse -File -EA 0 | Measure-Object Length -Sum

W ''
W '==========================================================='
W '  DONE STEP6'
W '==========================================================='
W "  EXE    = $($dstFi.FullName)"
W "  SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "  MTIME  = $($dstFi.LastWriteTime)"
W "  SHA256 = $sha"
if ($u -and $u.Sum) { W "  UNPACK = $(($u.Count)) files  $([math]::Round($u.Sum/1MB,2)) MB" }
W ''
W '=== STEP6 DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + ' ==='
exit 0
