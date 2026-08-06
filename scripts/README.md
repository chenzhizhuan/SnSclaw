# 桌面端打包脚本

- `build-desktop.ps1` —— Windows，产出 `智算方舟_<版本>_<arch>_Setup.exe`
- `build-desktop-mac.sh` —— macOS，产出 `.dmg` / `.zip`（**必须在 Mac 上运行**）

双击根目录 `RUN_LOCAL_PIPELINE.cmd` 即可，它把参数透传给 `build-desktop.ps1`。

```powershell
# 全量构建（干净检出唯一正确选择）
.\scripts\build-desktop.ps1

# 只改了前端，后端 .java 没动 —— 跳过 javac
.\scripts\build-desktop.ps1 -Fast

# 只想重打安装包
.\scripts\build-desktop.ps1 -From installer

# 看会做什么但不真做
.\scripts\build-desktop.ps1 -WhatIf

# ARM64 安装包（先备好 arm64 的 JRE，见下）
.\scripts\build-desktop.ps1 -Arch arm64
```

## 架构

`-Arch x64`（默认）或 `-Arch arm64`。构建前需要对应架构的 JRE：

```bash
bash mateclaw-desktop/scripts/download-jre.sh --os win --arch arm64
```

缺了会在 installer 步骤提前失败——否则会打出一个没有 JRE 的安装包，装完才发现起不来。

两个与架构相关的坑，都是实测踩出来的：

1. **`win.target` 由 `WIN_ARCH` 环境变量驱动**，不要在里面同时声明两个架构。命令行的
   `--arm64` 是与声明目标取**并集**而非过滤，同时声明会打出两个架构外加一个 640 MB 的
   合并安装包。`build-desktop.ps1` 会自动设这个变量。
2. **unpacked 目录名带架构后缀，但只对非默认架构加**：x64 是 `win-unpacked`，
   arm64 是 `win-arm64-unpacked`（electron-builder 的 `getArchSuffix()` 行为）。
   脚本里用 `$UNPACKED_DIR` 统一推导，不要写死。

打包后 `app.jar` 会从 405 MB 缩到约 237 MB，是 `afterPack` 钩子按平台裁剪 Playwright
driver 的正常行为。Windows 上**即使 arm64 也保留 `driver/win32_x64`**——Playwright 没有
出 Windows ARM 版驱动，ARM 机器靠 x64 模拟层跑，这个不要"修"。

## macOS

```bash
scripts/build-desktop-mac.sh --to vite       # 分段：先验前端
scripts/build-desktop-mac.sh --from maven    # 再往后
scripts/build-desktop-mac.sh --arch both     # arm64 + x64 两个 dmg
scripts/build-desktop-mac.sh --mode remote   # 瘦包，不含 JRE/JAR
```

`.dmg` **只能在 macOS 上构建**：electron-builder 在 Windows 上会直接
`throw`（`Build for macOS is supported only on macOS`），做磁盘镜像和 `codesign`
需要苹果工具链，没有任何参数能绕过。

产物未签名未公证，用户装完必须先清隔离标记再打开，顺序不能反：

```bash
xattr -cr /Applications/SnSclaw.app   # 先这个
open /Applications/SnSclaw.app        # 再打开
```

要免掉这一步需要付费 Apple Developer 账号：`CSC_LINK` + `CSC_KEY_PASSWORD` 做签名，
`APPLE_ID` + `APPLE_APP_SPECIFIC_PASSWORD` + `APPLE_TEAM_ID` 做公证。
`hardenedRuntime` 和 entitlements 已配好，只缺签名本身。

## 流水线七步

| 步 | 动作 | 产出 | 实测耗时 |
|---|---|---|---|
| `typecheck` | `vue-tsc --noEmit`（mateclaw-ui） | — | 73s |
| `vite` | `vite build` | `mateclaw-server/src/main/resources/static/` 281 文件 / 19.6 MB | 179s |
| `maven` | `mvn -B package` | `mateclaw-server/target/mateclaw-server-1.0.0-SNAPSHOT.jar` 405 MB | 87s（`-Fast`）/ 20 min+（全量） |
| `jar` | 拷到 `mateclaw-desktop/resources/app.jar` + BOOT-INF 审计 | app.jar 405 MB | ~15s |
| `desktop` | `npm run build` + `require("ws")` 外置校验 | `dist-electron/main/index.js` | 45s |
| `installer` | `electron-builder --win --x64` | `mateclaw-desktop/release/*_Setup.exe` 321 MB | ~2.5 min |
| `verify` | E2E SHA256 比对 | — | ~5s |

## ⚠ 没有 `mateclaw-ui/dist/`

[`mateclaw-ui/vite.config.ts`](../mateclaw-ui/vite.config.ts) 的 `outDir` 是
`../mateclaw-server/src/main/resources/static`，且 `emptyOutDir: true`。**vite 直接写进后端
static 目录**，不存在 `mateclaw-ui/dist/`。

历史上三个 `pipeline_local_*.ps1` 就是因为去找 `$UI/dist/index.html` 而永久失败——
`vite` 退出码 0，紧接着报 `no dist\index.html`。任何新脚本都不要再假设有 `dist/`。

另注 `static/` 被 gitignore，新克隆必须从 `-From typecheck` 开始，不能直接 `-From maven`。

## 开关

| 开关 | 场景 |
|---|---|
| `-From <step>` | 从某步续跑。前置断言会验证上游产物存在且够新 |
| `-To <step>` | 跑到某步为止，例如 `-From installer -To installer` 跳过 verify |
| `-Fast` | 跳过 javac：手动拷 static 到 `target/classes` 再 repackage。**只在没改 `.java` 时有效** |
| `-SkipTypeCheck` | 省掉 73s。`desktop` 步的 `npm run build` 里本来还会再跑一次 vue-tsc |
| `-FailOnTypeError` | 类型错误时阻断（默认只警告） |
| `-VerifyOnly` | 等价 `-From verify -To verify`，校验已有产物不重建 |
| `-InstallerPath <p>` | 指定要校验的安装包 |
| `-KeepTemp` | 保留 `%TEMP%` 里的 `win-unpacked`，**事后想 `-VerifyOnly` 必须加** |
| `-AggressiveUnlock` | builder 前 touch 扫描每个文件释放 Defender 句柄；第 3 次起改名驱逐 `win-unpacked` |
| `-OutputDir <d>` | 固定 builder 输出根目录（默认每次尝试用全新 `%TEMP%` 子目录） |
| `-BuilderAttempts n` / `-MavenAttempts n` | 重试次数，默认 7 / 6 |
| `-Force` | 跳过全部前置断言。**见下方警告** |
| `-MinJarSizeMB` / `-MinStaticFiles` | 阈值下限，默认 350 / 150。主要给测试注入用 |
| `-Root <d>` | 仓库根，默认从 `$PSScriptRoot` 推导。测试用 |

## 退出码

| 码 | 含义 | 码 | 含义 |
|---|---|---|---|
| 0 | 成功 | 40 | app.jar 拷贝失败 |
| 10 | 参数或工具链问题 | 41 | desktop 构建失败 |
| 11 | 类型错误（仅 `-FailOnTypeError`） | 42 | ws 被内联进 bundle |
| 20 | vite 失败 | 50 | electron-builder 全部尝试失败 |
| 21 | vite 产出缺失或过少 | 51 | 安装包拷回 release/ 失败 |
| 30 | static→classes 同步失败 | 60 | E2E 哈希不匹配 |
| 31 | Maven 失败 | 70 | 前置断言失败 |
| 32 | JAR 审计失败 | | |

## `-Fast` 是正确性权衡，不只是快

`-Fast` 用 `-Dmaven.main.skip=true`，**完全不编译 Java**，只重新打包已有的
`target/classes`。它存在的原因是 maven-resources-plugin 逐个锁定 1443 个资源文件会
触发 Defender 扫描风暴，手动拷贝 281 个文件只要几秒。

脚本会断言没有 `.java` 比最新 `.class` 新，不满足就拒绝。但 `-Force` 能压掉这个断言：
**`-Force -Fast` 在改过后端之后会打出一个装着旧后端的安装包**，而且构建全绿。

改了 Java 就别加 `-Fast`。

## Windows Defender 相关设计

这些不是过度设计，是 28 个历史脚本换来的：

- **`-c.compression=store`** —— 不压缩。压缩阶段长时间持有文件句柄，最容易撞 Defender。
- **每次尝试用全新 `%TEMP%` 子目录** —— Defender 对新目录是"冷"的。复用 `release/` 会因
  上次残留的 `win-unpacked` 句柄直接 EPERM。失败的尝试目录**立即删除**：每份约 1 GB，
  7 次失败就是 7 GB。
- **`[GC]::Collect()` + sleep** —— 释放本进程持有的 zip 句柄。审计打开过 405 MB 的 jar。
- **`7za.exe` / `7za_original.exe` guard** —— electron-builder 和 Defender 都会重命名
  `node_modules/7zip-bin/win/x64/7za.exe`。preflight 每次从备份恢复。
- **`-AggressiveUnlock`** —— 逐个文件读 1 字节，逼 Defender 完成后台扫描并释放句柄；
  第 3 次尝试起把 `win-unpacked` 改名成 GUID 再删。只在反复 EPERM 时才需要。
- **Maven 重试时选择性清理** `target/classes/{skills,pptx,db,i18n,static}` —— 这几个目录
  文件最多，最常被锁。

## 两个 helper 为什么是独立 `.ps1`

`step4_audit_jar.ps1` 和 `step5_check_ws_external.ps1` 通过
`powershell.exe -File` 以**子进程**方式调用，不是内联函数。

原因：`[IO.Compression.ZipFile]::OpenRead` 打开 405 MB 的 app.jar 会持有文件句柄，而下一步
正要拷这个文件。就地 `Dispose()` 通常够用，但进程退出是 Defender 施压下唯一确定的释放方式。
这个进程边界是免费的保险，**请不要"顺手"把它们内联掉**。

它们的参数签名（`-JarPath` / `-MainJs` / `-LogPath`）和退出码被 `build-desktop.ps1` 依赖。

## 校验能证明什么，不能证明什么

`verify` 步比对 `win-unpacked/resources/app.jar` 里
`BOOT-INF/classes/static/index.html` 与源文件的 SHA256。**这只覆盖前端**——它能证明
vite 产物完整穿过 Maven 打包和 electron-builder 到达安装包。

**后端改动没有等价检查。** JAR 审计的阈值（`libs>250`、`static>250`、`classes>1200`）
只能证明 fat jar 不是空的，不能证明你这次的 Java 改动编进去了。全绿 ≠ 你的后端改动上车了。

## 日志

`.build-logs/`（已 gitignore）：

- `ALL_<epoch>.log` —— 主日志，UTF-8 无 BOM。**用 `-Encoding UTF8` 读**，否则 PS 5.1
  按 ANSI 码页解码，中文产物名会乱码。
- `all-{tsc,vite,mvn,desk,eb}-{out,err}-<epoch>[-<attempt>].log` —— 各步原始输出
- `last-build.json` —— 记录 `win-unpacked` 在 `%TEMP%` 的位置，供事后 `-VerifyOnly` 使用

旧脚本每次运行都 `Remove-Item *.log` 清空日志目录，新脚本**不会**。

## 脚本编码：必须带 UTF-8 BOM

`build-desktop.ps1` 含中文，必须**保存为 UTF-8 with BOM**。PowerShell 5.1 遇到无 BOM 的
UTF-8 会按 GBK 解码，中文字符串字面量被截断成非法 token，报一堆莫名其妙的
"意外的标记 `}`"。改完这个文件如果突然大量语法错，先查 BOM。

## 历史

这个目录曾有 30 个 `.ps1`，根目录另有 3 个 `pipeline_local_*.ps1`。它们是同一条流水线的
历代变体——每次被 Defender 卡住就诞生一个"从第 N 步续跑"的新脚本。全部诉求现已收进
`build-desktop.ps1` 的开关，脚本本身可从 git 历史取回（`git log -- scripts/`，
commit `6e41130a` 附近）。
