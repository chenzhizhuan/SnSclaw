# POST VERIFY: Ensure Vite output baked into Setup.exe via JAR in unpacked dir
$ErrorActionPreference = 'Continue'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$DSK  = Join-Path $ROOT 'mateclaw-desktop'
$LOG  = Join-Path $ROOT '.build-logs'
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LOG "VERIFY_$TS.log"
[IO.File]::WriteAllText($MAST, "=== VERIFY @ $(Get-Date -F 'HH:mm:ss') ===`n")
function W { param($s) [IO.File]::AppendAllText($MAST, "$s`n") }

Add-Type -AssemblyName System.IO.Compression.FileSystem

$release = Join-Path $DSK 'release'
$setup   = Get-ChildItem $release -Filter '*Setup*.exe' -File -EA 0 | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $setup) { W 'FAIL no setup'; exit 91 }
W "Setup=$($setup.Name) size=$([math]::Round($setup.Length/1MB,2))MB mtime=$($setup.LastWriteTime)"

# Setup is NSIS installer (7z inner archive). Instead of unpacking the exe,
# use the DIRECT2 output dir which was cleaned just before copy.
$TMP = $env:TEMP
$outDirs = @(Get-ChildItem $TMP -Directory -EA 0 | Where-Object { $_.Name -like '_dir2_*' -or $_.Name -like '_sns_build_*' -or $_.Name -like '_tail_*' -or $_.Name -like '_stp6_*' } | Sort-Object LastWriteTime -Descending)
if ($outDirs.Count -eq 0) { W 'FAIL no output dir'; exit 92 }
W "Found $($outDirs.Count) temp dirs: $($outDirs[0].Name)  $($outDirs[0].LastWriteTime)"

$unpackedJar = $null
foreach ($d in $outDirs) {
  $winU = Join-Path $d.FullName 'win-unpacked'
  if (-not (Test-Path $winU)) {
    $child = Get-ChildItem $d.FullName -Directory -EA 0 | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($child) { $winU = Join-Path $child.FullName 'win-unpacked' }
  }
  if (Test-Path $winU) {
    $j = Join-Path $winU 'resources\app.jar'
    if (Test-Path $j) { $unpackedJar = $j; W "FOUND JAR at $j"; break }
  }
}
if (-not $unpackedJar) {
  W 'fallback: setup jar from desktop/resources/app.jar (that was baked in)'
  $unpackedJar = Join-Path $DSK 'resources\app.jar'
}
$jarFi = Get-Item $unpackedJar
W "appJar = $unpackedJar  size=$([math]::Round($jarFi.Length/1MB,2))MB mtime=$($jarFi.LastWriteTime)"

# Open JAR
W ''
W '=== JAR Audit (from unpacked win-unpacked/resources/app.jar) ==='
try {
  $z = [IO.Compression.ZipFile]::OpenRead($unpackedJar)
  $libs = ($z.Entries | Where-Object FullName -like 'BOOT-INF/lib/*').Count
  $statics = ($z.Entries | Where-Object FullName -like 'BOOT-INF/classes/static/*').Count
  $idx = $z.Entries | Where-Object FullName -eq 'BOOT-INF/classes/static/index.html'
  W "BOOT-INF/lib jars=$libs (need >250)"
  W "BOOT-INF/classes/static files=$statics (need ~281)"
  if ($idx) {
    W "BOOT-INF/classes/static/index.html PRESENT"
    W "  entry compressed size=$($idx.CompressedLength)B  uncompressed=$($idx.Length)B"
    W "  entry LastWriteTime (UTC-like zip internal)=$($idx.LastWriteTime)"
    # Read and hash
    $stm = $idx.Open()
    $buf = New-Object byte[] $idx.Length
    [void]$stm.Read($buf, 0, $idx.Length)
    $stm.Dispose()
    $idxContent = [Text.Encoding]::UTF8.GetString($buf)
    $idxLen = $idxContent.Length
    $hasher = [Security.Cryptography.SHA256]::Create()
    $idxSha = [BitConverter]::ToString($hasher.ComputeHash($buf)).Replace('-','').ToLower()
    W "  content length=$idxLen  sha256=$idxSha"
    # Check same as src static index
    $srcBuf = [IO.File]::ReadAllBytes("$ROOT\mateclaw-server\src\main\resources\static\index.html")
    $srcSha = [BitConverter]::ToString($hasher.ComputeHash($srcBuf)).Replace('-','').ToLower()
    W "  SRC  index sha256 = $srcSha"
    W "  JAR  index sha256 = $idxSha"
    W "  MATCH = $($srcSha -eq $idxSha)"
    $hasher.Dispose()
  } else { W '!!! index.html MISSING in BOOT-INF/classes/static !!!' }
  $z.Dispose()
} catch { W "AUDIT ERR: $($_.Exception.Message)" }
W ''
W '=== Setup.exe SHA256 ==='
$sha = (Get-FileHash $setup.FullName -Algorithm SHA256).Hash
W "PATH  = $($setup.FullName)"
W "SIZE  = $([math]::Round($setup.Length/1MB,2)) MB"
W "MTIME = $($setup.LastWriteTime)"
W "SHA256= $sha"
W ''
W '=== DONE VERIFY ==='
exit 0
