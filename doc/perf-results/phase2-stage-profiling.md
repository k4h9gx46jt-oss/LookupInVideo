# Phase 2 Stage Profiling Guide

## Purpose
Capture per-stage time distribution in video analysis pipeline to identify top bottlenecks.

## Toggle
In [src/main/resources/application.properties](src/main/resources/application.properties):
- `lookup.video.analysis.profile-stages=true`

Default should remain `false` for regular runs.

## Logged stages
- `grabDecode`
- `convertScaleColor`
- `motion`
- `scoring`
- `preview`
- `progressUpdate`

## Log marker
Search for:
- `ANALYSIS_STAGE_PROFILE`

The line includes:
- video metadata (intent, segment count, sample step, width)
- frame counters
- `topStages=[...]`
- total milliseconds per stage

## Recommended run
1. Enable stage profiling.
2. Run one directory benchmark with stable profile.
3. Collect logs and extract 3 most expensive stages.
4. Disable stage profiling after measurement.
