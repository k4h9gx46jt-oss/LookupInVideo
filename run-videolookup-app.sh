#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${PORT:-8080}"
BUILD_ONLY=false

if [[ "${1:-}" == "--build-only" ]]; then
  BUILD_ONLY=true
fi

# --- Logging setup ---------------------------------------------------------
# Maven -X is extremely verbose; persist everything to a timestamped log so we
# can post-mortem unexpected exits (e.g. exit 137 = SIGKILL, often the macOS
# memory pressure killer when -Xmx is set too high).
LOG_DIR="${SCRIPT_DIR}/logs"
mkdir -p "${LOG_DIR}"
TS="$(date +%Y%m%d-%H%M%S)"
BUILD_LOG="${LOG_DIR}/build-${TS}.log"
RUN_LOG="${LOG_DIR}/run-${TS}.log"

echo "[LookupInVideo] Build log:  ${BUILD_LOG}"
echo "[LookupInVideo] Run log:    ${RUN_LOG}"

# Snapshot environment that matters for OOM / crash investigation.
{
  echo "=== ENVIRONMENT SNAPSHOT @ ${TS} ==="
  echo "uname: $(uname -a)"
  echo "JAVA_HOME=${JAVA_HOME:-<unset>}"
  java -version 2>&1 || true
  echo "--- ulimit -a ---"
  ulimit -a || true
  echo "--- physical memory (MB) ---"
  if command -v sysctl >/dev/null 2>&1; then
    HW_MEM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo 0)
    echo "hw.memsize=${HW_MEM_BYTES}  (~$((HW_MEM_BYTES / 1024 / 1024)) MB)"
  fi
  echo "==================================="
} | tee -a "${BUILD_LOG}"

echo "[LookupInVideo] Build indul (mvn -X)..."
./mvnw -X -DskipTests compile 2>&1 | tee -a "${BUILD_LOG}"

echo "[LookupInVideo] Build kesz."
if [[ "$BUILD_ONLY" == true ]]; then
  echo "[LookupInVideo] Build-only mod aktiv, kilepes."
  exit 0
fi

# JVM args: keep behavior overridable via env JVM_HEAP_GB without editing the
# script. Default lowered to 12 GB — 28 GB on a 16 GB-class machine triggered
# exit 137 (SIGKILL by macOS jetsam / memory pressure killer).
JVM_HEAP_GB="${JVM_HEAP_GB:-12}"
JVM_ARGS=(
  "-Xms${JVM_HEAP_GB}g"
  "-Xmx${JVM_HEAP_GB}g"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=${LOG_DIR}"
  "-XX:+ExitOnOutOfMemoryError"
  "-Xlog:gc*:file=${LOG_DIR}/gc-${TS}.log:time,uptime,level,tags:filecount=5,filesize=20M"
  "-Dserver.port=${PORT}"
)
JVM_ARG_STRING="${JVM_ARGS[*]}"

echo "[LookupInVideo] Alkalmazas indul: http://localhost:${PORT}"
echo "[LookupInVideo] JVM args: ${JVM_ARG_STRING}"

# Trap any non-zero exit so we always print a hint about exit 137.
trap '
  EC=$?
  if [[ $EC -ne 0 ]]; then
    echo ""
    echo "[LookupInVideo] !!! Exit code: $EC"
    if [[ $EC -eq 137 ]]; then
      echo "[LookupInVideo] Exit 137 = SIGKILL. Most likely cause on macOS:"
      echo "[LookupInVideo]   * memory pressure killer (jetsam) — lower JVM_HEAP_GB"
      echo "[LookupInVideo]   * manual `kill -9` on the java process"
      echo "[LookupInVideo] Check Console.app for entries like \"low swap\" or \"jetsam\"."
    fi
    echo "[LookupInVideo] Build log: ${BUILD_LOG}"
    echo "[LookupInVideo] Run log:   ${RUN_LOG}"
  fi
' EXIT

# `exec` would replace the shell and bypass `tee`, so run normally and tee.
./mvnw -X spring-boot:run -Dspring-boot.run.jvmArguments="${JVM_ARG_STRING}" 2>&1 | tee -a "${RUN_LOG}"
