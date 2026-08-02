$ErrorActionPreference = 'Stop'
$ROOT = 'c:\Users\Administrator\Desktop\AIPSpace\SnSclaw'
$LOGDIR = Join-Path $ROOT '.build-logs'
$JAR = Join-Path $ROOT 'mateclaw-server\target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$O = Join-Path $LOGDIR 'jar_audit.log'
$JDK21 = Join-Path $ROOT '.cache\jdk21\jdk-21.0.11+10'
$JAREXE = Join-Path $JDK21 'bin\jar.exe'
"" | Set-Content $O -Encoding UTF8
"JAR = $JAR" | Tee-Object $O -Append
"Size = $([math]::Round((Get-Item $JAR).Length/1MB,2)) MB" | Tee-Object $O -Append
$out = & $JAREXE tf $JAR 2>&1
$total = $out.Count
"Total entries (raw lines from jar tf) = $total" | Tee-Object $O -Append
$boot = $out | Select-String '^BOOT-INF/'
$libs = $out | Select-String '^BOOT-INF/lib/'
$idx  = $out | Select-String 'static/index\.html'
"BOOT-INF entries: $($boot.Count)" | Tee-Object $O -Append
"BOOT-INF/lib/*.jar: $($libs.Count)" | Tee-Object $O -Append
"static/index.html entries: $($idx.Count)" | Tee-Object $O -Append
if ($idx.Count -gt 0) {
  $idx | ForEach-Object { "  -> " + $_.Line } | Tee-Object $O -Append
}
"First 25 entries:" | Tee-Object $O -Append
$out[0..24] | ForEach-Object { "  " + $_ } | Tee-Object $O -Append
exit 0
