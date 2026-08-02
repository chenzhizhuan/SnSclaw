# =======================================================================
#  STEP 5/7 HELPER — Verify dist-electron/main/index.js has ws external
#  Required to prevent: "TypeError: l.mask is not a function" at runtime.
#  Exit 12 on fail, exit 0 on ok.
# =======================================================================
param(
  [Parameter(Mandatory=$true)][string]$MainJs,
  [Parameter(Mandatory=$true)][string]$LogPath
)
$ErrorActionPreference='Stop'
if (-not (Test-Path $MainJs)) {
  "[FAIL] dist-electron/main/index.js not found at $MainJs" | Tee-Object $LogPath
  exit 13
}
$raw = Get-Content $MainJs -Raw
# We need require("ws") / require('ws') literally present — proves vite rollupOptions.external kept it as real require().
if ($raw -match 'require\([\x22\x27]ws[\x22\x27]\)') {
  "[OK] ws external verified: dist-electron/main/index.js calls require(`"ws`")" | Tee-Object $LogPath
  "[INFO] Main bundle size = $((Get-Item $MainJs).Length) B"                       | Tee-Object $LogPath -Append
  exit 0
}
"[FAIL] ws NOT externalized in $MainJs"                                           | Tee-Object $LogPath
"       Main process will throw 'TypeError: l.mask is not a function' at runtime." | Tee-Object $LogPath -Append
exit 12
