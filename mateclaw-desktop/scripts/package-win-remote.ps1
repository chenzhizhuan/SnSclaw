$ErrorActionPreference = "Stop"

$env:ELECTRON_MIRROR = "https://npmmirror.com/mirrors/electron/"
$env:ELECTRON_BUILDER_BINARIES_MIRROR = "https://npmmirror.com/mirrors/electron-builder-binaries/"
$env:BUILD_MODE = "remote"

& npx electron-builder --win --x64

