package com.gazsik.lookupinvideo.infrastructure.video;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import org.springframework.stereotype.Component;

@Component
public class FrameSampler {

    private static final long DEFAULT_SAMPLE_STEP_US = 1_000_000L;
    private static final long WILDLIFE_SAMPLE_STEP_US = 150_000L;

    public long computeSampleStepUs(QueryIntent intent, long durationUs) {
        if (intent == QueryIntent.TURN || intent == QueryIntent.LANE_CHANGE) {
            // Finer sampling to catch short turns and lane changes
            return 500_000L;
        }
        if (intent == QueryIntent.CROSSING_VEHICLE) {
            // Fast lateral motion across the frame – needs sub-second sampling
            return 250_000L;
        }
        if (intent == QueryIntent.ANOMALY) {
            // Burst events are short – sample ~3 fps
            return 350_000L;
        }
        if (intent == QueryIntent.ROAD_OBSTACLE) {
            // Stop / stationary detection compares against a longer EMA, 0.5 s is enough
            return 500_000L;
        }
        if (intent == QueryIntent.ONCOMING_TRUCK) {
            // Approaching truck silhouette grows over a few seconds — 0.4 s is enough
            // resolution to track the centroid stay-in-place + size growth.
            return 400_000L;
        }
        if (intent != QueryIntent.WILDLIFE) {
            return DEFAULT_SAMPLE_STEP_US;
        }
        if (durationUs >= 360_000_000L) {
            return 350_000L;
        }
        if (durationUs >= 180_000_000L) {
            return 250_000L;
        }
        return WILDLIFE_SAMPLE_STEP_US;
    }

    public boolean shouldProcessFrame(long timestampUs, long nextSampleUs) {
        return timestampUs >= nextSampleUs;
    }

    public long advanceSampleCursor(long timestampUs, long nextSampleUs, long sampleStepUs) {
        long step = Math.max(1L, sampleStepUs);
        long cursor = nextSampleUs;
        do {
            cursor += step;
        } while (timestampUs >= cursor);
        return cursor;
    }
}
