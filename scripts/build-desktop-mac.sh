#!/usr/bin/env bash
#
# scripts/build-desktop-mac.sh — Build the SnSclaw macOS desktop installer (.dmg).
#
# The macOS counterpart of build-desktop.ps1. Same five-stage shape:
#   typecheck -> vite -> maven -> jre -> installer
#
# MUST RUN ON macOS. electron-builder cannot produce a .dmg from Windows or
# Linux: creating an HFS+/APFS disk image and running codesign both require
# Apple tooling that only ships with macOS.
#
# Usage:
#   scripts/build-desktop-mac.sh                     # local build, host arch
#   scripts/build-desktop-mac.sh --arch both         # universal-ish: arm64 + x64 dmgs
#   scripts/build-desktop-mac.sh --mode remote       # thin build, no JRE/JAR (~530MB smaller)
#   scripts/build-desktop-mac.sh --from vite         # resume from a stage
#   scripts/build-desktop-mac.sh --to maven          # stop after a stage
#   scripts/build-desktop-mac.sh --skip-typecheck    # skip vue-tsc (slow)
#
# Exit codes mirror build-desktop.ps1's convention so a wrapper can branch:
#   10 args  11 typecheck  20 vite  30 maven  40 jre  50 installer  70 precondition
#
set -uo pipefail

# ─── Paths ────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
UI="$ROOT/mateclaw-ui"
SRV="$ROOT/mateclaw-server"
DESK="$ROOT/mateclaw-desktop"
SRC_STATIC="$SRV/src/main/resources/static"
LOG_DIR="$ROOT/.build-logs"

E_ARGS=10; E_TYPECHECK=11; E_VITE=20; E_MAVEN=30; E_JRE=40; E_INSTALLER=50; E_PRECOND=70

STAGES=(typecheck vite maven jre installer)
MODE="local"
ARCH="host"
FROM=""; TO=""
SKIP_TYPECHECK=0
MIN_STATIC_FILES=50

# ─── Logging ──────────────────────────────────────────────────────────────────
mkdir -p "$LOG_DIR"
STAMP="$(date +%s)"
MAIN_LOG="$LOG_DIR/MAC_$STAMP.log"

log()  { printf '%s\n' "$*" | tee -a "$MAIN_LOG"; }
step() { printf '\n=== STEP %s ===\n' "$*" | tee -a "$MAIN_LOG"; }
die()  { printf '\nFATAL: %s\n' "$1" | tee -a "$MAIN_LOG" >&2; exit "${2:-1}"; }

# ─── Args ─────────────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --mode)  MODE="${2:-}"; shift 2 ;;
    --arch)  ARCH="${2:-}"; shift 2 ;;
    --from)  FROM="${2:-}"; shift 2 ;;
    --to)    TO="${2:-}";   shift 2 ;;
    --skip-typecheck) SKIP_TYPECHECK=1; shift ;;
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) die "unknown argument: $1 (try --help)" $E_ARGS ;;
  esac
done

case "$MODE" in local|remote) ;; *) die "--mode must be local or remote" $E_ARGS ;; esac
case "$ARCH" in host|arm64|x64|both) ;; *) die "--arch must be host, arm64, x64 or both" $E_ARGS ;; esac

stage_index() {
  local i=0
  for s in "${STAGES[@]}"; do [ "$s" = "$1" ] && { echo "$i"; return 0; }; i=$((i+1)); done
  return 1
}
FROM_IDX=0; TO_IDX=$(( ${#STAGES[@]} - 1 ))
if [ -n "$FROM" ]; then FROM_IDX="$(stage_index "$FROM")" || die "--from: unknown stage '$FROM'" $E_ARGS; fi
if [ -n "$TO" ];   then TO_IDX="$(stage_index "$TO")"     || die "--to: unknown stage '$TO'"   $E_ARGS; fi
[ "$FROM_IDX" -le "$TO_IDX" ] || die "--from stage comes after --to stage" $E_ARGS

should_run() { local i; i="$(stage_index "$1")" || return 1; [ "$i" -ge "$FROM_IDX" ] && [ "$i" -le "$TO_IDX" ]; }

# ─── Preconditions ────────────────────────────────────────────────────────────
step "preconditions"

[ "$(uname -s)" = "Darwin" ] || die "this script must run on macOS — electron-builder cannot build a .dmg elsewhere" $E_PRECOND

for d in "$UI" "$SRV" "$DESK"; do
  [ -d "$d" ] || die "missing directory: $d" $E_PRECOND
done

command -v node >/dev/null 2>&1 || die "node not found on PATH" $E_PRECOND
command -v mvn  >/dev/null 2>&1 || die "mvn not found on PATH" $E_PRECOND

# Java 21 is mandatory (maven.compiler.release=21). Fail loudly rather than
# letting javac emit a confusing release-version error 300 lines into the build.
if should_run maven; then
  JAVA_MAJOR="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  [ "${JAVA_MAJOR:-0}" -ge 21 ] 2>/dev/null \
    || die "Java 21+ required for the server build (found '${JAVA_MAJOR:-none}'). Set JAVA_HOME to a JDK 21." $E_PRECOND
fi

[ -d "$UI/node_modules" ]   || die "mateclaw-ui/node_modules missing — run: cd mateclaw-ui && pnpm install" $E_PRECOND
[ -d "$DESK/node_modules" ] || die "mateclaw-desktop/node_modules missing — run: cd mateclaw-desktop && npm install" $E_PRECOND

log "mode=$MODE arch=$ARCH stages=[${STAGES[*]:$FROM_IDX:$((TO_IDX-FROM_IDX+1))}]"
log "log: $MAIN_LOG"

# ─── 1. typecheck ─────────────────────────────────────────────────────────────
# Uses node + the local binary directly (not `pnpm build`), because that npm
# script also invokes scripts/check-snowflake-precision.sh, which is absent from
# the repo and would abort the build. Same workaround build-desktop.ps1 uses.
if should_run typecheck && [ "$SKIP_TYPECHECK" -eq 0 ]; then
  step "typecheck (vue-tsc --noEmit)"
  ( cd "$UI" && node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit ) \
    >>"$LOG_DIR/mac-tsc-$STAMP.log" 2>&1 \
    || die "vue-tsc reported type errors — see $LOG_DIR/mac-tsc-$STAMP.log" $E_TYPECHECK
  log "typecheck OK"
elif should_run typecheck; then
  log "typecheck SKIPPED (--skip-typecheck)"
fi

# ─── 2. vite ──────────────────────────────────────────────────────────────────
# NOTE: vite.config.ts sets outDir to ../mateclaw-server/src/main/resources/static
# with emptyOutDir — this writes INTO the backend source tree. There is no
# mateclaw-ui/dist/. The Maven stage then packages that directory into the JAR,
# so vite MUST run before maven.
if should_run vite; then
  step "vite build (outputs into mateclaw-server static/)"
  ( cd "$UI" && node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build ) \
    >>"$LOG_DIR/mac-vite-$STAMP.log" 2>&1 \
    || die "vite build failed — see $LOG_DIR/mac-vite-$STAMP.log" $E_VITE
  [ -f "$SRC_STATIC/index.html" ] || die "vite finished but $SRC_STATIC/index.html is missing" $E_VITE
  log "vite OK ($(find "$SRC_STATIC" -type f | wc -l | tr -d ' ') files)"
fi

# ─── 3. maven ─────────────────────────────────────────────────────────────────
if should_run maven; then
  step "maven package (fat JAR)"
  if [ ! -f "$SRC_STATIC/index.html" ]; then
    die "static/index.html missing — run the vite stage first (its outDir is the backend static dir)" $E_PRECOND
  fi
  if [ "$(find "$SRC_STATIC" -type f | wc -l | tr -d ' ')" -lt "$MIN_STATIC_FILES" ]; then
    die "static/ has suspiciously few files — vite output looks incomplete" $E_PRECOND
  fi
  ( cd "$SRV" && mvn -B clean package -DskipTests -Dmaven.test.skip=true ) \
    >>"$LOG_DIR/mac-mvn-$STAMP.log" 2>&1 \
    || die "maven package failed — see $LOG_DIR/mac-mvn-$STAMP.log" $E_MAVEN

  JAR="$(ls -1 "$SRV"/target/mateclaw-server-*.jar 2>/dev/null | grep -v '\.original$' | head -1)"
  [ -n "$JAR" ] || die "no JAR produced in $SRV/target" $E_MAVEN
  mkdir -p "$DESK/resources"
  cp "$JAR" "$DESK/resources/app.jar"
  log "maven OK — app.jar $(du -h "$DESK/resources/app.jar" | cut -f1 | tr -d ' ')"
fi

# ─── 4. jre ───────────────────────────────────────────────────────────────────
# Only local builds embed a JRE. electron-builder's extraResources reads
# resources/jre/${os}-${arch}/, and the runtime looks for
# jre/Contents/Home/bin/java once packaged — which is exactly the layout
# scripts/download-jre.sh produces.
if should_run jre; then
  if [ "$MODE" = "remote" ]; then
    step "jre SKIPPED (remote mode bundles no JRE)"
  else
    step "jre (Temurin 21 for macOS)"
    need_arches=()
    case "$ARCH" in
      both) need_arches=(arm64 x64) ;;
      host) [ "$(uname -m)" = "arm64" ] && need_arches=(arm64) || need_arches=(x64) ;;
      *)    need_arches=("$ARCH") ;;
    esac
    for a in "${need_arches[@]}"; do
      if [ -x "$DESK/resources/jre/mac-$a/Contents/Home/bin/java" ]; then
        log "jre present: mac-$a"
      else
        log "fetching JRE for mac-$a"
        ( cd "$DESK" && bash scripts/download-jre.sh "$a" ) \
          >>"$LOG_DIR/mac-jre-$STAMP.log" 2>&1 \
          || die "download-jre.sh failed for $a — see $LOG_DIR/mac-jre-$STAMP.log" $E_JRE
      fi
    done
    log "jre OK"
  fi
fi

# ─── 5. installer ─────────────────────────────────────────────────────────────
if should_run installer; then
  step "electron-builder --mac (dmg + zip)"
  if [ "$MODE" = "local" ] && [ ! -f "$DESK/resources/app.jar" ]; then
    die "resources/app.jar missing — run the maven stage (or use --mode remote)" $E_PRECOND
  fi

  eb_args=(--mac)
  case "$ARCH" in
    both)  eb_args+=(--arm64 --x64) ;;
    arm64) eb_args+=(--arm64) ;;
    x64)   eb_args+=(--x64) ;;
    host)  [ "$(uname -m)" = "arm64" ] && eb_args+=(--arm64) || eb_args+=(--x64) ;;
  esac

  # The desktop app's own `npm run build` compiles the Electron main/preload
  # bundles; electron-builder only packages what that produced.
  ( cd "$DESK" && npm run build ) >>"$LOG_DIR/mac-desk-$STAMP.log" 2>&1 \
    || die "desktop (electron) build failed — see $LOG_DIR/mac-desk-$STAMP.log" $E_INSTALLER

  ( cd "$DESK" && BUILD_MODE="$MODE" npx electron-builder "${eb_args[@]}" ) \
    >>"$LOG_DIR/mac-eb-$STAMP.log" 2>&1 \
    || die "electron-builder failed — see $LOG_DIR/mac-eb-$STAMP.log" $E_INSTALLER

  log "installer OK"
  # Parenthesise the -o group: without it, `find` applies -maxdepth to only the
  # first expression and silently drops half the matches.
  find "$DESK/release" -maxdepth 2 \( -name '*.dmg' -o -name '*.zip' \) 2>/dev/null \
    | while read -r f; do log "  artifact: $f ($(du -h "$f" | cut -f1 | tr -d ' '))"; done
fi

# ─── Done ─────────────────────────────────────────────────────────────────────
step "done"
log "Artifacts under: $DESK/release"
cat <<'NOTE' | tee -a "$MAIN_LOG"

── Unsigned build: what your users must do ───────────────────────────────
This build is NOT signed with an Apple Developer ID and NOT notarized, so
macOS Gatekeeper quarantines it. After dragging the app to /Applications a
user must strip the quarantine attribute once:

    xattr -cr /Applications/SnSclaw.app

To remove that step, you need a paid Apple Developer account, then set
CSC_LINK / CSC_KEY_PASSWORD (Developer ID Application cert) plus
APPLE_ID / APPLE_APP_SPECIFIC_PASSWORD / APPLE_TEAM_ID and add a notarize
step. hardenedRuntime and the entitlements files are already configured in
electron-builder.cjs, so signing is the only missing piece.
NOTE
