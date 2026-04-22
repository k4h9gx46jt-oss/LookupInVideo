#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# mac-temps.sh — show CPU / GPU / SoC / disk temperatures and related system
#                metrics on macOS (Apple Silicon and Intel).
#
# Uses ONLY built-in macOS tools (no Homebrew required):
#   * powermetrics  — thermal pressure, CPU/GPU die temperature, package power
#   * iostat        — disk read/write throughput (no native temp on macOS)
#   * sysctl        — model + thermal pressure flag
#   * pmset         — battery / thermal state
#   * smartctl      — disk temperature for external drives THAT support SMART
#                     (skipped silently if not installed)
#
# Notes about macOS thermal data:
#   * macOS does not expose per-sensor temperatures to user space without root.
#     `powermetrics` (built-in, requires sudo) is the canonical source.
#   * Apple Silicon SoC integrates CPU + GPU + ANE; powermetrics reports them
#     together as "CPU die temperature" / "GPU die temperature".
#   * Internal NVMe temperature is not exposed by SMART on Apple Silicon.
#     Only external SATA/USB SSDs with `smartctl` may report it.
#
# Usage:
#   ./tools/mac-temps.sh                # one snapshot
#   ./tools/mac-temps.sh -i 5           # repeat every 5 seconds
#   ./tools/mac-temps.sh -i 2 -n 30     # 30 samples, 2 s apart
#   ./tools/mac-temps.sh --no-sudo      # skip powermetrics (no temps)
# -----------------------------------------------------------------------------
set -euo pipefail

INTERVAL=0
COUNT=1
USE_SUDO=true
POWERMETRICS_SAMPLE_MS=900   # how long powermetrics samples per snapshot

usage() {
  sed -n '2,30p' "$0"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--interval) INTERVAL="${2:-0}"; shift 2 ;;
    -n|--count)    COUNT="${2:-1}";    shift 2 ;;
    --no-sudo)     USE_SUDO=false;     shift   ;;
    -h|--help)     usage ;;
    *)             echo "Unknown arg: $1" >&2; usage ;;
  esac
done

# ---- helpers ----------------------------------------------------------------
have() { command -v "$1" >/dev/null 2>&1; }

color() { # $1=ansi $2=text
  printf '\033[%sm%s\033[0m' "$1" "$2"
}

hdr() {
  printf '\n%s\n' "$(color '1;36' "── $1 ──")"
}

bytes_human() { # $1 = bytes
  awk -v b="$1" 'BEGIN{
    s="B KB MB GB TB"; n=split(s,u," "); i=1;
    while (b>=1024 && i<n){ b/=1024; i++ } printf "%.1f %s", b, u[i]
  }'
}

# Format temperature with a colored severity.
fmt_temp() { # $1 = celsius (may be float)
  local t="$1"
  if [[ -z "$t" || "$t" == "n/a" ]]; then
    color '0;90' "n/a"
    return
  fi
  awk -v t="$t" '
    BEGIN {
      if (t < 0) col="0;90";
      else if (t < 60) col="0;32";
      else if (t < 75) col="0;33";
      else if (t < 90) col="0;31";
      else col="1;31;5";
      printf "\033[%sm%5.1f °C\033[0m", col, t;
    }'
}

# ---- sources ----------------------------------------------------------------
print_model_info() {
  hdr "System"
  printf '  Model      : %s\n' "$(sysctl -n hw.model 2>/dev/null || echo unknown)"
  printf '  Brand      : %s\n' "$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  printf '  Cores      : %s physical / %s logical\n' \
    "$(sysctl -n hw.physicalcpu 2>/dev/null || echo ?)" \
    "$(sysctl -n hw.logicalcpu  2>/dev/null || echo ?)"
  printf '  Memory     : %s\n' "$(bytes_human "$(sysctl -n hw.memsize 2>/dev/null || echo 0)")"
  printf '  macOS      : %s (build %s)\n' "$(sw_vers -productVersion)" "$(sw_vers -buildVersion)"
  printf '  Date       : %s\n' "$(date '+%Y-%m-%d %H:%M:%S %z')"
}

print_thermal_pressure() {
  hdr "Thermal pressure (kernel hint)"
  # 0 = nominal, 1 = moderate, 2 = heavy, 3 = trapping (Apple's terminology)
  local pmset_out
  if pmset_out=$(pmset -g therm 2>/dev/null); then
    echo "$pmset_out" | sed 's/^/  /'
  else
    echo "  (pmset -g therm unavailable)"
  fi
  if have sysctl; then
    local lvl
    lvl=$(sysctl -n machdep.xcpm.cpu_thermal_level 2>/dev/null || true)
    [[ -n "$lvl" ]] && printf '  CPU thermal level (sysctl) : %s\n' "$lvl"
    lvl=$(sysctl -n machdep.xcpm.gpu_thermal_level 2>/dev/null || true)
    [[ -n "$lvl" ]] && printf '  GPU thermal level (sysctl) : %s\n' "$lvl"
  fi
}

# Run powermetrics once, parse the SMC / thermal block.
print_powermetrics() {
  if [[ "$USE_SUDO" != true ]]; then
    hdr "powermetrics (skipped: --no-sudo)"
    return
  fi
  if ! have powermetrics; then
    hdr "powermetrics (not installed)"
    return
  fi

  hdr "Temperatures & power (powermetrics, sudo)"

  # Ask for SMC + thermal + cpu_power + gpu_power, take ONE sample.
  # Output is plain text; we scrape the lines we care about.
  local pm_out
  if ! pm_out=$(sudo -n powermetrics \
        --samplers smc,thermal,cpu_power,gpu_power \
        -i "$POWERMETRICS_SAMPLE_MS" \
        -n 1 2>/dev/null); then
    echo "  (sudo password required, or powermetrics failed)" >&2
    echo "  Tip: run once with    sudo -v   to cache credentials." >&2
    return
  fi

  # CPU / GPU die temperature (Apple Silicon)
  local cpu_die gpu_die
  cpu_die=$(echo "$pm_out" | awk '/CPU die temperature:/ {print $4; exit}')
  gpu_die=$(echo "$pm_out" | awk '/GPU die temperature:/ {print $4; exit}')

  # Generic fallback (older Intel powermetrics labels)
  [[ -z "$cpu_die" ]] && cpu_die=$(echo "$pm_out" | awk '/CPU\/Package temperature/ {print $3; exit}')

  # Fan speed (Intel only; Apple Silicon laptops without fan return nothing)
  local fan
  fan=$(echo "$pm_out" | awk -F'[: ]+' '/Fan/ {print $0; exit}')

  # Power figures (Watts)
  local cpu_w gpu_w pkg_w
  cpu_w=$(echo "$pm_out" | awk '/CPU Power:/  {print $3; exit}')
  gpu_w=$(echo "$pm_out" | awk '/GPU Power:/  {print $3; exit}')
  pkg_w=$(echo "$pm_out" | awk '/Combined Power \(CPU \+ GPU \+ ANE\):/ {print $9; exit}')

  printf '  CPU die         : %s\n' "$(fmt_temp "${cpu_die:-n/a}")"
  printf '  GPU die         : %s\n' "$(fmt_temp "${gpu_die:-n/a}")"
  printf '  CPU power       : %s mW\n'   "${cpu_w:-n/a}"
  printf '  GPU power       : %s mW\n'   "${gpu_w:-n/a}"
  [[ -n "$pkg_w" ]] && printf '  SoC pkg power   : %s mW\n' "$pkg_w"
  [[ -n "$fan"   ]] && printf '  Fan             : %s\n' "$fan"

  # Pressure & throttle hints from the same dump
  local thermal_pressure
  thermal_pressure=$(echo "$pm_out" | awk -F': ' '/Current pressure level/ {print $2; exit}')
  [[ -n "$thermal_pressure" ]] && printf '  Pressure level  : %s\n' "$thermal_pressure"
}

print_disk() {
  hdr "Disk activity & temperature"

  # Temperature for the BUILT-IN drive is not exposed by SMART on Apple Silicon.
  # We try smartctl on every disk; only externals tend to report something useful.
  if have smartctl; then
    local disks
    disks=$(diskutil list physical 2>/dev/null \
            | awk '/^\/dev\/disk[0-9]+/ {print $1}' || true)
    if [[ -n "$disks" ]]; then
      while IFS= read -r d; do
        # SMART read needs sudo on macOS; gracefully skip if not allowed.
        local out temp
        out=$(sudo -n smartctl -A "$d" 2>/dev/null || true)
        if [[ -n "$out" ]]; then
          temp=$(echo "$out" | awk '/Temperature:/ {print $2; exit}')
          if [[ -n "$temp" ]]; then
            printf '  %-12s : %s (SMART)\n' "$d" "$(fmt_temp "$temp")"
          fi
        fi
      done <<< "$disks"
    fi
  else
    echo "  (smartctl not installed — no disk temps; throughput only)"
  fi

  # Throughput per disk (one quick sample). iostat is built-in.
  if have iostat; then
    printf '\n  Throughput (1 s sample, KB/s):\n'
    iostat -d -w 1 -c 2 | tail -n +3 | awk 'NR<=3{print "  "$0}'
  fi
}

print_load() {
  hdr "Load"
  if have uptime; then
    uptime | sed 's/^[ ]*/  /'
  fi
  if have vm_stat; then
    local free_pages page_size
    page_size=$(sysctl -n hw.pagesize 2>/dev/null || echo 4096)
    free_pages=$(vm_stat | awk '/Pages free/ {gsub(/\./,"",$3); print $3}')
    if [[ -n "$free_pages" ]]; then
      printf '  Free RAM   : %s\n' "$(bytes_human "$((free_pages * page_size))")"
    fi
  fi
}

# ---- main loop --------------------------------------------------------------
# Cache sudo once up front so the loop is non-interactive.
if [[ "$USE_SUDO" == true ]]; then
  if ! sudo -n true 2>/dev/null; then
    echo "powermetrics needs sudo. Caching credentials now (one-time prompt)..."
    sudo -v || { echo "No sudo — re-run with --no-sudo to skip temperatures." >&2; exit 2; }
  fi
fi

iter=0
while :; do
  iter=$((iter + 1))
  printf '\n%s  iteration %d/%d\n' "$(color '1;35' '=== mac-temps ===')" "$iter" "$COUNT"

  print_model_info
  print_thermal_pressure
  print_powermetrics
  print_disk
  print_load

  if [[ $iter -ge $COUNT ]]; then
    break
  fi
  sleep "$INTERVAL"
done
