#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <profile_id> [properties_file]"
  exit 1
fi

PROFILE_ID="$1"
PROPS_FILE="${2:-src/main/resources/application.properties}"
CSV_FILE="tools/perf/config_profiles.csv"

if [[ ! -f "$CSV_FILE" ]]; then
  echo "Missing $CSV_FILE"
  exit 1
fi

if [[ ! -f "$PROPS_FILE" ]]; then
  echo "Missing $PROPS_FILE"
  exit 1
fi

line=$(awk -F',' -v p="$PROFILE_ID" 'NR>1 && $1==p {print $0}' "$CSV_FILE")
if [[ -z "$line" ]]; then
  echo "Profile not found: $PROFILE_ID"
  exit 1
fi

IFS=',' read -r profile max_threads intra_segments decode_hwaccel decode_threads gpu_processing <<< "$line"

update_prop() {
  local key="$1"
  local value="$2"
  local file="$3"
  local tmp
  tmp=$(mktemp)
  awk -v k="$key" -v v="$value" '
    BEGIN { done=0 }
    index($0, k"=")==1 { print k"="v; done=1; next }
    { print }
    END { if (!done) print k"="v }
  ' "$file" > "$tmp"
  mv "$tmp" "$file"
}

update_prop "lookup.video.analysis.max-threads" "$max_threads" "$PROPS_FILE"
update_prop "lookup.video.analysis.intra-segment-count" "$intra_segments" "$PROPS_FILE"
update_prop "lookup.video.analysis.decode-hwaccel" "$decode_hwaccel" "$PROPS_FILE"
update_prop "lookup.video.analysis.decode-threads" "$decode_threads" "$PROPS_FILE"
update_prop "lookup.video.analysis.gpu-processing" "$gpu_processing" "$PROPS_FILE"

echo "Applied profile: $profile"
echo "  max-threads=$max_threads"
echo "  intra-segment-count=$intra_segments"
echo "  decode-hwaccel=$decode_hwaccel"
echo "  decode-threads=$decode_threads"
echo "  gpu-processing=$gpu_processing"
