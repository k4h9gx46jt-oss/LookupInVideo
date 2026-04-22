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

echo "[run-scenario-tests] pattern: ${PATTERN}"
echo "[run-scenario-tests] launching mvn surefire ..."
echo

./mvnw -B -DfailIfNoTests=false -Dtest="${PATTERN}" test
