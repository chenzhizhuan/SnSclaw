$ErrorActionPreference = 'Continue'

$ROOT    = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$UI      = Join-Path $ROOT 'mateclaw-ui'
$SRV     = Join-Path $ROOT 'mateclaw-server'
$DSK     = Join-Path $ROOT 'mateclaw-desktop'
$LOGS    = Join-Path $ROOT '.build-logs'
$SCRIPTS = Join-Path $ROOT 'scripts'
$STATIC  = Join-Path $SRV  'src\main\resources\static'
$RELEASE = Join-Path $DSK  'release_local_final'
$MASTER  = Join-Path $LOGS ('MASTER_PS_' + [int][double](Get-Date -UFormat %s) + '.log')

New-Item -ItemType Directory -Force -Path $LOGS,$SCRIPTS | Out-Null
Remove-Item (Join-Path $LOGS '*.log') -Force -ErrorAction SilentlyContinue
Set-Location $ROOT

function Write-Tee([string]$msg) { Write-Host $msg; Add-Content -Path $MASTER -Value $msg -Encoding UTF8 -ErrorAction SilentlyContinue }
function Write-Step([string]$msg) {
  $hr = '=' * 78
  Write-Tee ''
  Write-Tee $hr
  Write-Tee "  $msg  @ $(Get-Date -Format 'HH:mm:ss')"
  Write-Tee $hr
}
function Append-Log([string]$file,[int]$tail=40) {
  if (Test-Path $file) {
    $lines = Get-Content $file -Tail $tail -ErrorAction SilentlyContinue
    foreach ($l in $lines) { Write-Tee $l }
  }
}
function Run-Exe([string]$exe,[string[]]$ArgList,[string]$wd,[string]$out,[string]$err) {
  # NOTE: parameter intentionally named $ArgList (NOT $args) to avoid colliding
  # with PowerShell's built-in automatic $args variable — collision caused the
  # entire argument array to become $null, producing STEP1 node.exe with NO args.
  Write-Tee "    Running: $exe $($ArgList -join ' ')"
  Write-Tee "    cwd    : $wd"
  $p = Start-Process -FilePath $exe -ArgumentList $ArgList -WorkingDirectory $wd `
       -PassThru -Wait -NoNewWindow -RedirectStandardOutput $out -RedirectStandardError $err
  Write-Tee "    exit=$($p.ExitCode)"
  return $p
}

Write-Tee "MAIN PIPELINE start @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Tee "ROOT    = $ROOT"
Write-Tee "UI      = $UI"
Write-Tee "SRV     = $SRV"
Write-Tee "DSK     = $DSK"
Write-Tee "RELEASE = $RELEASE"
Write-Tee "LOG     = $MASTER"
Write-Tee $("=" * 78)

# PREFLIGHT
Write-Tee ''
Write-Tee '[PRE] Path sanity check'
$paths = [ordered]@{
  'mateclaw-ui dir'          = $UI
  'vite entry JS'            = Join-Path $UI 'node_modules\vite\bin\vite.js'
  'server pom.xml'           = Join-Path $SRV 'pom.xml'
  'desktop package.json'     = Join-Path $DSK 'package.json'
  'desktop resources dir'    = Join-Path $DSK 'resources'
  'step4 audit script'       = Join-Path $SCRIPTS 'step4_audit_jar.ps1'
  'step5 ws check script'    = Join-Path $SCRIPTS 'step5_check_ws_external.ps1'
}
foreach ($k in $paths.Keys) {
  $v = $paths[$k]
  if (-not (Test-Path $v)) { Write-Tee "[FAIL] Missing $k  [$v]"; pause; exit 90 }
  Write-Tee "    OK  $k"
}
$env:NODE_OPTIONS            = '--max-old-space-size=8192'
$env:BUILD_MODE              = 'local'
$env:ELECTRON_CACHE          = Join-Path $ROOT '.cache\electron'
$env:ELECTRON_BUILDER_CACHE  = Join-Path $ROOT '.cache\electron-builder'
New-Item -ItemType Directory -Force -Path $env:ELECTRON_CACHE,$env:ELECTRON_BUILDER_CACHE | Out-Null
Write-Tee '[PRE] OK  paths + env ready'

# STEP 1 VITE
Write-Step 'STEP 1/6 VITE BUILD FRONTEND'
Remove-Item (Join-Path $UI 'dist') -Recurse -Force -ErrorAction SilentlyContinue
$outL = Join-Path $LOGS '01-vite-out.log'
$errL = Join-Path $LOGS '01-vite-err.log'
$viteJS = Join-Path $UI 'node_modules\vite\bin\vite.js'
$p = Run-Exe 'node.exe' @($viteJS,'build') $UI $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 60; Write-Tee '!! STEP1 FAIL'; pause; exit 11 }
$idx = Join-Path $UI 'dist\index.html'
if (-not (Test-Path $idx)) { Write-Tee '!! STEP1 FAIL no dist\index.html'; pause; exit 12 }
$f = Get-ChildItem (Join-Path $UI 'dist') -Recurse -File
Write-Tee "[OK] Frontend files=$($f.Count); total=$([math]::Round(($f|Measure-Object Length -Sum).Sum/1MB,2)) MB"

# STEP 2 COPY
Write-Step 'STEP 2/6 COPY DIST -> classpath:static'
Remove-Item $STATIC -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $STATIC | Out-Null
Copy-Item (Join-Path $UI 'dist\*') -Destination $STATIC -Recurse -Force
Start-Sleep -Milliseconds 300
if (-not (Test-Path (Join-Path $STATIC 'index.html'))) { Write-Tee '!! STEP2 FAIL index.html not copied'; pause; exit 21 }
Write-Tee '[OK] static/ populated'

# STEP 3 MVN PACKAGE
Write-Step 'STEP 3/6 MVN CLEAN PACKAGE'
$outL = Join-Path $LOGS '03-mvn-out.log'
$errL = Join-Path $LOGS '03-mvn-err.log'
$p = Run-Exe 'mvn.cmd' @('clean','package','-DskipTests','-Dmaven.test.skip=true','-q') $SRV $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 80; Write-Tee '!! STEP3 FAIL mvn'; pause; exit 31 }
$JAR = Get-ChildItem (Join-Path $SRV 'target') -Filter 'mateclaw-server-*.jar' -File |
       Where-Object { $_.Name -notmatch 'sources|javadoc|original' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $JAR) { Write-Tee '!! STEP3 FAIL JAR missing in target\'; pause; exit 32 }
Write-Tee "[OK] JAR = $($JAR.FullName)  Size=$([math]::Round($JAR.Length/1MB,2)) MB"

# STEP 4 AUDIT JAR + COPY APP.JAR
Write-Step 'STEP 4/6 AUDIT JAR (no more JSON 404 on root /)'
$outL = Join-Path $LOGS '04-audit.log'
$auditPs1 = Join-Path $SCRIPTS 'step4_audit_jar.ps1'
$p = Run-Exe 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',$auditPs1,
        '-JarPath',$JAR.FullName,
        '-LogPath',$outL) $SRV $outL $outL
Append-Log $outL 20
if ($p.ExitCode -ne 0) { Write-Tee '!! STEP4 FAIL JAR AUDIT'; pause; exit 41 }
Copy-Item $JAR.FullName (Join-Path $DSK 'resources\app.jar') -Force
Write-Tee '[OK] app.jar -> desktop\resources copied'

# STEP 5 DESKTOP BUILD + WS EXTERNAL CHECK
Write-Step 'STEP 5/6 DESKTOP SHELL BUILD'
$outL = Join-Path $LOGS '05-desktop-out.log'
$errL = Join-Path $LOGS '05-desktop-err.log'
$p = Run-Exe 'npm.cmd' @('run','build') $DSK $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 60; Write-Tee '!! STEP5 FAIL desktop build'; pause; exit 51 }
$MAINJS = Join-Path $DSK 'dist-electron\main\index.js'
if (-not (Test-Path $MAINJS)) { Write-Tee '!! STEP5 FAIL main/index.js missing'; pause; exit 52 }
$outL2 = Join-Path $LOGS '05-ws-check.log'
$wsPs1 = Join-Path $SCRIPTS 'step5_check_ws_external.ps1'
$q = Run-Exe 'powershell.exe' @('-NoProfile','-ExecutionPolicy','Bypass','-File',$wsPs1,
        '-MainJs',$MAINJS,
        '-LogPath',$outL2) $DSK $outL2 $outL2
Append-Log $outL2 20
if ($q.ExitCode -ne 0) { Write-Tee '!! STEP5 FAIL ws external'; pause; exit 53 }
Write-Tee '[OK] Desktop shell + ws external verified.'

# STEP 6 ELECTRON-BUILDER
Write-Step 'STEP 6/6 ELECTRON-BUILDER'
Remove-Item $RELEASE -Recurse -Force -ErrorAction SilentlyContinue
$outL = Join-Path $LOGS '06-builder-out.log'
$errL = Join-Path $LOGS '06-builder-err.log'
$p = Run-Exe 'npx.cmd' @('--no-install','electron-builder','--win',"--config.directories.output=$RELEASE") $DSK $outL $errL
if ($p.ExitCode -ne 0) { Append-Log $errL 80; Write-Tee '!! STEP6 FAIL builder'; pause; exit 61 }

# DONE
Write-Tee ''
Write-Tee $("=" * 78)
Write-Tee " PIPELINE COMPLETE @ $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Tee $("=" * 78)
Write-Tee " Installer directory = $RELEASE"
Write-Tee ' Installer files:'
Get-ChildItem $RELEASE -Filter '*.exe' -File -ErrorAction SilentlyContinue |
  Select-Object Name,@{n='SizeMB';e={[math]::Round($_.Length/1MB,2)}},LastWriteTime |
  Format-Table -AutoSize | Out-String | ForEach-Object { Write-Tee $_ }
Write-Tee ''
Write-Tee ' Baked fixes:'
Write-Tee '  [X] GET /settings typo + backend alias /api/v1/system-settings'
Write-Tee '  [X] JAR BOOT-INF/classes/static/index.html present'
Write-Tee '  [X] ws/bufferutil/utf-8-validate external + asarUnpack'
Write-Tee ''
Write-Host 'Pipeline complete! Press Enter to close this window...' -ForegroundColor Cyan
[void][Console]::ReadLine()
exit 0
