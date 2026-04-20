package com.gazsik.lookupinvideo.infrastructure.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;

@Component
public class VideoDecoderService {

    public long probeVideoDuration(Path videoPath) {
        FFmpegFrameGrabber probe = new FFmpegFrameGrabber(videoPath.toFile());
        try {
            probe.setOption("threads", "1");
            probe.setOption("hwaccel", "none");
            // Prevent FFmpeg from spending too long detecting the stream format.
            // Without these, a corrupted or unusual container can block start() for many minutes.
            probe.setOption("analyzeduration", "2000000");  // max 2s format analysis
            probe.setOption("probesize", "20000000");        // max 20MB header probe
            probe.start();
            long dur = Math.max(0L, probe.getLengthInTime());
            probe.stop();
            return dur;
        } catch (Exception ex) {
            return 0L;
        } finally {
            try {
                probe.close();
            } catch (Exception ignored) {
                // no-op
            }
        }
    }

    public void startGrabberWithGpuFallback(FFmpegFrameGrabber grabber,
                                            boolean decodeHwAccelEnabled,
                                            int decodeThreadCount) throws FFmpegFrameGrabber.Exception {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        String threadOption = decodeThreadCount <= 0 ? "0" : Integer.toString(Math.max(1, decodeThreadCount));
        grabber.setOption("threads", threadOption);
        // Limit format-detection time so a bad file cannot block a segment thread indefinitely.
        grabber.setOption("analyzeduration", "5000000");  // max 5s
        grabber.setOption("probesize", "30000000");        // max 30MB
        if (!decodeHwAccelEnabled) {
            grabber.setOption("hwaccel", "none");
            grabber.start();
            return;
        }

        if (os.contains("mac")) {
            grabber.setOption("hwaccel", "videotoolbox");
        } else if (os.contains("win")) {
            grabber.setOption("hwaccel", "d3d11va");
        } else {
            grabber.setOption("hwaccel", "auto");
        }

        try {
            grabber.start();
            return;
        } catch (FFmpegFrameGrabber.Exception ignored) {
            try {
                grabber.stop();
            } catch (Exception ignoredStop) {
                // no-op
            }
        }

        grabber.setOption("hwaccel", "none");
        grabber.start();
    }
}
