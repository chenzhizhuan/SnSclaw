# =======================================================================
#  STEP 4/7 HELPER — Audit JAR contains static/index.html in BOOT-INF/classes
#  Returns exit 11 on fail, 0 on ok. Prints count.
# =======================================================================
param(
  [Parameter(Mandatory=$true)][string]$JarPath,
  [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
try {
  $idxEntry = $zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
  $allStatic = $zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }
  if (-not $idxEntry) {
    "[FAIL] JAR audit — static/index.html NOT present in BOOT-INF/classes/static/" | Tee-Object $LogPath
    "[INFO] Total entries in JAR: $($zip.Entries.Count)"                                         | Tee-Object $LogPath -Append
    exit 11
  }
  "[OK] JAR audit passed"                                                                         | Tee-Object $LogPath
  "     static/* entries = $($allStatic.Count)"                                                   | Tee-Object $LogPath -Append
  "     index.html size  = $($idxEntry.Length) B"                                                 | Tee-Object $LogPath -Append
  exit 0
} finally {
  $zip.Dispose()
}
