# ULTRA-FAST STEP 6: electron-builder
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "S6X_$TS.log"
New-Item $LOG -ItemType Directory -Force | Out-Null
[IO.File]::WriteAllText($MAST, "=== S6X @ " + (Get-Date -F 'yyyy-MM-dd HH:mm:ss') + "`n")
function W { param($s) [IO.File]::AppendAllText($MAST, "$s`n") }

$EB    = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'
$NPM   = (Get-Command npm.cmd -EA 0).Source

$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE   = 'local'

$7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if (-not (Test-Path $7za) -and (Test-Path $7zaOrig)) {
  Copy-Item -Path $7zaOrig -Destination $7za -Force -EA SilentlyContinue
  W '  restored 7za.exe'
}

$OLD_REL = Join-Path $DSK 'release'
$OLD_WIN = Join-Path $OLD_REL 'win-unpacked'
$OLD_EXE = Get-ChildItem $OLD_REL -Filter '*Setup*.exe' -EA 0
if ($OLD_EXE) { foreach ($f in $OLD_EXE) { W "  Old: $($f.Name) mtime=$($f.LastWriteTime) size=$([math]::Round($f.Length/1MB,2))MB" } }

if (Test-Path $OLD_WIN) {
  $bak = Join-Path $OLD_REL "_bak_$TS"
  try { Move-Item -Path $OLD_WIN -Destination $bak -Force -EA Stop; W "  moved win-unpacked -> $bak" } catch { W "  WARN win-unpacked locked, moving skip move: $($_.Exception.Message)" }
}

$TMPBASE = Join-Path $env:TEMP ("_ebx_$TS")
New-Item -ItemType Directory -Force -Path $TMPBASE | Out-Null
W "  EB   = $(Test-Path $EB)"
W "  NPM  = $NPM"
W "  TMP  = $TMPBASE"

$SUCCESS = $false
$InstallerSrc = $null
for ($a=1; $a -le 7; $a++) {
  $OUT_DIR = Join-Path $TMPBASE ("att_$a")
  New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null
  $bout = Join-Path $LOG "s6x-out-$TS-$a.log"
  $berr = Join-Path $LOG "s6x-err-$TS-$a.log"
  [GC]::Collect(); Start-Sleep -Seconds 3
  W "attempt $a/7 OUT=$OUT_DIR"
  $argList = @('exec','--','electron-builder','--win','--x64','--publish=never','-c.compression=store',"-c.directories.output=$OUT_DIR")
  try {
    $p = Start-Process -FilePath $NPM -ArgumentList $argList -WorkingDirectory $DSK -Wait -PassThru -NoNewWindow -RedirectStandardOutput $bout -RedirectStandardError $berr
    $ec = $p.ExitCode
  } catch {
    W "  start err: $($_.Exception.Message)"
    $ec = 77
  }
  $bsz = (Get-Item $bout -EA 0).Length
  W "  exit=$ec stdout=$bsz B"
  $su = @(Get-ChildItem $OUT_DIR -Filter '*Setup*.exe' -Recurse -File -EA 0)
  if ($su.Count -gt 0 -and $ec -eq 0) {
    $InstallerSrc = $su[0]
    $SUCCESS = $true
    break
  }
  if (Test-Path $bout) { Get-Content $bout -Tail 15 | % { W "    > $_" } }
  if (Test-Path $berr) { if (Get-Item $berr -EA 0) { Get-Content $berr -Tail 8 | % { W "    ! $_" } } }
  Start-Sleep -Seconds 20
}

if (-not $SUCCESS -or -not $InstallerSrc) { W '  [FAIL]'; exit 72 }
$Dst = Join-Path $OLD_REL $InstallerSrc.Name
W "copy -> $Dst"
Copy-Item -Path $InstallerSrc.FullName -Destination $Dst -Force
$dstFi = Get-Item $Dst
$sha = (Get-FileHash $Dst -Algorithm SHA256).Hash
$up = Join-Path (Split-Path $InstallerSrc.FullName -Parent) 'win-unpacked'
$u = Get-ChildItem $up -Recurse -File -EA 0 | Measure-Object Length -Sum
W ''
W '===== RESULT ====='
W "EXE    = $($dstFi.FullName)"
W "SIZE   = $([math]::Round($dstFi.Length/1MB,2)) MB"
W "MTIME  = $($dstFi.LastWriteTime)"
W "SHA256 = $sha"
if ($u -and $u.Count) { W "UNPACK = $($u.Count) files  $([math]::Round($u.Sum/1MB,2)) MB" }
W '=== DONE @ ' + (Get-Date -F 'yyyy-MM-dd HH:mm:ss')
exit 0
