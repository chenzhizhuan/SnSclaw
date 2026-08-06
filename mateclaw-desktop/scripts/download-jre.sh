#!/usr/bin/env bash
#
# scripts/download-jre.sh — Download a Temurin 21 JRE and extract it into
# resources/jre/<os>-<arch>/ for electron-builder to bundle.
#
# The directory name must be "<os>-<arch>" because electron-builder.cjs declares
#   extraResources: [{ from: 'resources/jre/${os}-${arch}/', to: 'jre/' }]
# and app-builder-lib expands ${os} to its buildConfigurationKey — 'mac', 'win'
# or 'linux' (NOT process.platform, so it is 'win', never 'win32').
#
# Usage:
#   scripts/download-jre.sh                      # host os, host arch
#   scripts/download-jre.sh arm64                # host os, arm64  (back-compat)
#   scripts/download-jre.sh x64                  # host os, x64    (back-compat)
#   scripts/download-jre.sh all                  # host os, both arches
#   scripts/download-jre.sh --os win --arch all  # explicit os + arch
#   scripts/download-jre.sh --os all --arch all  # every supported combination
#
# Supported: mac|win|linux × x64|arm64. Temurin publishes a JRE for all six.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JRE_DIR="$PROJECT_ROOT/resources/jre"

JAVA_FEATURE_VERSION=21

# ─── Argument parsing ─────────────────────────────────────────────────────────
# Bare "arm64" / "x64" / "all" is kept as an arch shorthand so existing callers
# and docs (scripts/build-desktop-mac.sh passes a bare arch) keep working.
OS_SEL=""
ARCH_SEL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --os)   OS_SEL="${2:-}";   shift 2 ;;
    --arch) ARCH_SEL="${2:-}"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    arm64|x64|all) ARCH_SEL="$1"; shift ;;
    auto) ARCH_SEL="auto"; shift ;;
    *) echo "ERROR: unknown argument '$1' (try --help)" >&2; exit 1 ;;
  esac
done

host_os() {
  case "$(uname -s)" in
    Darwin)                 echo mac ;;
    Linux)                  echo linux ;;
    MINGW*|MSYS*|CYGWIN*)   echo win ;;
    *) echo "ERROR: cannot determine host OS from '$(uname -s)'" >&2; return 1 ;;
  esac
}

host_arch() {
  case "$(uname -m)" in
    arm64|aarch64)   echo arm64 ;;
    x86_64|amd64)    echo x64 ;;
    *) echo "ERROR: cannot determine host arch from '$(uname -m)'" >&2; return 1 ;;
  esac
}

# Written as explicit ifs, not `[ ... ] && VAR=...`: under `set -e` a short-
# circuiting AND-list has subtle exit-status semantics that differ between bash
# versions, and macOS still ships bash 3.2.
if [ -z "$OS_SEL" ]; then OS_SEL="$(host_os)"; fi
if [ -z "$ARCH_SEL" ] || [ "$ARCH_SEL" = "auto" ]; then ARCH_SEL="$(host_arch)"; fi

case "$OS_SEL" in
  mac|win|linux) OS_LIST=("$OS_SEL") ;;
  all)           OS_LIST=(mac win linux) ;;
  *) echo "ERROR: --os must be mac, win, linux or all (got '$OS_SEL')" >&2; exit 1 ;;
esac

case "$ARCH_SEL" in
  x64|arm64) ARCH_LIST=("$ARCH_SEL") ;;
  all)       ARCH_LIST=(x64 arm64) ;;
  *) echo "ERROR: --arch must be x64, arm64 or all (got '$ARCH_SEL')" >&2; exit 1 ;;
esac

# ─── Download + extract one os/arch pair ──────────────────────────────────────
# Two naming systems that do NOT agree — keep them strictly separate:
#   * folder names use electron-builder's vocabulary: win / mac / linux + x64 / arm64
#   * the Adoptium API wants:                        windows / mac / linux + x64 / aarch64
# Using 'win' or 'arm64' against the API yields a 404.
adoptium_arch() { if [ "$1" = "arm64" ]; then echo aarch64; else echo x64; fi; }
adoptium_os()   { if [ "$1" = "win" ];   then echo windows; else echo "$1"; fi; }

# Where the java binary lands inside the folder, per platform. macOS tarballs
# carry a Contents/Home/ bundle wrapper; win/linux are flat.
java_rel_path() {
  case "$1" in
    mac) echo "Contents/Home/bin/java" ;;
    win) echo "bin/java.exe" ;;
    *)   echo "bin/java" ;;
  esac
}

download_and_extract() {
  local os="$1" arch="$2"
  local aarch aos folder url ext tmpfile extract_tmp extracted_dir java_bin
  aarch="$(adoptium_arch "$arch")"
  aos="$(adoptium_os "$os")"
  folder="$os-$arch"
  if [ "$os" = "win" ]; then ext="zip"; else ext="tar.gz"; fi
  url="https://api.adoptium.net/v3/binary/latest/${JAVA_FEATURE_VERSION}/ga/${aos}/${aarch}/jre/hotspot/normal/eclipse?project=jdk"
  tmpfile="$JRE_DIR/.jre-$folder.$ext"

  echo "==> Downloading Temurin ${JAVA_FEATURE_VERSION} JRE for $os/$arch"
  mkdir -p "$JRE_DIR"
  curl -L --fail --retry 2 -o "$tmpfile" "$url"

  echo "==> Extracting to $JRE_DIR/$folder"
  rm -rf "$JRE_DIR/$folder"
  mkdir -p "$JRE_DIR/$folder"

  extract_tmp="$JRE_DIR/.tmp-$folder"
  rm -rf "$extract_tmp"; mkdir -p "$extract_tmp"
  if [ "$ext" = "zip" ]; then
    unzip -q "$tmpfile" -d "$extract_tmp"
  else
    tar -xzf "$tmpfile" -C "$extract_tmp"
  fi

  # Every Temurin archive nests everything under a single jdk-* directory.
  extracted_dir="$(find "$extract_tmp" -maxdepth 1 -type d -name 'jdk-*' | head -1)"

  if [ "$os" = "mac" ]; then
    # Move the inner Contents/ up so the result is <folder>/Contents/Home/...
    if [ -n "$extracted_dir" ] && [ -d "$extracted_dir/Contents" ]; then
      mv "$extracted_dir/Contents" "$JRE_DIR/$folder/Contents"
    elif [ -d "$extract_tmp/Contents" ]; then
      mv "$extract_tmp/Contents" "$JRE_DIR/$folder/Contents"
    else
      echo "ERROR: could not locate Contents/ in the macOS archive" >&2
      rm -rf "$extract_tmp" "$tmpfile"; exit 1
    fi
  else
    # Flat layout: hoist the contents of jdk-*/ into <folder>/ directly.
    if [ -n "$extracted_dir" ]; then
      # shellcheck disable=SC2086
      ( shopt -s dotglob; mv "$extracted_dir"/* "$JRE_DIR/$folder/" )
    elif [ -d "$extract_tmp/bin" ]; then
      ( shopt -s dotglob; mv "$extract_tmp"/* "$JRE_DIR/$folder/" )
    else
      echo "ERROR: could not locate bin/ in the $os archive" >&2
      rm -rf "$extract_tmp" "$tmpfile"; exit 1
    fi
  fi

  rm -rf "$extract_tmp" "$tmpfile"

  java_bin="$JRE_DIR/$folder/$(java_rel_path "$os")"
  if [ -f "$java_bin" ]; then
    echo "==> OK: $java_bin"
  else
    echo "ERROR: java binary not found at $java_bin" >&2
    exit 1
  fi
}

for os in "${OS_LIST[@]}"; do
  for arch in "${ARCH_LIST[@]}"; do
    download_and_extract "$os" "$arch"
  done
done

echo "==> Done. Contents of $JRE_DIR:"
ls -1 "$JRE_DIR"
