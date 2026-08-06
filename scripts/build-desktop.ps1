<#
.SYNOPSIS
  SnSclaw Windows 桌面端安装包构建 —— 唯一入口。

.DESCRIPTION
  七步流水线：typecheck -> vite -> maven -> jar -> desktop -> installer -> verify
  用 -From / -To 选区间，-Fast 走 maven.main.skip 快路径。

  取代了 34 个历史脚本。每个被删脚本的诉求都映射到某个开关，详见 scripts/README.md。

  ⚠ mateclaw-ui/vite.config.ts 的 outDir 指向 ../mateclaw-server/src/main/resources/static，
    vite 直接写进后端 static 目录。不存在 mateclaw-ui/dist/。

.EXAMPLE
  .\build-desktop.ps1
  全量构建（含 javac 重编译）。

.EXAMPLE
  .\build-desktop.ps1 -Fast -From maven
  前端已绿、后端 .java 未改：跳过 vite，只 repackage。

.EXAMPLE
  .\build-desktop.ps1 -From installer -AggressiveUnlock
  只重跑 NSIS，并在每次尝试前 touch 扫描释放 Defender 句柄。
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
  [ValidateSet('typecheck','vite','maven','jar','desktop','installer','verify')]
  [string]$From = 'typecheck',

  [ValidateSet('typecheck','vite','maven','jar','desktop','installer','verify')]
  [string]$To = 'verify',

  [switch]$Fast,
  [switch]$SkipTypeCheck,
  [switch]$FailOnTypeError,
  [switch]$VerifyOnly,
  [string]$OutputDir,
  [string]$InstallerPath,

  # Target CPU architecture. x64 stays the default so existing invocations are
  # unchanged. arm64 requires resources\jre\win-arm64\ to be present first:
  #   bash mateclaw-desktop/scripts/download-jre.sh --os win --arch arm64
  [ValidateSet('x64','arm64')]
  [string]$Arch = 'x64',

  [int]$BuilderAttempts = 7,
  [int]$MavenAttempts = 6,
  [switch]$AggressiveUnlock,
  [switch]$KeepTemp,
  [switch]$Force,
  [int]$MinJarSizeMB = 350,
  [int]$MinStaticFiles = 150,
  [string]$AssertJarContains,
  [string]$Root,
  [string]$LogDir
)

# ===========================================================================
# region 0  Bootstrap
# ===========================================================================
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Root)   { $Root = Split-Path $PSScriptRoot -Parent }
$Root = (Resolve-Path -LiteralPath $Root).Path
$UI   = Join-Path $Root 'mateclaw-ui'
$SRV  = Join-Path $Root 'mateclaw-server'
$DSK  = Join-Path $Root 'mateclaw-desktop'
$SRC_STATIC = Join-Path $SRV 'src\main\resources\static'
$TGT_STATIC = Join-Path $SRV 'target\classes\static'
$SRV_JAR    = Join-Path $SRV 'target\mateclaw-server-1.0.0-SNAPSHOT.jar'
$APP_JAR    = Join-Path $DSK 'resources\app.jar'
$MAIN_JS    = Join-Path $DSK 'dist-electron\main\index.js'
$RELEASE    = Join-Path $DSK 'release'

# electron-builder 的 unpacked 目录名带 arch 后缀，但只对「非默认 arch」加：
# builder-util getArchSuffix() 在 arch == defaultArch(未配置时为 x64) 时返回 ''。
# 所以 x64 -> 'win-unpacked'，arm64 -> 'win-arm64-unpacked'。写死 win-unpacked
# 会让 arm64 构建的 verify/unlock 步骤找不到目录。
$UNPACKED_DIR = if ($Arch -eq 'x64') { 'win-unpacked' } else { "win-$Arch-unpacked" }

if (-not $LogDir) { $LogDir = Join-Path $Root '.build-logs' }
$TS   = [int][DateTimeOffset]::Now.ToUnixTimeSeconds()
$MAST = Join-Path $LogDir "ALL_$TS.log"
$LAST = Join-Path $LogDir 'last-build.json'

$STEPS = @('typecheck','vite','maven','jar','desktop','installer','verify')
if ($VerifyOnly) { $From = 'verify'; $To = 'verify' }
$iFrom = [array]::IndexOf($STEPS, $From)
$iTo   = [array]::IndexOf($STEPS, $To)

# 每一步的失败退出码。helper 子进程的码在调用处重映射到这里。
$EXIT = @{
  args = 10; typecheck = 11; vite = 20; viteThin = 21; staticSync = 30
  maven = 31; jarAudit = 32; jarCopy = 40; desktop = 41; ws = 42
  builder = 50; releaseCopy = 51; e2e = 60; precondition = 70
}

function Should-Run { param([string]$Step)
  return ([array]::IndexOf($STEPS,$Step) -ge $iFrom -and [array]::IndexOf($STEPS,$Step) -le $iTo)
}

# 日志目录用 -Confirm:$false 强制建立：-WhatIf 也要能写日志，否则计划输出无处落地
New-Item -ItemType Directory -Force -Path $LogDir -Confirm:$false -WhatIf:$false | Out-Null
[IO.File]::WriteAllText($MAST, "=== ALL @ $(Get-Date -F 'yyyy-MM-dd HH:mm:ss') ===`r`n", [Text.Encoding]::UTF8)

# 日志写入用 UTF8 无 BOM；读取一律带 -Encoding UTF8，否则 PS 5.1 按 ANSI 码页
# 解码，中文产物名（智算方舟_..._Setup.exe）会乱码。
function W { param([string]$s) Write-Host $s; [IO.File]::AppendAllText($MAST, "$s`r`n", [Text.Encoding]::UTF8) }
function Wr { param([string]$s) W ''; W ('=' * 63); W "  $s"; W ('=' * 63) }
function Tail {
  param([string]$Path, [int]$N = 15, [string]$Prefix = '    > ')
  if (Test-Path -LiteralPath $Path) {
    Get-Content -LiteralPath $Path -Tail $N -Encoding UTF8 -EA 0 | ForEach-Object { W ($Prefix + $_) }
  }
}
function Die { param([string]$Msg, [int]$Code) W "  [FAIL] $Msg"; W ''; W "  exit=$Code"; exit $Code }

# 所有改动性操作走这里，于是 -WhatIf 能覆盖全部副作用。
function Confirm-Act { param([string]$Target, [string]$Action) return $PSCmdlet.ShouldProcess($Target, $Action) }

# 参数名刻意叫 $ArgList 而不是 $args —— $args 是 PowerShell 自动变量，
# 撞名会让整个参数数组变成 $null，历史上导致 node.exe 不带参数启动。
function Invoke-Exe {
  param(
    [Parameter(Mandatory)][string]$FilePath,
    [string[]]$ArgList = @(),
    [Parameter(Mandatory)][string]$Cwd,
    [Parameter(Mandatory)][string]$OutLog,
    [Parameter(Mandatory)][string]$ErrLog
  )
  if ($OutLog -eq $ErrLog) { throw "Invoke-Exe: OutLog 与 ErrLog 不能相同（Start-Process 会直接抛错）: $OutLog" }
  if (-not $FilePath) { throw 'Invoke-Exe: FilePath 为空（工具链解析失败？）' }
  W "    run: $FilePath $($ArgList -join ' ')"
  if (-not (Confirm-Act $FilePath "run $($ArgList -join ' ')")) { return 0 }
  $p = Start-Process -FilePath $FilePath -ArgumentList $ArgList -WorkingDirectory $Cwd `
       -Wait -PassThru -NoNewWindow -RedirectStandardOutput $OutLog -RedirectStandardError $ErrLog
  return $p.ExitCode
}

function Invoke-WithRetry {
  param([Parameter(Mandatory)][scriptblock]$Action, [int]$Attempts = 6, [int]$DelaySec = 5, [string]$Label = 'op')
  for ($i = 1; $i -le $Attempts; $i++) {
    try { & $Action; return $true }
    catch { W "    $Label $i/$Attempts 失败: $($_.Exception.Message)"; [GC]::Collect(); Start-Sleep -Seconds $DelaySec }
  }
  return $false
}

function Get-FileCount { param([string]$Dir) return @(Get-ChildItem -LiteralPath $Dir -Recurse -File -EA 0).Count }
function Get-MTime { param([string]$Path) $i = Get-Item -LiteralPath $Path -EA 0; if ($i) { return $i.LastWriteTime } return $null }
function Get-NewestMTime {
  param([string]$Dir, [string]$Filter)
  $f = @(Get-ChildItem -LiteralPath $Dir -Recurse -File -Filter $Filter -EA 0 | Sort-Object LastWriteTime -Descending)
  if ($f.Count -gt 0) { return $f[0].LastWriteTime }
  return $null
}

# Jar 内容审计（内联，try/finally 保证 Dispose 在 Defender 施压下也能释放句柄）
function Get-JarAudit {
  param([Parameter(Mandatory)][string]$JarPath)
  $result = @{ libs=0; static=0; classes=0; skills=0; migrations=0; hasIndex=$false; ok=$false; err='' }
  try {
    $z = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
      $e = $z.Entries
      $result.libs       = @($e | Where-Object { $_.FullName -like 'BOOT-INF/lib/*' }).Count
      $result.static     = @($e | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
      $result.hasIndex   = [bool]($e | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' })
      $result.classes    = @($e | Where-Object { $_.FullName -like 'BOOT-INF/classes/*.class' -or $_.FullName -like 'BOOT-INF/classes/**/*.class' }).Count
      $result.skills     = @($e | Where-Object { $_.FullName -like 'BOOT-INF/classes/skills/*.py' -or $_.FullName -like 'BOOT-INF/classes/skills/**/*.py' }).Count
      $result.migrations = @($e | Where-Object { $_.FullName -like 'BOOT-INF/classes/db/migration/*.sql' }).Count
      $result.ok         = $true
    } finally { $z.Dispose() }
  } catch { $result.err = $_.Exception.Message }
  return $result
}

# ===========================================================================
# region 1  Preflight
# ===========================================================================
if ($iFrom -lt 0 -or $iTo -lt 0 -or $iFrom -gt $iTo) {
  W "  [ERR] -From '$From' / -To '$To' 无效或顺序错"; exit $EXIT.args
}

Wr "PREFLIGHT  @ $(Get-Date -F 'HH:mm:ss')"
W "  Root    = $Root"
W "  From    = $From  To = $To  Fast = $($Fast.IsPresent)  AggressiveUnlock = $($AggressiveUnlock.IsPresent)"

# 工具链解析（路径因机器而异，必须用 Get-Command，不能硬编码）
$_n = Get-Command node.exe -EA 0; $NODE = if ($_n) { $_n.Source } else { $null }
$_m = Get-Command mvn.cmd  -EA 0; $MVN  = if ($_m) { $_m.Source } else { $null }
$_p = Get-Command npm.cmd  -EA 0; $NPM  = if ($_p) { $_p.Source } else { $null }
$VUE_TSC = Join-Path $UI  'node_modules\vue-tsc\bin\vue-tsc.js'
$VITE_JS = Join-Path $UI  'node_modules\vite\bin\vite.js'
$EB      = Join-Path $DSK 'node_modules\.bin\electron-builder.cmd'

W "  node.exe = $(if($NODE){$NODE}else{'NOT FOUND'})"
W "  mvn.cmd  = $(if($MVN){$MVN}else{'NOT FOUND'})"
W "  npm.cmd  = $(if($NPM){$NPM}else{'NOT FOUND'})"
foreach ($pair in @(
  ,@('vue-tsc.js',       $VUE_TSC)
  ,@('vite.js',          $VITE_JS)
  ,@('electron-builder', $EB)
  ,@('DSK pkg.json',     (Join-Path $DSK 'package.json'))
  ,@('DSK resources/',   (Join-Path $DSK 'resources'))
)) {
  $n = $pair[0]; $v = $pair[1]
  W "  $n  = $v  [$(if(Test-Path -LiteralPath $v){'OK'}else{'MISSING'})]"
}

# 只校验本次真正要用到的工具，避免 -From installer 时因缺 mvn 而误报。
# mvn 尤其需要注意：它是否在 PATH 里取决于父 shell，直接 PowerShell 会话里可能找不到。
if ((Should-Run 'typecheck') -or (Should-Run 'vite')) {
  if (-not $NODE) { Die 'node.exe 找不到（typecheck/vite 需要它）' $EXIT.args }
}
if (Should-Run 'maven') {
  if (-not $MVN) {
    # 回退：在已知位置找 mvn，PATH 因父 shell 而异
    foreach ($cand in @(
      (Join-Path $env:USERPROFILE '.trae\tools\maven\latest\bin\mvn.cmd'),
      (Join-Path $Root '.tools\maven\bin\mvn.cmd')
    )) {
      if (Test-Path -LiteralPath $cand) { $MVN = $cand; W "  mvn.cmd  = $MVN  (回退路径)"; break }
    }
  }
  if (-not $MVN) { Die 'mvn.cmd 找不到（maven 步骤需要它，请加入 PATH）' $EXIT.args }
}
if ((Should-Run 'desktop')) {
  if (-not $NPM) { Die 'npm.cmd 找不到（desktop 步骤需要它）' $EXIT.args }
}
if ((Should-Run 'installer')) {
  if (-not (Test-Path -LiteralPath $EB)) { Die "electron-builder 找不到：$EB（先 npm i）" $EXIT.args }
}

# JDK + 环境变量
$JDK21 = Join-Path $Root '.cache\jdk21\jdk-21.0.11+10'
if (-not (Test-Path -LiteralPath (Join-Path $JDK21 'bin\java.exe'))) {
  Die "JDK21 未找到：$JDK21（先运行 .tools 初始化或手动解压）" $EXIT.args
}
$env:JAVA_HOME            = $JDK21
$env:PATH                 = (Join-Path $JDK21 'bin') + ';' + $env:PATH
$env:NODE_OPTIONS         = '--max-old-space-size=8192'
$env:BUILD_MODE           = 'local'
$env:ELECTRON_CACHE       = Join-Path $Root '.cache\electron'
$env:ELECTRON_BUILDER_CACHE = Join-Path $Root '.cache\electron-builder'
New-Item -ItemType Directory -Force -Path $env:ELECTRON_CACHE,$env:ELECTRON_BUILDER_CACHE -WhatIf:$false | Out-Null
W "  JAVA_HOME = $env:JAVA_HOME"
W "  BUILD_MODE = local"

# 7za guard：electron-builder 重命名 7za.exe；Defender 也会重命名它。
# 每次构建开始前从备份恢复，保证后续 electron-builder 能找到它。
$_7za     = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za.exe'
$_7zaOrig = Join-Path $DSK 'node_modules\7zip-bin\win\x64\7za_original.exe'
if ((Test-Path $_7zaOrig) -and (-not (Test-Path $_7za))) {
  Copy-Item -LiteralPath $_7zaOrig -Destination $_7za -Force -EA 0
  W '  7za.exe 已从 7za_original.exe 恢复'
}

W ''
W "  步骤计划: $($STEPS[$iFrom..$iTo] -join ' -> ')"
W "  日志: $MAST"

# -WhatIf 在打印计划后退出，不执行任何构建动作
if (-not $PSCmdlet.ShouldProcess('build-desktop', '执行构建')) {
  W '  [WhatIf] 已打印计划，未执行任何操作'
  exit 0
}
New-Item -ItemType Directory -Force -Path $RELEASE | Out-Null

# ===========================================================================
# Assert-Precondition：-From 跳过早期步骤时，验证前一步的产物仍然新鲜有效。
# 每个断言都有对应的历史脚本 —— 那些脚本就是因为断言写在注释里而不是代码里才埋了坑。
# ===========================================================================
function Assert-Precondition {
  param([string]$Step)
  if ($Force) { W '  [WARN] -Force：跳过前置断言'; return }

  $idx = [array]::IndexOf($STEPS, $Step)

  # typecheck 及以上：node_modules 必须在
  if ($idx -ge 0) {
    if (-not (Test-Path -LiteralPath $VUE_TSC)) { Die "缺少 vue-tsc.js：$VUE_TSC" $EXIT.precondition }
  }
  # vite 及以上：vite 必须在
  if ($idx -ge 1) {
    if (-not (Test-Path -LiteralPath $VITE_JS)) { Die "缺少 vite.js：$VITE_JS" $EXIT.precondition }
  }
  # maven 及以上：static/ 必须存在且够厚
  if ($idx -ge 2) {
    if (-not (Test-Path -LiteralPath (Join-Path $SRC_STATIC 'index.html'))) {
      Die "缺少 $SRC_STATIC\index.html —— 先运行 vite 步骤（注意 outDir 直接写后端 static）" $EXIT.precondition
    }
    $sc = Get-FileCount $SRC_STATIC
    if ($sc -lt $MinStaticFiles) { Die "static/ 文件数 $sc < $MinStaticFiles，vite 输出可能不完整" $EXIT.precondition }
    # mvn / JDK 的存在性属于工具链门禁（按 Should-Run 'maven' 判断），不放在累积断言里，
    # 否则 -From jar -To jar 这种不碰 Maven 的场景会被误拦。
    if ($Fast -and (Should-Run 'maven')) {
      # -Fast 依赖已有的 target/classes；若有 .java 比 .class 新就拒绝
      $newestJava  = Get-NewestMTime (Join-Path $SRV 'src') '*.java'
      $newestClass = Get-NewestMTime (Join-Path $SRV 'target\classes') '*.class'
      if ($newestJava -and (-not $newestClass -or $newestJava -gt $newestClass)) {
        Die '-Fast 前提不满足：有 .java 文件比 .class 新，必须先全量编译（去掉 -Fast 或加 -Force）' $EXIT.precondition
      }
    }
  }
  # jar 及以上：服务端 jar 必须比 static/index.html 新
  if ($idx -ge 3) {
    if (-not (Test-Path -LiteralPath $SRV_JAR)) { Die "服务端 JAR 不存在：$SRV_JAR" $EXIT.precondition }
    $jarSz = (Get-Item -LiteralPath $SRV_JAR).Length / 1MB
    if ($jarSz -lt $MinJarSizeMB) { Die "服务端 JAR 太小：$([math]::Round($jarSz,1)) MB < $MinJarSizeMB MB" $EXIT.precondition }
    $idxMT = Get-MTime (Join-Path $SRC_STATIC 'index.html')
    $jarMT = Get-MTime $SRV_JAR
    if ($idxMT -and $jarMT -and $jarMT -lt $idxMT) {
      Die "服务端 JAR ($jarMT) 比 static/index.html ($idxMT) 旧 —— 前端已改但 Maven 未重跑" $EXIT.precondition
    }
  }
  # desktop 及以上：app.jar 必须比服务端 jar 新
  if ($idx -ge 4) {
    if (-not (Test-Path -LiteralPath $APP_JAR)) { Die "缺少 $APP_JAR —— 先运行 jar 步骤" $EXIT.precondition }
    $appSz = (Get-Item -LiteralPath $APP_JAR).Length / 1MB
    if ($appSz -lt $MinJarSizeMB) { Die "app.jar 太小：$([math]::Round($appSz,1)) MB" $EXIT.precondition }
    if (-not (Test-Path -LiteralPath (Join-Path $DSK 'node_modules\.bin'))) { Die "mateclaw-desktop/node_modules 未安装" $EXIT.precondition }
  }
  # installer 及以上：app.jar + dist-electron + main.js mtime 检查
  if ($idx -ge 5) {
    if (-not (Test-Path -LiteralPath $MAIN_JS)) { Die "缺少 $MAIN_JS —— 先运行 desktop 步骤" $EXIT.precondition }
    $mainMT = Get-MTime $MAIN_JS
    $newestTs = Get-NewestMTime (Join-Path $DSK 'electron\main') '*.ts'
    if ($mainMT -and $newestTs -and $mainMT -lt $newestTs) {
      Die "dist-electron/main/index.js ($mainMT) 比 electron/main/*.ts ($newestTs) 旧 —— 先运行 desktop 步骤" $EXIT.precondition
    }
    $appMT = Get-MTime $APP_JAR
    $sidxMT = Get-MTime (Join-Path $SRC_STATIC 'index.html')
    if ($appMT -and $sidxMT -and $appMT -lt $sidxMT) {
      Die "app.jar ($appMT) 比 static/index.html ($sidxMT) 旧" $EXIT.precondition
    }
    # ws 快速检查，复用 helper 逻辑（不开子进程，只 grep）
    $raw = [IO.File]::ReadAllText($MAIN_JS)
    if ($raw -notmatch 'require\([\x22\x27]ws[\x22\x27]\)') {
      Die 'ws 被内联进 index.js（必须是 external），先重新运行 desktop 步骤' $EXIT.precondition
    }
  }
}

Assert-Precondition $From

# ===========================================================================
# region 2  typecheck
# ===========================================================================
if (Should-Run 'typecheck') {
  if ($SkipTypeCheck) { W '  [SKIP] typecheck (-SkipTypeCheck)' }
  else {
    Wr "STEP typecheck  vue-tsc --noEmit"
    $o = Join-Path $LogDir "all-tsc-out-$TS.log"
    $e = Join-Path $LogDir "all-tsc-err-$TS.log"
    $t1 = Get-Date
    $ec = Invoke-Exe -FilePath $NODE -ArgList @($VUE_TSC,'--noEmit') -Cwd $UI -OutLog $o -ErrLog $e
    $dur = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
    W "  exit=$ec  dur=${dur}s"
    if ($ec -ne 0) {
      Tail $e 40 '    ! '
      if ($FailOnTypeError) { Die 'vue-tsc 报错 (-FailOnTypeError)' $EXIT.typecheck }
      else { W '  [WARN] vue-tsc 有错，继续（加 -FailOnTypeError 可阻断）' }
    } else { W '  [OK] tsc PASS' }
  }
}

# ===========================================================================
# region 3  vite
# ===========================================================================
if (Should-Run 'vite') {
  Wr "STEP vite  build -> mateclaw-server/src/main/resources/static"
  $o = Join-Path $LogDir "all-vite-out-$TS.log"
  $e = Join-Path $LogDir "all-vite-err-$TS.log"
  $t1 = Get-Date
  $ec = Invoke-Exe -FilePath $NODE -ArgList @($VITE_JS,'build') -Cwd $UI -OutLog $o -ErrLog $e
  $dur = [math]::Round(((Get-Date)-$t1).TotalSeconds,1)
  W "  exit=$ec  dur=${dur}s"
  if ($ec -ne 0) { Tail $e 40 '    ! '; Die 'vite build 失败' $EXIT.vite }
  $si  = Get-Item -LiteralPath (Join-Path $SRC_STATIC 'index.html') -EA 0
  $cnt = Get-FileCount $SRC_STATIC
  if (-not $si -or $cnt -lt $MinStaticFiles) {
    Die "vite 产出不足：index=$(if($si){'Y'}else{'N'}) 文件数=$cnt < $MinStaticFiles" $EXIT.viteThin
  }
  $tot = (Get-ChildItem -LiteralPath $SRC_STATIC -Recurse -File -EA 0 | Measure-Object Length -Sum)
  W "  static/index.html size=$($si.Length)B  mtime=$($si.LastWriteTime)"
  W "  static files=$cnt  total=$([math]::Round($tot.Sum/1MB,2)) MB"
  W '  [OK] Vite PASS'
}

# ===========================================================================
# region 4  maven
# ===========================================================================
if (Should-Run 'maven') {
  if ($Fast) {
    Wr "STEP maven  -Fast：manual static sync + repackage（跳过 javac）"
    if (Test-Path -LiteralPath $TGT_STATIC) {
      $ok = Invoke-WithRetry -Label 'del TGT_STATIC' -Attempts 6 -DelaySec 5 -Action {
        Remove-Item -LiteralPath $TGT_STATIC -Recurse -Force -EA Stop
      }
      if (-not $ok) { Die 'target/classes/static 删除失败（Defender 锁？）' $EXIT.staticSync }
    }
    New-Item -ItemType Directory -Force -Path $TGT_STATIC | Out-Null
    $ok = Invoke-WithRetry -Label 'copy static' -Attempts 6 -DelaySec 5 -Action {
      Copy-Item -Path (Join-Path $SRC_STATIC '*') -Destination $TGT_STATIC -Recurse -Force -EA Stop
    }
    if (-not $ok) { Die 'static 拷贝到 target/classes 失败' $EXIT.staticSync }
    W "  拷贝 $(Get-FileCount $TGT_STATIC) 个文件 [OK]"
    $MVN_ARGS  = @('-B','package','-DskipTests','-Dmaven.test.skip=true','-Dmaven.main.skip=true')
    $mvAttempts = 4
  } else {
    Wr "STEP maven  全量重编译（mvn -B package，$MavenAttempts 次重试）"
    $MVN_ARGS  = @('-B','package','-DskipTests','-Dmaven.test.skip=true')
    $mvAttempts = $MavenAttempts
  }
  $mvok = $false
  for ($ma = 1; $ma -le $mvAttempts; $ma++) {
    W "  attempt $ma/$mvAttempts"
    if ($ma -ge 2 -and -not $Fast) {
      foreach ($sub in @('skills','pptx','db','i18n','static')) {
        $todel = Join-Path $SRV "target\classes\$sub"
        if (Test-Path -LiteralPath $todel) {
          W "    cleaning target/classes/$sub"
          for ($dd=1; $dd -le 5; $dd++) {
            try { Remove-Item -LiteralPath $todel -Recurse -Force -EA Stop; break }
            catch { [GC]::Collect(); Start-Sleep -Seconds 5 }
          }
        }
      }
    }
    $o  = Join-Path $LogDir "all-mvn-out-$TS-$ma.log"
    $e2 = Join-Path $LogDir "all-mvn-err-$TS-$ma.log"
    [GC]::Collect(); Start-Sleep -Seconds $(if($ma -eq 1){3}else{5})
    $t1 = Get-Date
    $mc = Invoke-Exe -FilePath $MVN -ArgList $MVN_ARGS -Cwd $SRV -OutLog $o -ErrLog $e2
    W "    exit=$mc  dur=$([math]::Round(((Get-Date)-$t1).TotalSeconds,1))s"
    if ($mc -eq 0 -and (Test-Path -LiteralPath $SRV_JAR) -and
        (Get-Item $SRV_JAR).Length -gt ($MinJarSizeMB * 1MB)) {
      $a = Get-JarAudit $SRV_JAR
      if ($a.err) { W "    audit err: $($a.err)" }
      W "    BOOT-INF  libs=$($a.libs) static=$($a.static) classes=$($a.classes) skills=$($a.skills) migrations=$($a.migrations) index=$(if($a.hasIndex){'Y'}else{'N'})"
      if ($a.libs -gt 250 -and $a.static -gt 250 -and $a.hasIndex -and $a.classes -gt 1200) {
        $mvok = $true
      }
    }
    if ($mvok) { break }
    Tail $o 15; Tail $e2 10 '    ! '
    if ($ma -lt $mvAttempts) { Start-Sleep -Seconds 30 }
  }
  if (-not $mvok) { Die "Maven $mvAttempts 次后均失败" $EXIT.maven }
  $jf = Get-Item -LiteralPath $SRV_JAR
  W "  server JAR = $([math]::Round($jf.Length/1MB,2)) MB  mtime=$($jf.LastWriteTime)  [OK]"
}

# ===========================================================================
# region 5  jar  — 拷 jar + BOOT-INF 子进程审计
# ===========================================================================
if (Should-Run 'jar') {
  Wr "STEP jar  拷贝 app.jar + BOOT-INF 审计"
  [GC]::Collect(); Start-Sleep -Seconds 3
  $ok = Invoke-WithRetry -Label 'copy app.jar' -Attempts 7 -DelaySec 6 -Action {
    Copy-Item -LiteralPath $SRV_JAR -Destination $APP_JAR -Force -EA Stop
  }
  if (-not $ok) { Die 'app.jar 拷贝失败（Defender 锁？）' $EXIT.jarCopy }
  $af = Get-Item -LiteralPath $APP_JAR
  W "  app.jar = $([math]::Round($af.Length/1MB,2)) MB  mtime=$($af.LastWriteTime)"

  # 通过子进程运行 helper：[IO.Compression.ZipFile]::OpenRead 持有405MB jar的句柄；
  # 进程退出是 Defender 压力下唯一确定的释放方式（see scripts/README.md）
  $auditLog = Join-Path $LogDir "04-audit-$TS.log"
  $auditOut = Join-Path $LogDir "04-audit-out-$TS.log"
  $auditErr = Join-Path $LogDir "04-audit-err-$TS.log"
  $auditPs1 = Join-Path $PSScriptRoot 'step4_audit_jar.ps1'
  $q = Invoke-Exe -FilePath 'powershell.exe' `
    -ArgList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$auditPs1,'-JarPath',$APP_JAR,'-LogPath',$auditLog) `
    -Cwd $SRV -OutLog $auditOut -ErrLog $auditErr
  Tail $auditLog 5
  if ($q -ne 0) { Die "JAR 审计失败（step4 exit=$q）" $EXIT.jarAudit }
  W '  [OK] app.jar 已拷贝且通过审计'
}

# ===========================================================================
# region 6  desktop  — npm run build + ws 外置校验
# ===========================================================================
if (Should-Run 'desktop') {
  Wr "STEP desktop  npm run build + ws external check"
  $o = Join-Path $LogDir "all-desk-out-$TS.log"
  $e = Join-Path $LogDir "all-desk-err-$TS.log"
  $ec = Invoke-Exe -FilePath $NPM -ArgList @('run','build') -Cwd $DSK -OutLog $o -ErrLog $e
  W "  desktop exit=$ec  main.js exists=$(Test-Path -LiteralPath $MAIN_JS)"
  if ($ec -ne 0 -or -not (Test-Path -LiteralPath $MAIN_JS)) {
    Tail $e 20 '    ! '; Die 'desktop build 失败' $EXIT.desktop
  }

  $wsLog = Join-Path $LogDir "05-ws-$TS.log"
  $wsOut = Join-Path $LogDir "05-ws-out-$TS.log"
  $wsErr = Join-Path $LogDir "05-ws-err-$TS.log"
  $wsPs1 = Join-Path $PSScriptRoot 'step5_check_ws_external.ps1'
  $wq = Invoke-Exe -FilePath 'powershell.exe' `
    -ArgList @('-NoProfile','-ExecutionPolicy','Bypass','-File',$wsPs1,'-MainJs',$MAIN_JS,'-LogPath',$wsLog) `
    -Cwd $DSK -OutLog $wsOut -ErrLog $wsErr
  Tail $wsLog 3
  if ($wq -ne 0) { Die "ws 被内联（step5 exit=$wq）" $EXIT.ws }
  W '  [OK] desktop shell + ws external 验证通过'
}

# ===========================================================================
# region 7  installer  — electron-builder，每次尝试用全新输出目录
# ===========================================================================
# Defender 会持有 win-unpacked/ 下文件的句柄，导致下一次 electron-builder 打包
# 时 EPERM。三层缓解：(1) 每次尝试换全新目录，Defender 对它是冷的；
# (2) [GC]::Collect() + sleep 释放本进程句柄；(3) -AggressiveUnlock 主动 touch。
function Release-LockOnDir {
  param([string]$Dir)
  if (-not (Test-Path -LiteralPath $Dir)) { return }
  W "    touch 扫描释放句柄: $Dir"
  $fi = @(Get-ChildItem -LiteralPath $Dir -Recurse -File -EA 0)
  $n = 0
  foreach ($f in $fi) {
    try { $fs = [IO.File]::OpenRead($f.FullName); $null = $fs.ReadByte(); $fs.Dispose() } catch { }
    $n++
    if ($n % 500 -eq 0) { W "      touched $n / $($fi.Count)" }
  }
  [GC]::Collect(); [GC]::WaitForPendingFinalizers(); Start-Sleep -Seconds 6
}

$InstallerSrc = $null
$WinUnpacked  = $null
if (Should-Run 'installer') {
  Wr "STEP installer  electron-builder（store 压缩，最多 $BuilderAttempts 次，arch=$Arch）"

  # extraResources 读 resources\jre\win-<arch>\，缺了会打出一个没有 JRE 的安装包
  # ——装完才发现起不来。这里提前失败，报错比事后排查便宜。
  $jreDir = Join-Path $DSK "resources\jre\win-$Arch"
  if (-not (Test-Path -LiteralPath (Join-Path $jreDir 'bin\java.exe'))) {
    Die "缺少 $Arch 的 JRE：$jreDir\bin\java.exe`n  先运行: bash mateclaw-desktop/scripts/download-jre.sh --os win --arch $Arch" $EXIT.precondition
  }

  $attemptDirs = @()
  for ($a = 1; $a -le $BuilderAttempts; $a++) {
    if ($AggressiveUnlock) {
      Release-LockOnDir (Join-Path $RELEASE $UNPACKED_DIR)
      if ($a -gt 2) {
        $wu = Join-Path $RELEASE $UNPACKED_DIR
        if (Test-Path -LiteralPath $wu) {
          W "    hard attempt: rename $UNPACKED_DIR 后删除"
          $bakName = 'win-unpacked-bak-' + [guid]::NewGuid().ToString('N')
          try {
            Rename-Item -LiteralPath $wu -NewName $bakName -Force -EA Stop
            Start-Sleep -Seconds 4
            Remove-Item -LiteralPath (Join-Path $RELEASE $bakName) -Recurse -Force -EA Stop
          } catch { W "    evict 失败: $($_.Exception.Message)" }
        }
      }
    }
    $attemptOut = if ($OutputDir) { Join-Path $OutputDir "_a$a" } else { Join-Path $env:TEMP "_bd_${TS}_$a" }
    New-Item -ItemType Directory -Force -Path $attemptOut | Out-Null
    $attemptDirs += $attemptOut
    $bout = Join-Path $LogDir "all-eb-out-$TS-$a.log"
    $berr = Join-Path $LogDir "all-eb-err-$TS-$a.log"
    W "  attempt $a/$BuilderAttempts  dir=$attemptOut"
    [GC]::Collect(); Start-Sleep -Seconds 4
    # WIN_ARCH drives electron-builder.cjs's target list. Without it, the config
    # would declare both arches and --$Arch would union rather than filter,
    # producing both installers plus a combined one.
    $env:WIN_ARCH = $Arch
    $ec = Invoke-Exe -FilePath $EB -Cwd $DSK -OutLog $bout -ErrLog $berr `
      -ArgList @('--win',"--$Arch",'--publish=never','-c.compression=store',"-c.directories.output=$attemptOut")
    W "    exit=$ec"
    $su = @(Get-ChildItem -LiteralPath $attemptOut -Filter '*Setup*.exe' -Recurse -File -EA 0)
    if ($ec -eq 0 -and $su.Count -gt 0) {
      $InstallerSrc = $su[0]
      $WinUnpacked  = Join-Path (Split-Path $InstallerSrc.FullName -Parent) $UNPACKED_DIR
      break
    }
    Tail $bout 15
    # 立刻清理失败尝试：每份 win-unpacked 约 1GB，7 次失败会填掉 7GB
    Remove-Item -LiteralPath $attemptOut -Recurse -Force -EA 0
    if ($a -lt $BuilderAttempts) { Start-Sleep -Seconds 20 }
  }
  if (-not $InstallerSrc) { Die "electron-builder $BuilderAttempts 次后均失败" $EXIT.builder }

  $DstExe = Join-Path $RELEASE $InstallerSrc.Name
  $ok = Invoke-WithRetry -Label 'copy installer' -Attempts 5 -DelaySec 4 -Action {
    Copy-Item -LiteralPath $InstallerSrc.FullName -Destination $DstExe -Force -EA Stop
  }
  if (-not $ok) { Die 'installer 拷回 release/ 失败' $EXIT.releaseCopy }
  $sha = (Get-FileHash -LiteralPath $DstExe -Algorithm SHA256).Hash

  # last-build.json 记录 win-unpacked 位置：release/ 里没有它，它在 %TEMP%。
  # 后续 -VerifyOnly 靠这个文件找到 app.jar（需配合 -KeepTemp 保留目录）。
  @{
    ts = $TS; installer = $DstExe; sha256 = $sha
    attemptOut = (Split-Path $InstallerSrc.FullName -Parent)
    winUnpacked = $WinUnpacked; keptTemp = [bool]$KeepTemp
  } | ConvertTo-Json | ForEach-Object {
    # 用 .NET 写而不是 Set-Content -Encoding UTF8：后者在 PS 5.1 会加 BOM，
    # 导致 python 的 json.load 等非 PowerShell 读取方直接报错。
    [IO.File]::WriteAllText($LAST, $_, (New-Object Text.UTF8Encoding $false))
  }
  W "  installer -> $DstExe"
  W "  SHA256 = $sha"
}

# ===========================================================================
# region 8  verify  — E2E：安装包里的 index.html 必须与源码逐字节一致
# ===========================================================================
if (Should-Run 'verify') {
  Wr "STEP verify  E2E SHA256（win-unpacked/app.jar 内的 static/index.html）"

  # 解析顺序：-InstallerPath > last-build.json > release/ 里最新的
  $vInstaller = $null
  if ($InstallerPath) {
    if (-not (Test-Path -LiteralPath $InstallerPath)) { Die "-InstallerPath 不存在：$InstallerPath" $EXIT.precondition }
    $vInstaller = Get-Item -LiteralPath $InstallerPath
    $WinUnpacked = Join-Path (Split-Path $vInstaller.FullName -Parent) $UNPACKED_DIR
  } elseif ($InstallerSrc) {
    # 本次刚构建：报告 release/ 下的正式副本，而不是 %TEMP% 里的中间产物
    $relCopy = Join-Path $RELEASE $InstallerSrc.Name
    $vInstaller = if (Test-Path -LiteralPath $relCopy) { Get-Item -LiteralPath $relCopy } else { $InstallerSrc }
  } elseif (Test-Path -LiteralPath $LAST) {
    $meta = Get-Content -LiteralPath $LAST -Raw -Encoding UTF8 | ConvertFrom-Json
    $WinUnpacked = $meta.winUnpacked
    if (Test-Path -LiteralPath $meta.installer) { $vInstaller = Get-Item -LiteralPath $meta.installer }
    W "  来自 last-build.json (ts=$($meta.ts))"
  }
  if (-not $vInstaller) {
    $cand = @(Get-ChildItem -LiteralPath $RELEASE -Filter '*Setup*.exe' -File -EA 0 | Sort-Object LastWriteTime -Descending)
    if ($cand.Count -gt 0) { $vInstaller = $cand[0] }
  }
  if (-not $vInstaller) { Die "找不到安装包（release/ 为空且无 last-build.json）" $EXIT.precondition }

  $upJar = if ($WinUnpacked) { Join-Path $WinUnpacked 'resources\app.jar' } else { $null }
  if (-not $upJar -or -not (Test-Path -LiteralPath $upJar)) {
    W "  [WARN] 找不到 win-unpacked/resources/app.jar（它在 %TEMP%，非 release/）"
    W "         下次构建加 -KeepTemp 才能事后 -VerifyOnly。跳过 E2E 哈希比对。"
  } else {
    $ujf = Get-Item -LiteralPath $upJar
    W "  win-unpacked app.jar = $([math]::Round($ujf.Length/1MB,2)) MB  mtime=$($ujf.LastWriteTime)"
    $z = [IO.Compression.ZipFile]::OpenRead($upJar)
    try {
      $statics = @($z.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/static/*' }).Count
      $idx = $z.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/static/index.html' }
      W "  BOOT-INF static 条目数 = $statics"
      if (-not $idx) { Die 'win-unpacked app.jar 里没有 BOOT-INF/classes/static/index.html' $EXIT.e2e }
      $stm = $idx.Open()
      try {
        $buf = New-Object byte[] $idx.Length
        [void]$stm.Read($buf, 0, $idx.Length)
      } finally { $stm.Dispose() }
      $hasher = [Security.Cryptography.SHA256]::Create()
      try {
        $shaJar = [BitConverter]::ToString($hasher.ComputeHash($buf)).Replace('-','').ToLower()
        $srcBuf = [IO.File]::ReadAllBytes((Join-Path $SRC_STATIC 'index.html'))
        $shaSrc = [BitConverter]::ToString($hasher.ComputeHash($srcBuf)).Replace('-','').ToLower()
      } finally { $hasher.Dispose() }
      W "  JAR index SHA256 = $shaJar"
      W "  SRC index SHA256 = $shaSrc"
      $match = ($shaJar -eq $shaSrc)
      W "  MATCH = $match"
      if (-not $match) { Die '安装包里的前端与源码不一致（构建链路某处用了旧产物）' $EXIT.e2e }
    } finally { $z.Dispose() }
  }

  # 校验完成后清理胜出的临时目录（除非 -KeepTemp）
  if (-not $KeepTemp -and $WinUnpacked -and (Test-Path -LiteralPath $WinUnpacked)) {
    $parent = Split-Path $WinUnpacked -Parent
    if ($parent -like "$env:TEMP*") {
      Remove-Item -LiteralPath $parent -Recurse -Force -EA 0
      W "  已清理临时目录 $parent（加 -KeepTemp 可保留）"
    }
  }

  # region 9  Summary
  Wr 'FINAL PRODUCT'
  $fi = Get-Item -LiteralPath $vInstaller.FullName -EA 0
  if ($fi) {
    W "  PATH   = $($fi.FullName)"
    W "  SIZE   = $([math]::Round($fi.Length/1MB,2)) MB"
    W "  MTIME  = $($fi.LastWriteTime)"
    W "  SHA256 = $((Get-FileHash -LiteralPath $fi.FullName -Algorithm SHA256).Hash)"
  }
}

W ''
W "=== ALL DONE @ $(Get-Date -F 'yyyy-MM-dd HH:mm:ss') ==="
W "日志：$MAST"
exit 0
