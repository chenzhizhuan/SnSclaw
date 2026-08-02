# =============================================================================
#  STEP 1 RESCUE — run spring-boot-maven-plugin:repackage ONLY
#  This reads the 15MB "ordinary jar" already produced by maven-jar-plugin,
#  injects all 100+ runtime dependencies (BOOT-INF/lib/*.jar) producing the
#  ~230MB Spring Boot FAT executable JAR. No javac, no test, no resources copy.
#  Then the rest of the pipeline runs: JAR audit → app.jar copy → Desktop build
#  → ws check → electron-builder.
# =============================================================================
$ErrorActionPreference = 'Stop'

$ROOT    = Split-Path -Parent $PSScriptRoot
$SRV     = Join-Path $ROOT 'mateclaw-server'
$DSK     = Join-Path $ROOT 'mateclaw-desktop'
$RELEASE = Join-Path $DSK  'release_final2'
$LOGDIR  = Join-Path $ROOT '.build-logs'
$TS      = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MASTER  = Join-Path $LOGDIR "PIPELINE_RESCUE_$TS.log"
New-Item $LOGDIR -ItemType Directory -Force | Out-Null
[System.IO.File]::WriteAllText($MASTER, "`n")

function Write-Tee { param([string]$s)
  $line = "$s`n"
  [System.IO.File]::AppendAllText($MASTER, $line)
}
function Run-Exe {
  param(
    [Parameter(Mandatory)][string]$Exe,
    [string[]]$ArgList=@(),
    [string]$Cwd=$ROOT,
    [string]$LogOut,
    [string]$LogErr
  )
  $std = if ($LogOut) { $LogOut } else { Join-Path $LOGDIR "tmp_$TS_out.log" }
  $ste = if ($LogErr) { $LogErr } else { Join-Path $LOGDIR "tmp_$TS_err.log" }
  Write-Tee "    Run: $Exe $($ArgList -join ' ')"
  Write-Tee "      cwd    = $Cwd"
  Write-Tee "      stdout = $std"
  Write-Tee "      stderr = $ste"
  $p = Start-Process -FilePath $Exe -ArgumentList $ArgList `
         -WorkingDirectory $Cwd -Wait -PassThru -NoNewWindow `
         -RedirectStandardOutput $std -RedirectStandardError $ste
  $code = $p.ExitCode
  Write-Tee "      exit=$code"
  return $code
}

# ---- PRE: JDK 21 force -------------------------------------------------------
Write-Tee '==============================================================='
Write-Tee '  PRE: Force JDK 21 (project cache) @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
if (-not (Test-Path $JDK21)) { Write-Tee '[FATAL] JDK21 dir missing at ' + $JDK21; exit 97 }
$javaExe = Join-Path $JDK21 'bin\java.exe'
$javacExe = Join-Path $JDK21 'bin\javac.exe'
$jarExe  = Join-Path $JDK21 'bin\jar.exe'
if (-not (Test-Path $javaExe))  { Write-Tee '[FATAL] java.exe missing'; exit 97 }
if (-not (Test-Path $javacExe)) { Write-Tee '[FATAL] javac.exe missing'; exit 97 }
if (-not (Test-Path $jarExe))  { Write-Tee '[FATAL] jar.exe missing'; exit 97 }
$env:JAVA_HOME = $JDK21
$env:PATH      = (Join-Path $JDK21 'bin') + ';' + $env:PATH
Write-Tee "    JDK21 project cache: $JDK21"
Write-Tee "    JAVA_HOME set, PATH preloaded with jdk21/bin"
Write-Tee "    java.exe / javac.exe / jar.exe exist"
$mvnCmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if (-not $mvnCmd) { Write-Tee '[FATAL] mvn.cmd not in PATH'; exit 98 }
Write-Tee "    mvn.cmd found: $($mvnCmd.Source)"
Write-Tee "    [OK] JDK 21 preload verified by filesystem checks"

# ---- STEP 0: pre-check -------------------------------------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 0/6 Pre-verify ordinary jar exists @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$ORDINARY = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
if (-not (Test-Path $ORDINARY)) { Write-Tee '[FATAL] ordinary jar missing'; exit 10 }
Write-Tee "    Ordinary JAR: $([math]::Round((Get-Item $ORDINARY).Length/1MB,2)) MB OK"

# ---- STEP 1: spring-boot:repackage (with 3 retries) -------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 1/6 mvn package (NO CLEAN) — up to 3 retries @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
Write-Tee '    Why package not spring-boot:repackage? Repackage requires the'
Write-Tee '    jar artifact be produced in SAME lifecycle invocation, otherwise'
Write-Tee '    it says "Source file is not available". So we run full package phase'
Write-Tee '    but maven-compiler-plugin will skip compile (all .class newer than .java).'
$JAR_PATH = $null
$OK = $false
foreach ($att in 1..3) {
  Write-Tee "  [attempt $att/3]"
  $out = Join-Path $LOGDIR "rescue-step1-package-out-a$att.log"
  $err = Join-Path $LOGDIR "rescue-step1-package-err-a$att.log"
  [GC]::Collect(); Start-Sleep -Seconds 3
  # maven.main.skip=true skips BOTH process-resources AND compile phases
  # (Defender keeps locking the 1443 resource copies). target/classes already
  # contains 3441 files: 1998 *.class + 281 static/* + all resources. Perfect.
  $code = Run-Exe mvn.cmd @('package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.main.skip=true') `
                  -Cwd $SRV -LogOut $out -LogErr $err
  if ($code -eq 0) {
    $cands = Get-ChildItem (Join-Path $SRV 'target') -Filter 'mateclaw-server-*.jar' `
               | Where-Object { $_.Name -notlike '*-sources*' -and $_.Name -notlike '*-javadoc*' -and $_.Name -notlike '*.original' }
    if ($cands) {
      $big = ($cands | Sort-Object Length -Descending)[0]
      if ($big.Length -gt 100MB) {
        $JAR_PATH = $big.FullName
        $OK = $true
        Write-Tee "    [OK] Maven package + repackage SUCCESS (attempt $att)"
        Write-Tee "         Path = $JAR_PATH"
        Write-Tee "         Size = $([math]::Round($big.Length/1MB,2)) MB"
        break
      } else {
        Write-Tee "    attempt $att produced jar only $([math]::Round($big.Length/1MB,2)) MB (too small — repackage missed) — retry"
      }
    }
  }
  Write-Tee "    attempt $att FAILED. GC + 10s backoff"
  [GC]::Collect(); Start-Sleep -Seconds 10
}
if (-not $OK) { Write-Tee '[FATAL] spring-boot:repackage FAILED all 3 attempts'; exit 21 }

# ---- STEP 2: JAR audit (BOOT-INF/classes/static/index.html present) ---------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 2/6 JAR audit @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$a4    = Join-Path $ROOT 'scripts\step4_audit_jar.ps1'
$a4out = Join-Path $LOGDIR "rescue-step2-audit.log"
[System.IO.File]::WriteAllText($a4out, "`n")
$code = Run-Exe powershell.exe @('-NoProfile','-ExecutionPolicy','Bypass','-File',$a4,'-JarPath',$JAR_PATH,'-LogPath',$a4out)
if ($code -ne 0) {
  Write-Tee '[FATAL] JAR audit FAILED'
  Get-Content $a4out -Encoding UTF8 | ForEach-Object { Write-Tee "      $_" }
  exit 22
}
Get-Content $a4out -Encoding UTF8 | ForEach-Object { Write-Tee "      $_" }

# ---- STEP 3: copy JAR → desktop/resources/app.jar ---------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 3/6 copy → mateclaw-desktop/resources/app.jar @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$APP_JAR = Join-Path $DSK 'resources\app.jar'
New-Item -ItemType Directory (Split-Path $APP_JAR) -Force | Out-Null
Copy-Item $JAR_PATH $APP_JAR -Force
Write-Tee "    [OK] app.jar size = $([math]::Round((Get-Item $APP_JAR).Length/1MB,2)) MB"

# ---- STEP 4: Desktop build (npm run build) ----------------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 4/6 Desktop build (npm run build) @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$dout = Join-Path $LOGDIR "rescue-step4-desktop-out.log"
$derr = Join-Path $LOGDIR "rescue-step4-desktop-err.log"
$env:NODE_OPTIONS = '--max-old-space-size=8192'
$env:BUILD_MODE    = 'local'
$code = Run-Exe npm.cmd @('run','build') -Cwd $DSK -LogOut $dout -LogErr $derr
if ($code -ne 0) { Write-Tee '[FATAL] Desktop build FAILED'; exit 23 }
$MAINJS = Join-Path $DSK 'dist-electron\main\index.js'
if (-not (Test-Path $MAINJS)) { Write-Tee '[FATAL] main/index.js missing'; exit 24 }
Write-Tee "    [OK] main/index.js = $([math]::Round((Get-Item $MAINJS).Length/1KB,2)) KB"

# ---- STEP 5: ws external check ----------------------------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 5/6 ws external check @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
$a5    = Join-Path $ROOT 'scripts\step5_check_ws_external.ps1'
$a5out = Join-Path $LOGDIR "rescue-step5-ws.log"
[System.IO.File]::WriteAllText($a5out, "`n")
$code = Run-Exe powershell.exe @('-NoProfile','-ExecutionPolicy','Bypass','-File',$a5,'-MainJs',$MAINJS,'-LogPath',$a5out)
if ($code -ne 0) {
  Write-Tee '[FATAL] ws external FAILED'
  Get-Content $a5out -Encoding UTF8 | ForEach-Object { Write-Tee "      $_" }
  exit 25
}
Get-Content $a5out -Encoding UTF8 | ForEach-Object { Write-Tee "      $_" }

# ---- STEP 6: electron-builder -----------------------------------------------
Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  STEP 6/6 electron-builder (win x64) @ ' + (Get-Date -F 'HH:mm:ss')
Write-Tee '==============================================================='
New-Item $RELEASE -ItemType Directory -Force | Out-Null
$bout = Join-Path $LOGDIR "rescue-step6-builder-out.log"
$berr = Join-Path $LOGDIR "rescue-step6-builder-err.log"
$code = Run-Exe npx.cmd @('electron-builder','--win','--x64','--publish=never',"`-c.output=$RELEASE") `
                -Cwd $DSK -LogOut $bout -LogErr $berr
if ($code -ne 0) { Write-Tee '[FATAL] electron-builder FAILED'; exit 26 }
Write-Tee "    electron-builder exit=0"

Write-Tee ''
Write-Tee '==============================================================='
Write-Tee '  RELEASE OUTPUT'
Write-Tee '==============================================================='
$rels = Get-ChildItem $RELEASE -Recurse -File -ErrorAction SilentlyContinue
foreach ($r in $rels) {
  $rel = $r.FullName.Replace($ROOT, '.').Replace('\','/')
  Write-Tee ("    {0,-70} {1,9:N2} MB   {2}" -f $rel, ($r.Length/1MB), $r.LastWriteTime)
}
$exe = $rels | Where-Object { $_.Extension -eq '.exe' } | Sort-Object Length -Descending | Select-Object -First 1
if ($exe) {
  Write-Tee ''
  Write-Tee ("[SUCCESS] FINAL EXE: {0}  ({1:N2} MB)" -f $exe.FullName, ($exe.Length/1MB))
} else {
  Write-Tee '[WARN] No .exe produced. Check builder logs.'
}
Write-Tee ''
Write-Tee ('=== RESCUE PIPELINE DONE @ ' + (Get-Date -F 'HH:mm:ss') + ' ===')
exit 0
