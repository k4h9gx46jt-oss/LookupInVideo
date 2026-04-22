#!/usr/bin/env bash
# Run the curated scene-detection regression scenarios.
#
# Usage:
#   ./tools/run-scenario-tests.sh                    # all scenarios
#   ./tools/run-scenario-tests.sh '#positive_*'      # only positives (JUnit pattern)
#   ./tools/run-scenario-tests.sh '#negative_*'      # only negatives
#   ./tools/run-scenario-tests.sh '#positive_demo1_deerCrossing'  # one method
#
# The full suite takes ~2 minutes on a recent Mac. Tests that need video files
# missing from the workspace are silently skipped (Assumptions.assumeTrue),
# they never fail the build.

set -euo pipefail

# Always run from repo root so liveformowncam/ + demoVideo/ relative paths resolve.
cd "$(dirname "$0")/.."

PATTERN="SceneDetectionScenarioTest${1:-}"

LOG_FILE="$(mktemp -t scenario-tests.XXXXXX.log)"
trap 'rm -f "${LOG_FILE}"' EXIT

echo "[run-scenario-tests] pattern: ${PATTERN}"
echo "[run-scenario-tests] log:     ${LOG_FILE}"
echo "[run-scenario-tests] launching mvn surefire ..."
echo

set +e
./mvnw -B -DfailIfNoTests=false -Dtest="${PATTERN}" test 2>&1 | tee "${LOG_FILE}"
MVN_EXIT=${PIPESTATUS[0]}
set -e

echo
echo "================ SCENARIO TEST SUMMARY ================"

# Per-test status table from Surefire's "<<< FAILURE!" / "<<< ERROR!" markers
# combined with the names that ran (parsed from "Running ..." headers).
awk '
  /^\[INFO\] Tests run: [0-9]+,/ && !seen { print; seen=1 }
  /<<< FAILURE!|<<< ERROR!/ { print "  FAIL " $0 }
' "${LOG_FILE}" || true

# Final aggregate counts (last "Tests run:" line in the log is the per-class total).
TOTALS_LINE=$(grep -E "^\[(INFO|ERROR)\] Tests run: [0-9]+, Failures:" "${LOG_FILE}" | tail -1 || true)
if [[ -n "${TOTALS_LINE}" ]]; then
  echo "  ${TOTALS_LINE}"
fi

# Failing test names (clean list, no stack trace)
FAILS=$(grep -E "^\[ERROR\]   SceneDetectionScenarioTest\." "${LOG_FILE}" | sed 's/^\[ERROR\]   /    /' | cut -d' ' -f5- || true)
if [[ -n "${FAILS}" ]]; then
  echo "  Failing tests:"
  echo "${FAILS}" | sed 's/^/    /'
fi

echo "  mvn exit code: ${MVN_EXIT}"
echo "======================================================="

exit "${MVN_EXIT}"
