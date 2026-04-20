package com.gazsik.lookupinvideo.infrastructure.video;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import org.springframework.stereotype.Component;

@Component
public class FrameSampler {

    private static final long DEFAULT_SAMPLE_STEP_US = 1_000_000L;
    private static final long WILDLIFE_SAMPLE_STEP_US = 150_000L;

    public long computeSampleStepUs(QueryIntent intent, long durationUs) {
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
