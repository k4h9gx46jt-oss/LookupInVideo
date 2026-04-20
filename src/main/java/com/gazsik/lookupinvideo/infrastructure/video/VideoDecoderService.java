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
