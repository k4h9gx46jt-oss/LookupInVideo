#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
VIDEO_DIR="${VIDEO_DIR:-}"
QUERY="${QUERY:-deer}"
JAVA_PID="${JAVA_PID:-}"
CSV_FILE="tools/perf/config_profiles.csv"

if [[ -z "$VIDEO_DIR" ]]; then
  echo "Usage: VIDEO_DIR=<path> [QUERY=deer] [JAVA_PID=<pid>] $0"
  exit 1
fi

if [[ ! -d "$VIDEO_DIR" ]]; then
  echo "Directory not found: $VIDEO_DIR"
  exit 1
fi

if [[ ! -f "$CSV_FILE" ]]; then
  echo "Missing $CSV_FILE"
  exit 1
fi

while IFS=',' read -r profile _; do
  [[ "$profile" == "profile_id" ]] && continue

  echo "------------------------------------------------------------"
  echo "Applying profile: $profile"
  tools/perf/apply_config_profile.sh "$profile"

  echo "Restart the app manually now, then press Enter to continue..."
  read -r _

  echo "Running benchmark for $profile"
  bench_args=(--dir "$VIDEO_DIR" --query "$QUERY" --base-url "$BASE_URL")
  if [[ -n "$JAVA_PID" ]]; then
    bench_args+=(--pid "$JAVA_PID")
  fi

  PROFILE_NAME="$profile" \
  BASE_URL="$BASE_URL" VIDEO_DIR="$VIDEO_DIR" QUERY="$QUERY" JAVA_PID="$JAVA_PID" \
  OUT_CSV="doc/perf-results/benchmarks.csv" \
  tools/perf/run_directory_benchmark.sh "${bench_args[@]}"
done < "$CSV_FILE"

echo "Sweep done. See doc/perf-results/benchmarks.csv"
