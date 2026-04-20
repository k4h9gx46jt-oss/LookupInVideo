# Performance Results

This folder stores repeatable measurement outputs for CPU-load tuning phases.

## Files
- `benchmarks.csv`: per-run benchmark outputs from `tools/perf/run_directory_benchmark.sh`
- `config_profiles.csv`: tuning profile matrix in `tools/perf/config_profiles.csv`

## Typical flow
1. Apply a profile:
   - `tools/perf/apply_config_profile.sh p03`
2. Restart app manually.
3. Run benchmark:
   - `JAVA_PID=<pid> tools/perf/run_directory_benchmark.sh --dir <video_dir> --query deer`
4. Repeat and compare rows in `benchmarks.csv`.
