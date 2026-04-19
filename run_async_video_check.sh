#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT_DIR="${ROOT_DIR:-/Users/SEV0A/java/LookupInVideo}"
QUERY="${QUERY:-szarvas}"
PROFILE_LABEL="${PROFILE_LABEL:-AFTER}"
MAX_POLLS="${MAX_POLLS:-1200}"
POLL_INTERVAL_SEC="${POLL_INTERVAL_SEC:-0.4}"

NEGATIVE_VIDEOS=(
  "liveformowncam/NO20260415-163405-000388.mp4"
  "liveformowncam/NO20260415-160202-000356.mp4"
  "liveformowncam/NO20260415-160302-000357.mp4"
  "liveformowncam/NO20260415-160803-000362.mp4"
)

POSITIVE_VIDEOS=(
  "demoVideo/Demo1.mp4"
)

if ! curl -fsS "$BASE_URL/" >/dev/null 2>&1; then
  echo "ERROR: A szerver nem erheto el: $BASE_URL" >&2
  echo "Inditsd el az appot, majd futtasd ujra ezt a scriptet." >&2
  exit 1
fi

TMP_RESULTS="$(mktemp)"
trap 'rm -f "$TMP_RESULTS"' EXIT

run_case() {
  local phase="$1"
  local expected="$2"
  local video="$3"

  local video_dir="$ROOT_DIR/$(dirname "$video")"
  local video_file="$(basename "$video")"

  local post_resp
  post_resp="$(curl -sS -X POST "$BASE_URL/search-local-async" \
    --data-urlencode "videoDir=$video_dir" \
    --data-urlencode "videoFile=$video_file" \
    --data-urlencode "query=$QUERY" || true)"

  local job_id
  local post_error
  job_id="$(printf '%s' "$post_resp" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("jobId", ""))' 2>/dev/null || true)"
  post_error="$(printf '%s' "$post_resp" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("error", ""))' 2>/dev/null || true)"

  if [[ -z "$job_id" ]]; then
    printf "| %s | %s | %s | ERROR | 0 | 0.0%% | FAIL | %s |\n" "$PROFILE_LABEL" "$phase" "$video" "${post_error:-failed to start job}"
    printf "%s|%s|%s|ERROR|FAIL|0|0.0\n" "$PROFILE_LABEL" "$phase" "$video" >> "$TMP_RESULTS"
    return
  fi

  local status=""
  local progress_json=""
  local error_text=""
  for ((i = 1; i <= MAX_POLLS; i++)); do
    progress_json="$(curl -sS "$BASE_URL/progress/$job_id" || true)"
    status="$(printf '%s' "$progress_json" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("status", ""))' 2>/dev/null || true)"
    [[ "$status" == "DONE" || "$status" == "ERROR" ]] && break
    sleep "$POLL_INTERVAL_SEC"
  done

  if [[ "$status" != "DONE" && "$status" != "ERROR" ]]; then
    status="TIMEOUT"
  fi

  if [[ "$status" != "DONE" ]]; then
    error_text="$(printf '%s' "$progress_json" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("error", ""))' 2>/dev/null || true)"
    if [[ -z "$error_text" || "$error_text" == "None" ]]; then
      error_text="no result"
    fi
    printf "| %s | %s | %s | %s | 0 | 0.0%% | FAIL | %s |\n" "$PROFILE_LABEL" "$phase" "$video" "$status" "${error_text:-no result}"
    printf "%s|%s|%s|%s|FAIL|0|0.0\n" "$PROFILE_LABEL" "$phase" "$video" "$status" >> "$TMP_RESULTS"
    return
  fi

  local html
  local html_file
  local parsed
  html="$(curl -sS "$BASE_URL/result-single/$job_id" || true)"
  html_file="$(mktemp)"
  printf '%s' "$html" > "$html_file"
  parsed="$(python3 - "$html_file" <<'PY'
import html
import re
import sys
from pathlib import Path

s = Path(sys.argv[1]).read_text(encoding='utf-8', errors='ignore')
count = len(re.findall(r'class="result-card"', s))
reasons = [
    html.unescape(x.strip())
    for x in re.findall(r'Szarvas-heurisztika:[^<\r\n]*', s, flags=re.IGNORECASE)
]
scores = []
for r in reasons:
    m = re.search(r'Szarvas-heurisztika:\s*([0-9]+(?:[\.,][0-9]+)?)%', r, flags=re.IGNORECASE)
    if m:
        scores.append(float(m.group(1).replace(',', '.')))

top_reason = reasons[0] if reasons else ''
top_score = max(scores) if scores else 0.0
print(count)
print(top_score)
print(top_reason)
PY
)"
  rm -f "$html_file"

  local match_count
  local top_score
  local top_reason
  local decision
  local verdict
  match_count="$(printf '%s\n' "$parsed" | sed -n '1p')"
  top_score="$(printf '%s\n' "$parsed" | sed -n '2p')"
  top_reason="$(printf '%s\n' "$parsed" | sed -n '3p' | tr '|' '/' )"
  if [[ "${match_count:-0}" -gt 0 ]]; then
    decision="KEEP"
  else
    decision="DROP"
  fi

  if [[ "$expected" == "$decision" ]]; then
    verdict="PASS"
  else
    verdict="FAIL"
  fi

  printf "| %s | %s | %s | DONE | %s | %.1f%% | %s | %s |\n" \
    "$PROFILE_LABEL" "$phase" "$video" "${match_count:-0}" "${top_score:-0}" "$verdict" "${top_reason:-none}"
  printf "%s|%s|%s|DONE|%s|%s|%s\n" \
    "$PROFILE_LABEL" "$phase" "$video" "$verdict" "${match_count:-0}" "${top_score:-0}" >> "$TMP_RESULTS"
}

printf "# Validation order: NEGATIVE first, POSITIVE second\n"
printf "# Query: %s\n\n" "$QUERY"
printf "| profile | phase | video | status | match_count | top_score | verdict | top_reason |\n"
printf "|---|---|---|---|---:|---:|---|---|\n"

for v in "${NEGATIVE_VIDEOS[@]}"; do
  run_case "NEGATIVE" "DROP" "$v"
done
for v in "${POSITIVE_VIDEOS[@]}"; do
  run_case "POSITIVE" "KEEP" "$v"
done

printf "\n# Summary\n"
python3 - "$TMP_RESULTS" <<'PY'
import sys
from statistics import mean

rows=[]
for line in open(sys.argv[1], 'r', encoding='utf-8'):
    line=line.strip()
    if not line:
        continue
    profile, phase, video, status, verdict, match_count, top_score = line.split('|')
    rows.append({
        'profile': profile,
        'phase': phase,
        'video': video,
        'status': status,
        'verdict': verdict,
        'match_count': int(float(match_count)),
        'top_score': float(top_score),
    })

if not rows:
    print('No rows collected.')
    sys.exit(0)

neg=[r for r in rows if r['phase']=='NEGATIVE']
pos=[r for r in rows if r['phase']=='POSITIVE']
pass_count=sum(1 for r in rows if r['verdict']=='PASS')

max_neg=max((r['top_score'] for r in neg), default=0.0)
min_pos=min((r['top_score'] for r in pos if r['match_count']>0), default=0.0)
avg_neg=mean([r['top_score'] for r in neg]) if neg else 0.0
avg_pos=mean([r['top_score'] for r in pos]) if pos else 0.0

print(f'- Cases: {len(rows)} | PASS: {pass_count} | FAIL: {len(rows)-pass_count}')
print(f'- NEG avg/top: {avg_neg:.1f}% / {max_neg:.1f}%')
print(f'- POS avg/min: {avg_pos:.1f}% / {min_pos:.1f}%')

if min_pos > 0.0 and min_pos > max_neg:
    rec=(min_pos + max_neg)/2.0
    print(f'- Suggested deer threshold window center: {rec:.1f}% (between max NEG and min POS)')
else:
    print('- Overlap detected between NEG and POS scores. Adjust weights first, then threshold.')
PY

printf "\n# Tip\n"
printf "# Before/After osszehasonlitashoz futtasd 2x: PROFILE_LABEL=BEFORE majd PROFILE_LABEL=AFTER\n"
