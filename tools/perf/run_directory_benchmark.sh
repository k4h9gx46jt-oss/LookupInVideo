#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
VIDEO_DIR="${VIDEO_DIR:-}"
QUERY="${QUERY:-deer}"
POLL_SEC="${POLL_SEC:-1}"
JAVA_PID="${JAVA_PID:-}"
OUT_CSV="${OUT_CSV:-doc/perf-results/benchmarks.csv}"

usage() {
  cat <<'USAGE'
Usage:
  run_directory_benchmark.sh --dir <video_dir> [--query deer] [--base-url http://localhost:8080] [--pid <java_pid>] [--poll-sec 1]

Notes:
- Expects the app to be already running.
- If --pid is omitted, script runs without CPU sampling.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) VIDEO_DIR="$2"; shift 2 ;;
    --query) QUERY="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --pid) JAVA_PID="$2"; shift 2 ;;
    --poll-sec) POLL_SEC="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1"; usage; exit 1 ;;
  esac
done

if [[ -z "$VIDEO_DIR" ]]; then
  echo "ERROR: --dir is required"
  usage
  exit 1
fi

if [[ ! -d "$VIDEO_DIR" ]]; then
  echo "ERROR: directory not found: $VIDEO_DIR"
  exit 1
fi

mkdir -p "$(dirname "$OUT_CSV")"
if [[ ! -f "$OUT_CSV" ]]; then
  echo "timestamp,profile,max_threads,intra_segments,decode_hwaccel,decode_threads,gpu_processing,query,video_dir,elapsed_sec,cpu_avg,cpu_max,processed,total,matches_found" > "$OUT_CSV"
fi

profile="${PROFILE_NAME:-manual}"
props_file="src/main/resources/application.properties"
max_threads=$(grep '^lookup.video.analysis.max-threads=' "$props_file" | cut -d= -f2)
intra_segments=$(grep '^lookup.video.analysis.intra-segment-count=' "$props_file" | cut -d= -f2)
decode_hwaccel=$(grep '^lookup.video.analysis.decode-hwaccel=' "$props_file" | cut -d= -f2)
decode_threads=$(grep '^lookup.video.analysis.decode-threads=' "$props_file" | cut -d= -f2)
gpu_processing=$(grep '^lookup.video.analysis.gpu-processing=' "$props_file" | cut -d= -f2)

start_ts=$(date +%s)
iso_ts=$(date '+%Y-%m-%dT%H:%M:%S')

resp=$(curl -fsS -X POST "$BASE_URL/search-dir" \
  --data-urlencode "videoDir=$VIDEO_DIR" \
  --data-urlencode "query=$QUERY")

job_id=$(printf "%s" "$resp" | sed -n 's/.*"jobId"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
if [[ -z "$job_id" ]]; then
  err=$(printf "%s" "$resp" | sed -n 's/.*"error"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
  echo "ERROR: could not start search-dir. Response: $resp"
  [[ -n "$err" ]] && echo "Server error: $err"
  exit 1
fi

echo "Started jobId=$job_id"

cpu_sum="0"
cpu_max="0"
cpu_samples=0

status="RUNNING"
processed=0
total=0
matches=0

while [[ "$status" == "RUNNING" ]]; do
  sleep "$POLL_SEC"
  progress_json=$(curl -fsS "$BASE_URL/progress/$job_id" || true)
  if [[ -z "$progress_json" ]]; then
    continue
  fi

  status=$(printf "%s" "$progress_json" | sed -n 's/.*"status"[[:space:]]*:[[:space:]]*"\([A-Z]*\)".*/\1/p')
  processed=$(printf "%s" "$progress_json" | sed -n 's/.*"processed"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
  total=$(printf "%s" "$progress_json" | sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')
  matches=$(printf "%s" "$progress_json" | sed -n 's/.*"matchesFound"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p')

  [[ -z "$status" ]] && status="RUNNING"
  [[ -z "$processed" ]] && processed=0
  [[ -z "$total" ]] && total=0
  [[ -z "$matches" ]] && matches=0

  if [[ -n "$JAVA_PID" ]]; then
    cpu_now=$(ps -p "$JAVA_PID" -o %cpu= | awk '{print $1+0}')
    cpu_sum=$(awk -v a="$cpu_sum" -v b="$cpu_now" 'BEGIN{printf "%.6f", a+b}')
    cpu_max=$(awk -v a="$cpu_max" -v b="$cpu_now" 'BEGIN{if (b>a) printf "%.2f", b; else printf "%.2f", a}')
    cpu_samples=$((cpu_samples + 1))
  fi

  printf 'status=%s processed=%s/%s matches=%s\r' "$status" "$processed" "$total" "$matches"
done
printf '\n'

end_ts=$(date +%s)
elapsed=$((end_ts - start_ts))

if [[ "$cpu_samples" -gt 0 ]]; then
  cpu_avg=$(awk -v a="$cpu_sum" -v n="$cpu_samples" 'BEGIN{printf "%.2f", a/n}')
else
  cpu_avg="n/a"
  cpu_max="n/a"
fi

echo "Benchmark finished"
echo "  jobId: $job_id"
echo "  status: $status"
echo "  elapsed_sec: $elapsed"
echo "  processed/total: $processed/$total"
echo "  matches_found: $matches"
echo "  cpu_avg: $cpu_avg"
echo "  cpu_max: $cpu_max"

echo "$iso_ts,$profile,$max_threads,$intra_segments,$decode_hwaccel,$decode_threads,$gpu_processing,$QUERY,$VIDEO_DIR,$elapsed,$cpu_avg,$cpu_max,$processed,$total,$matches" >> "$OUT_CSV"

echo "Results appended to $OUT_CSV"
