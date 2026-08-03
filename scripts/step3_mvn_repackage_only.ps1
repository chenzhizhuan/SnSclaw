# Step 3 only: mvn repackage with new target/classes/static
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$SRV = Join-Path $ROOT 'mateclaw-server'
$LOG = Join-Path $ROOT '.build-logs'
New-Item $LOG -ItemType Directory -Force | Out-Null
$TS = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$OUT = Join-Path $LOG "mvn3-out-$TS.log"
$ERR = Join-Path $LOG "mvn3-err-$TS.log"
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$env:JAVA_HOME = $JDK21
$env:PATH = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$MVN = (Get-Command mvn.cmd -EA 0).Source

Write-Host "Time start: $(Get-Date -F 'HH:mm:ss')"
Write-Host "RUN: $MVN -B package -DskipTests -Dmaven.test.skip=true -Dmaven.main.skip=true"
Write-Host "OUT -> $OUT"
$t1 = Get-Date
$p = Start-Process -FilePath $MVN -ArgumentList @('-B','package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.main.skip=true') `
     -WorkingDirectory $SRV -Wait -PassThru -NoNewWindow `
     -RedirectStandardOutput $OUT -RedirectStandardError $ERR
$t2 = Get-Date
Write-Host "exit=$($p.ExitCode)  dur=$([math]::Round(($t2-$t1).TotalSeconds,1))s"
if (Test-Path $OUT) {
  $tail = Get-Content $OUT -Tail 20
  $tail | ForEach-Object { Write-Host "  $_" }
  $jar = Get-Item (Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar') -EA 0
  if ($jar) { Write-Host "JAR now = $([math]::Round($jar.Length/1MB,2)) MB  mtime=$($jar.LastWriteTime)" }
}
exit $p.ExitCode
