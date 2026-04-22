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
# script. Default lowered to 6 GB — 28 GB on a 36 GB machine triggered
# exit 137 (SIGKILL by macOS jetsam / memory pressure killer).
#
# THE ACTUAL ROOT CAUSE of the recurring exit 137 was NOT the Java heap, it
# was native memory growth from JavaCPP. OpenCV calls return many small native
# objects per frame (Scalar from mean(), Moments from moments(), Rect from
# intersectRect(), Point2d from phaseCorrelate, ROI views, ...). These are
# enqueued for deallocation but only freed when the JVM does a GC. With a
# small Java heap the GC almost never runs, so the JavaCPP queue grows until
# resident memory hits the kernel jetsam threshold and the process is SIGKILLed
# silently — no JVM exception, no hs_err, just exit 137.
#
# The official mitigation is the JavaCPP config knobs below:
#   * org.bytedeco.javacpp.maxPhysicalBytes — when total process RSS approaches
#     this, JavaCPP forces a GC and waits for pending finalizers (the cap that
#     actually prevents jetsam).
#   * org.bytedeco.javacpp.maxBytes — caps tracked native allocations.
#   * org.bytedeco.javacpp.nopointergc=false — keep finalizer-driven cleanup on.
#
# Heap configured with a small -Xms (lazy growth) and modest -Xmx, leaving
# plenty of headroom for native + ffmpeg buffers within an 18 GB total budget
# on a 36 GB machine.
JVM_HEAP_GB="${JVM_HEAP_GB:-6}"
JVM_HEAP_INIT_GB="${JVM_HEAP_INIT_GB:-1}"
# Total budget for native (JavaCPP-tracked) memory. Includes OpenCV Mat/UMat,
# ffmpeg decode buffers and small per-call native objects. Set so that
# heap + native + JVM overhead < physical RAM with comfortable jetsam margin.
JAVACPP_MAX_BYTES_GB="${JAVACPP_MAX_BYTES_GB:-8}"
# Hard ceiling on total RSS that JavaCPP enforces by triggering GC on growth.
# Must be > heap + native budget; should be < physical RAM minus 8-12 GB for
# the OS, IDE, browser etc.
JAVACPP_MAX_PHYSICAL_GB="${JAVACPP_MAX_PHYSICAL_GB:-18}"
JVM_ARGS=(
  "-Xms${JVM_HEAP_INIT_GB}g"
  "-Xmx${JVM_HEAP_GB}g"
  "-XX:MaxDirectMemorySize=2g"
  "-XX:+HeapDumpOnOutOfMemoryError"
  "-XX:HeapDumpPath=${LOG_DIR}"
  "-XX:+ExitOnOutOfMemoryError"
  "-Xlog:gc*:file=${LOG_DIR}/gc-${TS}.log:time,uptime,level,tags:filecount=5,filesize=20M"
  "-Dserver.port=${PORT}"
  # --- JavaCPP native-memory caps (the actual fix for exit 137) ---
  "-Dorg.bytedeco.javacpp.maxBytes=${JAVACPP_MAX_BYTES_GB}g"
  "-Dorg.bytedeco.javacpp.maxPhysicalBytes=${JAVACPP_MAX_PHYSICAL_GB}g"
  "-Dorg.bytedeco.javacpp.logger.debug=false"
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
      echo "[LookupInVideo]   * memory pressure killer (jetsam) — see Console.app"
      echo "[LookupInVideo]     -> lower JAVACPP_MAX_PHYSICAL_GB (current: ${JAVACPP_MAX_PHYSICAL_GB:-18})"
      echo "[LookupInVideo]     -> or lower JVM_HEAP_GB (current: ${JVM_HEAP_GB:-6})"
      echo "[LookupInVideo]   * manual kill -9 on the java process"
      echo "[LookupInVideo] Check Console.app for entries like \"low swap\" or \"jetsam\"."
    fi
    echo "[LookupInVideo] Build log: ${BUILD_LOG}"
    echo "[LookupInVideo] Run log:   ${RUN_LOG}"
  fi
' EXIT

# `exec` would replace the shell and bypass `tee`, so run normally and tee.
./mvnw -X spring-boot:run -Dspring-boot.run.jvmArguments="${JVM_ARG_STRING}" 2>&1 | tee -a "${RUN_LOG}"
