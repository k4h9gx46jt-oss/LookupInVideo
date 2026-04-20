package com.gazsik.lookupinvideo.service;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import com.gazsik.lookupinvideo.domain.model.ColorQuery;
import com.gazsik.lookupinvideo.domain.model.SearchQueryInterpretation;
import com.gazsik.lookupinvideo.infrastructure.processing.EventPostProcessor;
import com.gazsik.lookupinvideo.infrastructure.processing.EventScoringService;
import com.gazsik.lookupinvideo.infrastructure.video.FrameSampler;
import com.gazsik.lookupinvideo.infrastructure.video.VideoDecoderService;
import com.gazsik.lookupinvideo.model.SceneMatch;
import com.gazsik.lookupinvideo.model.SearchOutcome;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Moments;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_core.UMat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import jakarta.annotation.PreDestroy;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import com.gazsik.lookupinvideo.model.JobProgress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bytedeco.javacpp.BytePointer;

import static org.bytedeco.opencv.global.opencv_core.absdiff;
import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_core.haveOpenCL;
import static org.bytedeco.opencv.global.opencv_core.mean;
import static org.bytedeco.opencv.global.opencv_core.setUseOpenCL;
import static org.bytedeco.opencv.global.opencv_core.useOpenCL;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2BGR;
import static org.bytedeco.opencv.global.opencv_imgproc.INTER_AREA;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.moments;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;

@Service
public class VideoSearchService {

    private static final Logger LOG = LoggerFactory.getLogger(VideoSearchService.class);

    private static final int ANALYSIS_WIDTH_CPU = 320;
    private static final int ANALYSIS_WIDTH_GPU = 512;
    private static final int MAX_MATCHES = 12;
    /** Warm-up window before each non-zero segment start to prime EMA and axis state. */
    private static final long SEGMENT_WARMUP_US = 2_000_000L;

    private static final double DEER_TIMESTAMP_LEAD_BASE_SECONDS = 1.1;
    private static final double DEER_TIMESTAMP_LEAD_MAX_SECONDS = 4.7;
    private static final int GLOBAL_SHIFT_MAX_DX = 8;
    private static final int GLOBAL_SHIFT_MAX_DY = 6;
    private static final int GLOBAL_SHIFT_SAMPLE_STRIDE = 4;
    private static final long MIN_ANALYSIS_TIMEOUT_SECONDS = 60L;
    private static final double ONCOMING_CENTER_RATIO_MIN = 0.34;
    private static final double ONCOMING_LATERAL_TRAVEL_MAX = 0.020;
    private static final double ONCOMING_ACTIVE_GROWTH_MIN = 0.0025;
    private static final double ONCOMING_TRAVEL_SCORE_MAX = 0.46;
    private static final double WEAK_LATERAL_CROSS_TRAVEL_MAX = 0.013;
    private static final double CENTER_APPROACH_CENTER_RATIO_MIN = 0.42;
    private static final double CENTER_APPROACH_TRAVEL_SCORE_MAX = 0.31;
    private static final double CENTER_APPROACH_CROSS_TRAVEL_MAX = 0.016;
    private static final double DEER_TRACK_WINDOW_SECONDS = 1.7;
    private static final double DEER_TRACK_LATERAL_MIN = 0.020;
    private static final double DEER_TRACK_LATERAL_STRONG = 0.080;
    private static final double DEER_TRACK_X_MIN = 0.045;
    private static final double DEER_TRACK_X_STRONG = 0.200;
    private static final double ROAD_VEHICLE_COLOR_SIGNAL_MIN = 0.22;
    private static final double ROAD_VEHICLE_NEUTRAL_SIGNAL_MIN = 0.33;
    private static final double VEHICLE_COLOR_DOMINANCE_FILTER = 0.18;
    private static final double NEUTRAL_COLOR_DOMINANCE_FILTER = 0.30;
    private static final double OVERTRACKED_FLOW_CROSS_MIN = 0.75;
    private static final double OVERTRACKED_FLOW_CENTER_MIN = 0.38;
    private static final double OVERTRACKED_FLOW_LATERAL_MIN = 0.45;
    private static final double OVERTRACKED_FLOW_RESIDUAL_MIN = 0.030;
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".avi", ".mov", ".mkv", ".wmv", ".flv", ".webm", ".m4v", ".mpeg", ".mpg");

    private final Path storagePath;
    private final Map<String, Path> videoRegistry = new ConcurrentHashMap<>();
    private final Map<String, JobProgress> progressMap = new ConcurrentHashMap<>();
    private final Map<String, List<SearchOutcome>> dirResultMap = new ConcurrentHashMap<>();
    private final Map<String, SearchOutcome> singleResultMap = new ConcurrentHashMap<>();
    private final int analysisThreadCount;
    private final long analysisTimeoutSeconds;
    private final ExecutorService analysisExecutor;
    private final ExecutorService segmentExecutor;
    private final boolean gpuProcessingEnabled;
    private final String gpuProcessingStatus;
    private final boolean decodeHwAccelEnabled;
    private final int decodeThreadCount;
    private final int maxConcurrentGrabbers;
    private final int configuredSegmentCount;
    private final boolean stageProfilingEnabled;
    private final QueryInterpretationService queryInterpretationService;
    private final FrameSampler frameSampler;
    private final VideoDecoderService videoDecoderService;
    private final EventScoringService eventScoringService;
    private final EventPostProcessor eventPostProcessor;

    public VideoSearchService(QueryInterpretationService queryInterpretationService,
                              FrameSampler frameSampler,
                              VideoDecoderService videoDecoderService,
                              EventScoringService eventScoringService,
                              EventPostProcessor eventPostProcessor,
                              @Value("${lookup.video.storage-path:uploads}") String storageDir,
                              @Value("${lookup.video.analysis.max-threads:0}") int configuredAnalysisThreads,
                              @Value("${lookup.video.analysis.timeout-seconds:900}") long configuredAnalysisTimeoutSeconds,
                              @Value("${lookup.video.analysis.gpu-processing:true}") boolean gpuProcessingRequested,
                              @Value("${lookup.video.analysis.decode-hwaccel:false}") boolean decodeHwAccelEnabled,
                              @Value("${lookup.video.analysis.decode-threads:0}") int decodeThreadCount,
                              @Value("${lookup.video.analysis.max-concurrent-grabbers:0}") int configuredMaxConcurrentGrabbers,
                              @Value("${lookup.video.analysis.profile-stages:false}") boolean stageProfilingEnabled,
                              @Value("${lookup.video.analysis.intra-segment-count:0}") int configuredSegmentCount) throws IOException {
        try {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        } catch (Throwable ignored) {
            // Safe fallback if ffmpeg globals are unavailable at runtime.
        }
        ImageIO.setUseCache(false);
        this.queryInterpretationService = queryInterpretationService;
        this.frameSampler = frameSampler;
        this.videoDecoderService = videoDecoderService;
        this.eventScoringService = eventScoringService;
        this.eventPostProcessor = eventPostProcessor;
        this.storagePath = Paths.get(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(this.storagePath);
        this.analysisThreadCount = normalizeThreadCount(configuredAnalysisThreads);
        this.analysisTimeoutSeconds = Math.max(MIN_ANALYSIS_TIMEOUT_SECONDS, configuredAnalysisTimeoutSeconds);
        this.configuredSegmentCount = Math.max(0, configuredSegmentCount);
        int availableCores = Math.max(1, Runtime.getRuntime().availableProcessors());
        this.maxConcurrentGrabbers = configuredMaxConcurrentGrabbers > 0
            ? configuredMaxConcurrentGrabbers
            : Math.max(availableCores * 2, this.analysisThreadCount);
        this.analysisExecutor = Executors.newFixedThreadPool(this.analysisThreadCount);
        // Pool must fit ALL analysis threads each running their full segment slice simultaneously.
        // Without this, analysis threads block each other waiting for the segment pool to free up,
        // so only 1-2 videos ever make real progress at once (the old pool-size-= bug).
        int segsPerVideo = this.configuredSegmentCount > 0 ? this.configuredSegmentCount : 1;
        int segPoolDesired = this.analysisThreadCount * segsPerVideo;
        int segPoolSize = Math.max(this.analysisThreadCount, Math.min(segPoolDesired, this.maxConcurrentGrabbers));
        this.segmentExecutor = Executors.newFixedThreadPool(segPoolSize);
        this.gpuProcessingEnabled = resolveGpuProcessingEnabled(gpuProcessingRequested);
        this.decodeHwAccelEnabled = decodeHwAccelEnabled;
        this.decodeThreadCount = decodeThreadCount;
        this.stageProfilingEnabled = stageProfilingEnabled;
        this.gpuProcessingStatus = gpuProcessingRequested
            ? (this.gpuProcessingEnabled ? "OpenCL GPU aktiv" : "GPU nem elerheto, CPU fallback")
            : "GPU mod kikapcsolva (config)";
    }

    @PreDestroy
    public void shutdownExecutors() {
        analysisExecutor.shutdownNow();
        segmentExecutor.shutdownNow();
    }

    public SearchOutcome storeAndSearch(MultipartFile videoFile, String query) {
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Nem erkezett video fajl.");
        }

        String videoId = UUID.randomUUID().toString();
        String safeName = sanitizeFileName(videoFile.getOriginalFilename());
        Path targetPath = storagePath.resolve(videoId + "-" + safeName);

        try (InputStream in = videoFile.getInputStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("A video mentese sikertelen.", ex);
        }

        videoRegistry.put(videoId, targetPath);
        String displayName = videoFile.getOriginalFilename() != null ? videoFile.getOriginalFilename() : safeName;
        return analyzeVideo(videoId, targetPath, query == null ? "" : query.trim(), displayName, null);
    }

    /** Async feltoltes + elemzes — progress kovetessel. */
    public String startSingleUploadSearch(MultipartFile videoFile, String query) {
        if (videoFile == null || videoFile.isEmpty()) {
            throw new IllegalArgumentException("Nem erkezett video fajl.");
        }
        String videoId = UUID.randomUUID().toString();
        String safeName = sanitizeFileName(videoFile.getOriginalFilename());
        Path targetPath = storagePath.resolve(videoId + "-" + safeName);
        try (InputStream in = videoFile.getInputStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new IllegalStateException("A video mentese sikertelen.", ex);
        }
        videoRegistry.put(videoId, targetPath);
        String displayName = videoFile.getOriginalFilename() != null ? videoFile.getOriginalFilename() : safeName;

        String jobId = UUID.randomUUID().toString();
        JobProgress progress = new JobProgress();
        progress.startFile(0, 1, displayName);
        progressMap.put(jobId, progress);

        final String q = query == null ? "" : query.trim();
        final String vid = videoId;
        final Path path = targetPath;
        final String name = displayName;
        CompletableFuture.runAsync(() -> {
            try {
                SearchOutcome outcome = analyzeVideo(vid, path, q, name, progress);
                singleResultMap.put(jobId, outcome);
                progress.done(1);
            } catch (Exception ex) {
                progress.error(ex.getMessage());
            }
        }, analysisExecutor);
        return jobId;
    }

    /** Async helyi fajl elemzes — progress kovetessel. */
    public String startSinglePathSearch(Path videoPath, String query) {
        if (videoPath == null || !Files.exists(videoPath)) {
            throw new IllegalArgumentException("A videofajl nem talalhato: " + videoPath);
        }
        String videoId = UUID.randomUUID().toString();
        videoRegistry.put(videoId, videoPath);
        String displayName = videoPath.getFileName().toString();

        String jobId = UUID.randomUUID().toString();
        JobProgress progress = new JobProgress();
        progress.startFile(0, 1, displayName);
        progressMap.put(jobId, progress);

        final String q = query == null ? "" : query.trim();
        final String vid = videoId;
        CompletableFuture.runAsync(() -> {
            try {
                SearchOutcome outcome = analyzeVideo(vid, videoPath, q, displayName, progress);
                singleResultMap.put(jobId, outcome);
                progress.done(1);
            } catch (Exception ex) {
                progress.error(ex.getMessage());
            }
        }, analysisExecutor);
        return jobId;
    }

    public SearchOutcome searchByPath(Path videoPath, String query) {
        if (videoPath == null || !Files.exists(videoPath)) {
            throw new IllegalArgumentException("A videofajl nem talalhato: " + videoPath);
        }
        String videoId = UUID.randomUUID().toString();
        videoRegistry.put(videoId, videoPath);
        return analyzeVideo(videoId, videoPath, query == null ? "" : query.trim(),
                videoPath.getFileName().toString(), null);
    }

    public Path resolveVideoPath(String videoId) {
        return videoRegistry.get(videoId);
    }

    // -------------------------------------------------------------------------
    // Könyvtár-szintű aszinkron keresés
    // -------------------------------------------------------------------------

    public String startDirectorySearch(Path dirPath, String query) throws IOException {
        List<Path> videoFiles;
        try (Stream<Path> stream = Files.list(dirPath)) {
            videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith);
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (videoFiles.isEmpty()) {
            throw new IllegalArgumentException("Nincs videofajl a konyvtarban: " + dirPath);
        }

        String jobId = UUID.randomUUID().toString();
        int threadCount = Math.min(videoFiles.size(), analysisThreadCount);
        JobProgress progress = new JobProgress();
        progress.startParallel(videoFiles.size(), threadCount);
        progressMap.put(jobId, progress);

        final String q = query == null ? "" : query.trim();
        final int total = videoFiles.size();
        final long fileTimeoutSeconds = Math.max(60L, analysisTimeoutSeconds + 30L);

        List<CompletableFuture<SearchOutcome>> futures = videoFiles.stream()
                .map(videoPath -> CompletableFuture.supplyAsync(() -> {
                    String fileName = videoPath.getFileName().toString();
                    String videoId = UUID.randomUUID().toString();
                    videoRegistry.put(videoId, videoPath);
                    progress.startFileTracking(fileName);
                    return analyzeVideo(videoId, videoPath, q, fileName, progress);
                }, analysisExecutor)
                .orTimeout(fileTimeoutSeconds, TimeUnit.SECONDS)
                .handle((outcome, ex) -> {
                    String fileName = videoPath.getFileName().toString();
                    if (ex != null) {
                        LOG.warn("Directory file analysis timeout/failure for file={} (timeout={}s): {}",
                                fileName,
                                fileTimeoutSeconds,
                                ex.toString());
                        progress.fileCompleted(fileName, 0);
                        return null;
                    }
                    int matchCount = (outcome == null || outcome.getMatches().isEmpty())
                            ? 0
                            : outcome.getMatches().size();
                    progress.fileCompleted(fileName, matchCount);
                    return (outcome == null || outcome.getMatches().isEmpty()) ? null : outcome;
                }))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    List<SearchOutcome> outcomes = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(SearchOutcome::getFileName))
                            .collect(Collectors.toList());
                    dirResultMap.put(jobId, outcomes);
                    progress.done(total);
                });

        return jobId;
    }

    public JobProgress getProgress(String jobId) {
        return progressMap.get(jobId);
    }

    public List<SearchOutcome> getDirectoryResults(String jobId) {
        return dirResultMap.get(jobId);
    }

    public SearchOutcome getSingleResult(String jobId) {
        return singleResultMap.get(jobId);
    }

    private SearchOutcome analyzeVideo(String videoId, Path videoPath, String query,
                                        String fileName, JobProgress progress) {
        SearchQueryInterpretation interpretation = queryInterpretationService.interpret(query);
        QueryIntent intent = interpretation.getIntent();
        ColorQuery colorQuery = interpretation.getColorQuery();

        long durationUs = videoDecoderService.probeVideoDuration(videoPath);
        long sampleStepUs = frameSampler.computeSampleStepUs(intent, durationUs);
        int analysisWidth = gpuProcessingEnabled ? ANALYSIS_WIDTH_GPU : ANALYSIS_WIDTH_CPU;
        long analysisDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(analysisTimeoutSeconds);
        StageProfiler profiler = stageProfilingEnabled
            ? StageProfiler.enabled(videoId, fileName, intent.name(), 0)
            : StageProfiler.disabled();

        int segmentCount = computeIntraVideoSegmentCount(durationUs);
        int effectiveDecodeThreads = resolveEffectiveDecodeThreads(segmentCount);
        boolean effectiveDecodeHwAccel = decodeHwAccelEnabled && segmentCount <= 2;
        profiler.setSegmentCount(segmentCount);
        // Shared progress tracker: each slot holds how many microseconds segment i has processed.
        AtomicLong[] segProgUs = new AtomicLong[segmentCount];
        for (int i = 0; i < segmentCount; i++) segProgUs[i] = new AtomicLong(0L);
        List<SceneMatch> matches;

        if (segmentCount <= 1) {
            matches = processVideoSegment(videoPath, 0L, Long.MAX_VALUE, durationUs,
                    intent, colorQuery, analysisWidth, sampleStepUs, analysisDeadlineNanos,
                    progress, fileName, 0, segProgUs, profiler,
                    effectiveDecodeHwAccel, effectiveDecodeThreads);
        } else {
            long segmentDuration = durationUs / segmentCount;
            List<CompletableFuture<List<SceneMatch>>> futures = new ArrayList<>();
            for (int i = 0; i < segmentCount; i++) {
                final long startUs = (long) i * segmentDuration;
                final long endUs = (i == segmentCount - 1) ? Long.MAX_VALUE : startUs + segmentDuration;
                final int segIdx = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> processVideoSegment(videoPath, startUs, endUs, durationUs,
                        intent, colorQuery, analysisWidth, sampleStepUs, analysisDeadlineNanos,
                    progress, fileName, segIdx, segProgUs, profiler,
                    effectiveDecodeHwAccel, effectiveDecodeThreads),
                        segmentExecutor));
            }
            matches = new ArrayList<>();
            for (CompletableFuture<List<SceneMatch>> future : futures) {
                try {
                    long remainingNanos = analysisDeadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        future.cancel(true);
                        continue;
                    }
                    matches.addAll(future.get(remainingNanos, TimeUnit.NANOSECONDS));
                } catch (TimeoutException timeoutException) {
                    future.cancel(true);
                    LOG.warn("Segment timeout during analyzeVideo; canceled one segment for file={}", fileName);
                } catch (Exception ignored) {
                    // Segment failure is non-fatal; continue with other segments.
                }
            }
            for (CompletableFuture<List<SceneMatch>> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }

        matches = eventPostProcessor.postProcess(intent, matches);

        matches.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());
        if (matches.size() > MAX_MATCHES) {
            matches = new ArrayList<>(matches.subList(0, MAX_MATCHES));
        }
        matches.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));

        String modeLabel = eventScoringService.modeLabel(intent);
        String note = eventScoringService.modeNote(intent);
        note = note + " | " + gpuProcessingStatus + " | Szegmensek: " + segmentCount;

        profiler.logSummary(durationUs, matches.size(), sampleStepUs, analysisWidth, gpuProcessingStatus);

        return new SearchOutcome(videoId, query, modeLabel, note, durationUs / 1_000_000.0, matches, fileName);
    }

    private List<SceneMatch> processVideoSegment(
            Path videoPath,
            long segmentStartUs,
            long segmentEndUs,
            long fullVideoDurationUs,
            QueryIntent intent,
            ColorQuery colorQuery,
            int analysisWidth,
            long sampleStepUs,
            long analysisDeadlineNanos,
            JobProgress progress,
            String fileName,
            int segmentIndex,
            AtomicLong[] segmentProgressUs,
            StageProfiler profiler,
            boolean effectiveDecodeHwAccel,
            int effectiveDecodeThreads) {
        List<SceneMatch> matches = new ArrayList<>();
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile());
        // Tell FFmpeg to rescale output frames to analysis width before returning to Java.
        // This moves the (expensive) full-resolution YUV→BGR conversion down to analysis size,
        // dramatically reducing per-frame cost – especially important for skipped frames.
        grabber.setImageWidth(analysisWidth);
        try {
            videoDecoderService.startGrabberWithGpuFallback(grabber, effectiveDecodeHwAccel, effectiveDecodeThreads);
            // Seek to a warm-up window before the actual segment start so the EMA,
            // longitudinal axis, and deer-track state are primed before emitting matches.
            long warmupStartUs = (segmentStartUs > 0L)
                    ? Math.max(0L, segmentStartUs - SEGMENT_WARMUP_US)
                    : 0L;
            if (warmupStartUs > 0L) {
                grabber.setTimestamp(warmupStartUs);
            }

            Java2DFrameConverter converter = new Java2DFrameConverter();
            OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();
            Mat previousSampleMat = null;
            double previousResidualCentroidX = Double.NaN;
            double previousResidualCentroidY = Double.NaN;
            double longitudinalAxisX = 0.0;
            double longitudinalAxisY = 1.0;
            double motionEma = 0.0;
            double previousActiveRatio = Double.NaN;
            List<TrackPoint> deerTrack = new ArrayList<>();
            long nextSampleUs = warmupStartUs;

            Frame frame;
            while (true) {
                long grabStarted = System.nanoTime();
                frame = grabber.grabImage();
                profiler.addGrabDecodeNs(System.nanoTime() - grabStarted);
                if (frame == null) {
                    break;
                }

                long loopStarted = System.nanoTime();
                profiler.incrementFramesSeen();

                try {
                    if (Thread.currentThread().isInterrupted()) break;
                    if (System.nanoTime() > analysisDeadlineNanos) break;

                    long timestampUs = Math.max(grabber.getTimestamp(), 0L);
                    if (segmentEndUs != Long.MAX_VALUE && timestampUs >= segmentEndUs) break;

                    if (progress != null && fullVideoDurationUs > 0 && segmentProgressUs != null) {
                        long progressStarted = System.nanoTime();
                        // Update this segment's share monotonically (never go backward).
                        long localUs = Math.max(0L, timestampUs - Math.max(0L, segmentStartUs));
                        segmentProgressUs[segmentIndex].accumulateAndGet(localUs, Math::max);
                        // Sum all segments to get a stable, never-decreasing overall percent.
                        long totalUs = 0L;
                        for (AtomicLong al : segmentProgressUs) totalUs += al.get();
                        int percent = (int) Math.min(99L, totalUs * 100L / fullVideoDurationUs);
                        progress.updateFileFrame(fileName, percent);
                        profiler.addProgressUpdateNs(System.nanoTime() - progressStarted);
                    }
                    if (timestampUs < nextSampleUs) {
                        continue;
                    }

                    profiler.incrementFramesSampled();

                    long prepStarted = System.nanoTime();
                    Mat frameMat = matConverter.convert(frame);
                    if (frameMat == null || frameMat.empty()) {
                        continue;
                    }
                    Mat scaledMat = scale(frameMat, analysisWidth);
                    if (scaledMat == null || scaledMat.empty()) {
                        continue;
                    }
                    ColorStats colorStats = computeColorStats(scaledMat);
                    profiler.addConvertScaleColorNs(System.nanoTime() - prepStarted);

                    long motionStarted = System.nanoTime();
                    MotionMetrics motionMetrics = previousSampleMat == null
                            ? MotionMetrics.empty()
                            : computeMotionMetricsWithBackend(
                                    previousSampleMat,
                                    scaledMat,
                                    previousResidualCentroidX,
                                    previousResidualCentroidY,
                                    longitudinalAxisX,
                                    longitudinalAxisY
                            );
                    profiler.addMotionNs(System.nanoTime() - motionStarted);
                    previousResidualCentroidX = motionMetrics.centroidX;
                    previousResidualCentroidY = motionMetrics.centroidY;

                    double shiftMagnitude = Math.hypot(motionMetrics.globalShiftX, motionMetrics.globalShiftY);
                    if (shiftMagnitude >= 0.45) {
                        double shiftUnitX = motionMetrics.globalShiftX / shiftMagnitude;
                        double shiftUnitY = motionMetrics.globalShiftY / shiftMagnitude;
                        longitudinalAxisX = longitudinalAxisX * 0.88 + shiftUnitX * 0.12;
                        longitudinalAxisY = longitudinalAxisY * 0.88 + shiftUnitY * 0.12;
                        double axisMagnitude = Math.hypot(longitudinalAxisX, longitudinalAxisY);
                        if (axisMagnitude > 1e-6) {
                            longitudinalAxisX /= axisMagnitude;
                            longitudinalAxisY /= axisMagnitude;
                        }
                    }

                    double burstBase = eventScoringService.usesWildlifePath(intent)
                            ? motionMetrics.residualIntensity
                            : motionMetrics.intensity;
                    double burstScore = Math.max(0.0, burstBase - motionEma);
                    motionEma = motionEma == 0.0
                            ? burstBase
                            : (motionEma * 0.85 + burstBase * 0.15);
                    double activeGrowth = Double.isNaN(previousActiveRatio)
                            ? 0.0
                            : Math.max(0.0, motionMetrics.activeRatio - previousActiveRatio);
                    previousActiveRatio = motionMetrics.activeRatio;

                    double axisNormNow = Math.hypot(longitudinalAxisX, longitudinalAxisY);
                    double axisXNow = axisNormNow < 1e-6 ? 0.0 : longitudinalAxisX / axisNormNow;
                    double axisYNow = axisNormNow < 1e-6 ? 1.0 : longitudinalAxisY / axisNormNow;
                    double lateralTrackScore = 0.0;
                    if (eventScoringService.usesWildlifePath(intent)) {
                        lateralTrackScore = computeLateralTrackScore(
                                deerTrack,
                                timestampUs / 1_000_000.0,
                                motionMetrics,
                                axisXNow,
                                axisYNow
                        );
                    }
                    if (previousSampleMat != null) {
                        previousSampleMat.release();
                    }
                    previousSampleMat = scaledMat.clone();
                    scaledMat.release();

                    long scoringStarted = System.nanoTime();
                    double score;
                    String reason;

                    if (intent == QueryIntent.COLOR) {
                        double colorDominance = colorStats.dominance(colorQuery);
                        double colorMotionBoost = clamp((motionMetrics.residualIntensity - 0.010) / 0.070, 0.0, 1.0);
                        score = clamp(colorDominance * 0.78 + colorMotionBoost * 0.22, 0.0, 1.0);
                        reason = String.format(
                                Locale.ROOT,
                                "Szindominancia (%s): %.1f%% | Mozgas-boost: %.1f%%",
                                colorQuery.displayName(),
                                colorDominance * 100,
                                colorMotionBoost * 100
                        );
                    } else if (eventScoringService.usesWildlifePath(intent)) {
                        double vehicleColorSignal = Math.max(colorStats.redDominance, colorStats.blueDominance);
                        double neutralSignal = colorStats.neutralDominance;
                        boolean strongCrossingCandidate = lateralTrackScore >= 0.78
                                && motionMetrics.crossMotionRatio >= 0.48
                                && motionMetrics.residualIntensity >= 0.020
                                && vehicleColorSignal < 0.20;
                        boolean crossingCore = lateralTrackScore >= 0.70
                                && motionMetrics.crossMotionRatio >= 0.40;
                        boolean likelyRoadVehicleByColor = vehicleColorSignal >= 0.20
                                && motionMetrics.centerRatio >= 0.30
                                && motionMetrics.residualIntensity >= 0.030;

                        if (!crossingCore) {
                            continue;
                        }
                        if (likelyRoadVehicleByColor && !strongCrossingCandidate) {
                            continue;
                        }
                        if (looksLikeOncomingVehicle(motionMetrics, activeGrowth)
                                || looksLikePseudoLateralGrowth(motionMetrics, activeGrowth, lateralTrackScore)) {
                            continue;
                        }
                        if (!strongCrossingCandidate
                                && (looksLikeCenteredLowLateralVehicle(motionMetrics, lateralTrackScore)
                                || looksLikeLowCrossHighTrackVehicle(motionMetrics, lateralTrackScore, vehicleColorSignal)
                                || looksLikeOvertrackedRoadFlow(motionMetrics, lateralTrackScore)
                                || looksLikeCenterApproach(motionMetrics, lateralTrackScore)
                                || looksLikeVehicleColorBlob(motionMetrics, colorStats, lateralTrackScore)
                                || looksLikeColorfulMidLateralRoadVehicle(motionMetrics, lateralTrackScore, vehicleColorSignal)
                                || looksLikeHighLateralRoadSweep(motionMetrics, lateralTrackScore, vehicleColorSignal)
                                || looksLikeColoredRoadSweep(motionMetrics, lateralTrackScore, vehicleColorSignal)
                                || looksLikeRoadVehicleProfile(motionMetrics, lateralTrackScore, vehicleColorSignal, neutralSignal))) {
                            continue;
                        }
                        score = computeDeerScore(
                                motionMetrics,
                                burstScore,
                                activeGrowth,
                                lateralTrackScore,
                                vehicleColorSignal,
                                colorStats.neutralDominance
                        );
                        reason = String.format(
                                Locale.ROOT,
                                "Szarvas-heurisztika: %.1f%% | Keresztmozgas: %.1f%% | Kiemelt mozgas: %.1f%% | Kozepso regio: %.1f%% | Oldalpalya: %.1f%% | Autoszin: %.1f%%",
                                score * 100,
                                motionMetrics.crossMotionRatio * 100,
                                motionMetrics.residualIntensity * 100,
                                motionMetrics.centerRatio * 100,
                                lateralTrackScore * 100,
                                vehicleColorSignal * 100
                        );
                    } else {
                        score = motionMetrics.intensity;
                        reason = String.format(Locale.ROOT, "Mozgas-intenzitas: %.1f%%", motionMetrics.intensity * 100);
                    }
                    profiler.addScoringNs(System.nanoTime() - scoringStarted);

                    nextSampleUs = frameSampler.advanceSampleCursor(timestampUs, nextSampleUs, sampleStepUs);

                    // During the warm-up window, update state but don't emit matches.
                    if (timestampUs < segmentStartUs) {
                        continue;
                    }

                    if (!eventScoringService.isMatch(intent, score)) {
                        continue;
                    }

                    long previewStarted = System.nanoTime();
                    BufferedImage image = converter.convert(frame);
                    if (image == null) {
                        continue;
                    }
                    String preview = createPreviewDataUrl(image);
                    profiler.addPreviewNs(System.nanoTime() - previewStarted);
                    double timestampSeconds = timestampUs / 1_000_000.0;
                    if (eventScoringService.usesWildlifePath(intent)) {
                        double deerLeadSeconds = computeDeerTimestampLeadSeconds(motionMetrics, lateralTrackScore, score);
                        timestampSeconds = Math.max(0.0, timestampSeconds - deerLeadSeconds);
                    }
                    matches.add(new SceneMatch(timestampSeconds, score, reason, preview));
                    profiler.incrementMatchesEmitted();
                } finally {
                    profiler.addLoopNs(System.nanoTime() - loopStarted);
                }
            }

            if (previousSampleMat != null) {
                previousSampleMat.release();
            }
            grabber.stop();
        } catch (Exception ex) {
            // Segment failure is non-fatal; return whatever matches were accumulated.
        } finally {
            try { grabber.close(); } catch (Exception ignored) {}
        }
        return matches;
    }

    private int computeIntraVideoSegmentCount(long durationUs) {
        if (durationUs < 5_000_000L) {
            return 1;
        }
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        int maxSegByGrabberLimit = Math.max(1, maxConcurrentGrabbers / Math.max(1, analysisThreadCount));

        if (configuredSegmentCount > 0) {
            // Explicit override from application.properties.
            return Math.max(1, Math.min(configuredSegmentCount, maxSegByGrabberLimit));
        }
        // Auto: one segment per ~4 seconds, capped at CPU core count.
        int byDuration = (int) Math.max(1L, durationUs / 4_000_000L);
        int autoSegments = Math.min(cores, byDuration);
        return Math.max(1, Math.min(autoSegments, maxSegByGrabberLimit));
    }

    private int resolveEffectiveDecodeThreads(int segmentCount) {
        int requested = decodeThreadCount <= 0 ? 1 : Math.max(1, decodeThreadCount);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        long concurrentGrabbers = (long) Math.max(1, analysisThreadCount) * Math.max(1, segmentCount);

        if (concurrentGrabbers >= cores) {
            return 1;
        }
        if (concurrentGrabbers * requested > (long) cores * 2L) {
            return 1;
        }
        return requested;
    }

    private static int normalizeThreadCount(int configuredThreadCount) {
        int available = Math.max(1, Runtime.getRuntime().availableProcessors());
        if (configuredThreadCount > 0) {
            return Math.max(1, configuredThreadCount);
        }
        // Oversubscription keeps CPU and OpenCL pipeline fed when many videos are analyzed in parallel.
        return Math.max(2, Math.min(32, available * 2));
    }

    private static boolean resolveGpuProcessingEnabled(boolean gpuProcessingRequested) {
        if (!gpuProcessingRequested) {
            return false;
        }
        try {
            boolean openClAvailable = haveOpenCL();
            setUseOpenCL(openClAvailable);
            return openClAvailable && useOpenCL();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double computeDeerScore(MotionMetrics motion,
                                           double burstScore,
                                           double activeGrowth,
                                           double lateralTrackScore,
                                           double vehicleColorSignal,
                                           double neutralDominance) {
        double compactness = motion.activeRatio <= 0.0001
                ? 0.0
            : clamp((motion.residualIntensity / motion.activeRatio) / 3.7, 0.0, 1.0);
        double foregroundIsolation = 1.0 - clamp(motion.activeRatio / 0.14, 0.0, 1.0);
        double residualGate = clamp((motion.residualIntensity - 0.020) / 0.070, 0.0, 1.0);
        double lateralTravelGate = clamp((motion.crossTravel - 0.008) / 0.032, 0.0, 1.0);
        double directionalConfidence = clamp(
            ((motion.crossMotionRatio * 0.28)
                + (lateralTravelGate * 0.33)
                + (lateralTrackScore * 0.39))
                        * motion.travelScore
                        * residualGate,
                0.0,
                1.0
        );
        double burstGate = clamp(burstScore / 0.070, 0.0, 1.0);

        double centerPenalty = clamp((motion.centerRatio - ONCOMING_CENTER_RATIO_MIN) / 0.45, 0.0, 1.0);
        double growthPenalty = clamp((activeGrowth - ONCOMING_ACTIVE_GROWTH_MIN) / 0.018, 0.0, 1.0);
        double lowCrossPenalty = clamp((ONCOMING_LATERAL_TRAVEL_MAX - motion.crossTravel) / ONCOMING_LATERAL_TRAVEL_MAX, 0.0, 1.0);
        double lowTravelPenalty = clamp((ONCOMING_TRAVEL_SCORE_MAX - motion.travelScore) / ONCOMING_TRAVEL_SCORE_MAX, 0.0, 1.0);
        double oncomingPenalty = (centerPenalty * 0.35)
                + (growthPenalty * 0.30)
                + (lowCrossPenalty * 0.20)
                + (lowTravelPenalty * 0.15);
        oncomingPenalty *= clamp((motion.residualIntensity - 0.035) / 0.070, 0.0, 1.0);
        double colorPenalty = clamp((vehicleColorSignal - 0.055) / 0.20, 0.0, 1.0)
            * clamp((motion.centerRatio - 0.32) / 0.50, 0.0, 1.0)
            * clamp((0.36 - lateralTrackScore) / 0.36, 0.0, 1.0);
        double neutralPenalty = clamp((neutralDominance - 0.18) / 0.30, 0.0, 1.0)
            * clamp((motion.centerRatio - 0.30) / 0.55, 0.0, 1.0)
            * clamp((0.42 - lateralTrackScore) / 0.42, 0.0, 1.0);
        double overtrackedFlowPenalty = clamp((motion.crossMotionRatio - 0.80) / 0.14, 0.0, 1.0)
            * clamp((motion.centerRatio - 0.28) / 0.20, 0.0, 1.0)
            * clamp((lateralTrackScore - 0.50) / 0.40, 0.0, 1.0);

        double combined =
            directionalConfidence * 0.63 +
            residualGate * 0.10 +
            burstGate * 0.15 +
            foregroundIsolation * 0.06 +
            compactness * 0.06;

        combined -= oncomingPenalty * 0.42;
        combined -= colorPenalty * 0.30;
        combined -= neutralPenalty * 0.20;
        combined -= overtrackedFlowPenalty * 0.26;

        return clamp(combined, 0.0, 1.0);
    }

    private static boolean looksLikeOncomingVehicle(MotionMetrics motion, double activeGrowth) {
        boolean centerLocked = motion.centerRatio >= ONCOMING_CENTER_RATIO_MIN
                && Math.abs(motion.centroidX - 0.5) <= 0.26;
        boolean lowLateralTravel = motion.crossTravel <= ONCOMING_LATERAL_TRAVEL_MAX;
        boolean slowLateralCentroid = motion.travelScore <= ONCOMING_TRAVEL_SCORE_MAX;
        boolean growingObject = activeGrowth >= ONCOMING_ACTIVE_GROWTH_MIN || motion.activeRatio >= 0.055;
        boolean sufficientlyStrong = motion.residualIntensity >= 0.045;
        return centerLocked && growingObject && sufficientlyStrong && (lowLateralTravel || slowLateralCentroid);
    }

    private static boolean looksLikePseudoLateralGrowth(MotionMetrics motion, double activeGrowth, double lateralTrackScore) {
        return motion.crossMotionRatio >= 0.55
                && motion.crossTravel <= WEAK_LATERAL_CROSS_TRAVEL_MAX
                && motion.travelScore <= ONCOMING_TRAVEL_SCORE_MAX
                && lateralTrackScore < 0.30
                && (activeGrowth >= ONCOMING_ACTIVE_GROWTH_MIN || motion.centerRatio >= 0.38);
    }

    private static boolean looksLikeCenteredLowLateralVehicle(MotionMetrics motion, double lateralTrackScore) {
        return motion.centerRatio >= 0.46
                && lateralTrackScore <= 0.24
                && motion.crossMotionRatio <= 0.58
                && motion.residualIntensity >= 0.045;
    }

    private static boolean looksLikeLowCrossHighTrackVehicle(MotionMetrics motion,
                                                             double lateralTrackScore,
                                                             double vehicleColorSignal) {
        return lateralTrackScore >= 0.65
                && motion.crossMotionRatio <= 0.38
                && motion.centerRatio >= 0.40
                && vehicleColorSignal >= 0.14
                && motion.residualIntensity >= 0.060;
    }

    private static boolean looksLikeColorfulMidLateralRoadVehicle(MotionMetrics motion,
                                                                   double lateralTrackScore,
                                                                   double vehicleColorSignal) {
        return vehicleColorSignal >= 0.20
                && motion.centerRatio >= 0.38
                && lateralTrackScore >= 0.55
                && lateralTrackScore <= 0.90
                && motion.crossMotionRatio <= 0.70
                && motion.residualIntensity >= 0.045;
    }

    private static boolean looksLikeOvertrackedRoadFlow(MotionMetrics motion, double lateralTrackScore) {
        return motion.crossMotionRatio >= OVERTRACKED_FLOW_CROSS_MIN
                && motion.centerRatio >= OVERTRACKED_FLOW_CENTER_MIN
                && lateralTrackScore >= OVERTRACKED_FLOW_LATERAL_MIN
                && motion.residualIntensity >= OVERTRACKED_FLOW_RESIDUAL_MIN;
    }

    private static boolean looksLikeColoredRoadSweep(MotionMetrics motion,
                                                     double lateralTrackScore,
                                                     double vehicleColorSignal) {
        return vehicleColorSignal >= 0.24
                && lateralTrackScore >= 0.85
                && motion.crossMotionRatio >= 0.65
                && motion.centerRatio >= 0.18
                && motion.residualIntensity >= 0.030;
    }

    private static boolean looksLikeHighLateralRoadSweep(MotionMetrics motion,
                                                         double lateralTrackScore,
                                                         double vehicleColorSignal) {
        return vehicleColorSignal >= 0.16
                && lateralTrackScore >= 0.85
                && motion.crossMotionRatio >= 0.70
                && motion.centerRatio >= 0.30
                && motion.residualIntensity >= 0.045;
    }

    private static boolean looksLikeCenterApproach(MotionMetrics motion, double lateralTrackScore) {
        return motion.centerRatio >= CENTER_APPROACH_CENTER_RATIO_MIN
                && motion.travelScore <= CENTER_APPROACH_TRAVEL_SCORE_MAX
                && motion.crossTravel <= CENTER_APPROACH_CROSS_TRAVEL_MAX
                && lateralTrackScore < 0.35
                && motion.residualIntensity >= 0.032;
    }

    private static boolean looksLikeVehicleColorBlob(MotionMetrics motion, ColorStats colorStats, double lateralTrackScore) {
        double vehicleColor = Math.max(colorStats.redDominance, colorStats.blueDominance);
        boolean centerish = motion.centerRatio >= 0.32;
        boolean weakLateral = lateralTrackScore < 0.45
                && (motion.crossTravel <= 0.026 || motion.travelScore <= 0.46);
        boolean vividVehicle = vehicleColor >= VEHICLE_COLOR_DOMINANCE_FILTER;
        boolean neutralVehicle = colorStats.neutralDominance >= NEUTRAL_COLOR_DOMINANCE_FILTER;
        return centerish && weakLateral && (vividVehicle || neutralVehicle);
    }

    private static boolean looksLikeRoadVehicleProfile(MotionMetrics motion,
                                                       double lateralTrackScore,
                                                       double vehicleColorSignal,
                                                       double neutralSignal) {
        boolean roadLikeMotion = motion.centerRatio >= 0.34 && motion.residualIntensity >= 0.030;
        boolean colorfulVehicle = vehicleColorSignal >= ROAD_VEHICLE_COLOR_SIGNAL_MIN
            && (lateralTrackScore >= 0.70 || lateralTrackScore <= 0.22);
        boolean neutralVehicle = neutralSignal >= ROAD_VEHICLE_NEUTRAL_SIGNAL_MIN && lateralTrackScore < 0.80;
        return roadLikeMotion && (colorfulVehicle || neutralVehicle);
    }

    private static double computeLateralTrackScore(List<TrackPoint> deerTrack,
                                                   double timestampSeconds,
                                                   MotionMetrics motion,
                                                   double axisX,
                                                   double axisY) {
        if (motion.activeRatio < 0.008 || motion.residualIntensity < 0.018) {
            return 0.0;
        }

        deerTrack.add(new TrackPoint(timestampSeconds, motion.centroidX, motion.centroidY));
        while (!deerTrack.isEmpty() && timestampSeconds - deerTrack.get(0).timestampSeconds > DEER_TRACK_WINDOW_SECONDS) {
            deerTrack.remove(0);
        }

        if (deerTrack.size() < 2) {
            return 0.0;
        }

        TrackPoint first = deerTrack.get(0);
        double dx = motion.centroidX - first.centroidX;
        double dy = motion.centroidY - first.centroidY;
        double lateralDistance = Math.abs(dx * (-axisY) + dy * axisX);
        double lateralScore = clamp(
                (lateralDistance - DEER_TRACK_LATERAL_MIN) / (DEER_TRACK_LATERAL_STRONG - DEER_TRACK_LATERAL_MIN),
                0.0,
                1.0
        );
        double horizontalDistance = Math.abs(dx);
        double horizontalScore = clamp(
            (horizontalDistance - DEER_TRACK_X_MIN) / (DEER_TRACK_X_STRONG - DEER_TRACK_X_MIN),
            0.0,
            1.0
        );

        // True crossing should be both axis-lateral and visibly horizontal in image space.
        double blended = Math.sqrt(lateralScore * horizontalScore);
        if (motion.centerRatio >= 0.45 && horizontalScore < 0.35) {
            blended *= 0.55;
        }
        return clamp(blended, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double computeDeerTimestampLeadSeconds(MotionMetrics motion,
                                                          double lateralTrackScore,
                                                          double score) {
        double centerLead = clamp((motion.centerRatio - 0.18) / 0.30, 0.0, 1.0) * 2.4;
        double lowTravelLead = clamp((0.54 - motion.travelScore) / 0.30, 0.0, 1.0) * 0.9;
        double stableCrossingLead = clamp((lateralTrackScore - 0.68) / 0.32, 0.0, 1.0) * 0.5;
        double weakConfidenceLead = clamp((0.34 - score) / 0.16, 0.0, 1.0) * 3.5;

        double leadSeconds = DEER_TIMESTAMP_LEAD_BASE_SECONDS
                + centerLead
                + lowTravelLead
                + stableCrossingLead
                + weakConfidenceLead;
        return clamp(leadSeconds, DEER_TIMESTAMP_LEAD_BASE_SECONDS, DEER_TIMESTAMP_LEAD_MAX_SECONDS);
    }

    private static String sanitizeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "uploaded-video.mp4";
        }

        String name = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (name.length() > 80) {
            return name.substring(name.length() - 80);
        }
        return name;
    }

    private static BufferedImage scale(BufferedImage source, int maxWidth) {
        int width = source.getWidth();
        int height = source.getHeight();
        int targetWidth = Math.min(maxWidth, width);
        int targetHeight = Math.max(1, (int) Math.round((double) height * targetWidth / width));

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        return scaled;
    }

    private static Mat scale(Mat source, int maxWidth) {
        if (source == null || source.empty()) {
            return new Mat();
        }
        Mat bgrSource = ensureBgr(source);
        int width = bgrSource.cols();
        int height = bgrSource.rows();
        int targetWidth = Math.min(maxWidth, width);
        int targetHeight = Math.max(1, (int) Math.round((double) height * targetWidth / width));

        Mat scaled = new Mat();
        resize(bgrSource, scaled, new Size(targetWidth, targetHeight), 0, 0, INTER_AREA);
        if (bgrSource != source) {
            bgrSource.release();
        }
        return scaled;
    }

    private static Mat ensureBgr(Mat source) {
        if (source == null || source.empty()) {
            return new Mat();
        }
        if (source.channels() == 3) {
            return source;
        }
        Mat converted = new Mat();
        if (source.channels() == 4) {
            cvtColor(source, converted, COLOR_BGRA2BGR);
            return converted;
        }
        if (source.channels() == 1) {
            cvtColor(source, converted, COLOR_GRAY2BGR);
            return converted;
        }
        source.copyTo(converted);
        return converted;
    }

    private static ColorStats computeColorStats(Mat image) {
        Mat bgr = ensureBgr(image);
        if (bgr == null || bgr.empty() || bgr.data() == null) {
            return new ColorStats(0.0, 0.0, 0.0, 0.0);
        }

        int width = bgr.cols();
        int height = bgr.rows();
        long rowStep = bgr.step();
        BytePointer data = bgr.data();
        int total = 0;
        int redDominant = 0;
        int greenDominant = 0;
        int blueDominant = 0;
        int neutralDominant = 0;

        for (int y = 0; y < height; y += 2) {
            long rowOffset = y * rowStep;
            for (int x = 0; x < width; x += 2) {
                long px = rowOffset + (long) x * 3L;
                int b = data.get(px) & 0xFF;
                int g = data.get(px + 1) & 0xFF;
                int r = data.get(px + 2) & 0xFF;

                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int saturation = max - min;

                if (r > 80 && saturation > 28 && r > (int) (g * 1.20) && r > (int) (b * 1.20)) {
                    redDominant++;
                }
                if (g > 80 && saturation > 28 && g > (int) (r * 1.15) && g > (int) (b * 1.15)) {
                    greenDominant++;
                }
                if (b > 80 && saturation > 28 && b > (int) (r * 1.12) && b > (int) (g * 1.12)) {
                    blueDominant++;
                }
                if (saturation < 20 && max > 95) {
                    neutralDominant++;
                }
                total++;
            }
        }

        if (bgr != image) {
            bgr.release();
        }

        if (total == 0) {
            return new ColorStats(0.0, 0.0, 0.0, 0.0);
        }
        return new ColorStats(
                redDominant / (double) total,
                greenDominant / (double) total,
                blueDominant / (double) total,
                neutralDominant / (double) total
        );
    }

    private MotionMetrics computeMotionMetricsWithBackend(Mat previousMat,
                                                          Mat currentMat,
                                                          double previousCentroidX,
                                                          double previousCentroidY,
                                                          double longitudinalAxisX,
                                                          double longitudinalAxisY) {
        try {
            return computeMotionMetricsOpenCl(
                    previousMat,
                    currentMat,
                    previousCentroidX,
                    previousCentroidY,
                    longitudinalAxisX,
                    longitudinalAxisY,
                    gpuProcessingEnabled
            );
        } catch (Throwable ignored) {
            return computeMotionMetricsOpenCl(
                    previousMat,
                    currentMat,
                    previousCentroidX,
                    previousCentroidY,
                    longitudinalAxisX,
                    longitudinalAxisY,
                    false
            );
        }
    }

    private static MotionMetrics computeMotionMetricsOpenCl(Mat previous,
                                                            Mat current,
                                                            double previousCentroidX,
                                                            double previousCentroidY,
                                                            double longitudinalAxisX,
                                                            double longitudinalAxisY,
                                                            boolean preferGpu) {
        int width = Math.min(previous.cols(), current.cols());
        int height = Math.min(previous.rows(), current.rows());
        if (width <= 0 || height <= 0) {
            return MotionMetrics.empty();
        }

        Rect sharedRect = new Rect(0, 0, width, height);
        Mat previousShared = new Mat(previous, sharedRect);
        Mat currentShared = new Mat(current, sharedRect);
        try {
            GlobalShift shift = estimateGlobalShiftOpenCl(previousShared, currentShared, width, height);
            double intensity = rgbDiffMean01(previousShared, currentShared, preferGpu);

            int startX = Math.max(0, -shift.dx);
            int endX = Math.min(width, width - shift.dx);
            int startY = Math.max(0, -shift.dy);
            int endY = Math.min(height, height - shift.dy);
            int overlapWidth = endX - startX;
            int overlapHeight = endY - startY;

            if (overlapWidth < 8 || overlapHeight < 8) {
                return new MotionMetrics(
                        intensity,
                        0.0,
                        0.0,
                        0.0,
                        Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX,
                        Double.isNaN(previousCentroidY) ? 0.68 : previousCentroidY,
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        shift.dx,
                        shift.dy
                );
            }

            Rect prevOverlap = new Rect(startX, startY, overlapWidth, overlapHeight);
            Rect currOverlap = new Rect(startX + shift.dx, startY + shift.dy, overlapWidth, overlapHeight);
            Mat prevRoi = new Mat(previousShared, prevOverlap);
            Mat currRoi = new Mat(currentShared, currOverlap);
            Mat residualDiff = null;
            Mat residualGray = new Mat();
            Mat activeMask = new Mat();
            Mat motionMask = null;
            Mat centerMask = null;
            try {
                residualDiff = absDiffWithOptionalGpu(prevRoi, currRoi, preferGpu);
                double residualIntensity = scalarAverage(mean(residualDiff), 3) / 255.0;
                if (!buildBinaryMaskWithOptionalGpu(residualDiff, residualGray, activeMask, preferGpu)) {
                    cvtColor(residualDiff, residualGray, COLOR_BGR2GRAY);
                    threshold(residualGray, activeMask, 0.11 * 255.0, 255.0, THRESH_BINARY);
                }

                int roiStartX = (int) Math.round(width * 0.10);
                int roiEndX = (int) Math.round(width * 0.90);
                int roiStartY = (int) Math.round(height * 0.22);
                int roiEndY = (int) Math.round(height * 0.95);

                int centerStartX = (int) Math.round(width * 0.26);
                int centerEndX = (int) Math.round(width * 0.74);
                int centerStartY = (int) Math.round(height * 0.25);
                int centerEndY = (int) Math.round(height * 0.90);

                Rect motionRect = intersectRect(
                        startX,
                        startY,
                        endX,
                        endY,
                        roiStartX,
                        roiStartY,
                        roiEndX + 1,
                        roiEndY + 1,
                        -startX,
                        -startY
                );

                if (motionRect == null || motionRect.width() <= 0 || motionRect.height() <= 0) {
                    return new MotionMetrics(
                            intensity,
                            residualIntensity,
                            0.0,
                            0.0,
                            Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX,
                            Double.isNaN(previousCentroidY) ? 0.68 : previousCentroidY,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            shift.dx,
                            shift.dy
                    );
                }

                motionMask = new Mat(activeMask, motionRect);
                int active = countNonZero(motionMask);
                int roiSamples = motionRect.width() * motionRect.height();
                double activeRatio = active / (double) Math.max(1, roiSamples);

                double centroidX;
                double centroidY;
                if (active <= 0) {
                    centroidX = Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX;
                    centroidY = Double.isNaN(previousCentroidY) ? 0.68 : previousCentroidY;
                } else {
                    Moments maskMoments = moments(motionMask, true);
                    if (Math.abs(maskMoments.m00()) <= 1e-6) {
                        centroidX = Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX;
                        centroidY = Double.isNaN(previousCentroidY) ? 0.68 : previousCentroidY;
                    } else {
                        double localCx = maskMoments.m10() / maskMoments.m00();
                        double localCy = maskMoments.m01() / maskMoments.m00();
                        double globalPx = startX + motionRect.x() + localCx;
                        double globalPy = startY + motionRect.y() + localCy;
                        centroidX = clamp(globalPx / Math.max(1.0, (double) (width - 1)), 0.0, 1.0);
                        centroidY = clamp(globalPy / Math.max(1.0, (double) (height - 1)), 0.0, 1.0);
                    }
                }

                Rect centerRect = intersectRect(
                        startX,
                        startY,
                        endX,
                        endY,
                        centerStartX,
                        centerStartY,
                        centerEndX + 1,
                        centerEndY + 1,
                        -startX,
                        -startY
                );
                int centerActive = 0;
                if (centerRect != null && centerRect.width() > 0 && centerRect.height() > 0) {
                    centerMask = new Mat(activeMask, centerRect);
                    centerActive = countNonZero(centerMask);
                }
                double centerRatio = active <= 0 ? 0.0 : centerActive / (double) active;

                double objectShiftX = Double.isNaN(previousCentroidX) ? 0.0 : centroidX - previousCentroidX;
                double objectShiftY = Double.isNaN(previousCentroidY) ? 0.0 : centroidY - previousCentroidY;
                double objectSpeed = Math.hypot(objectShiftX, objectShiftY);

                double axisNorm = Math.hypot(longitudinalAxisX, longitudinalAxisY);
                double axisX = axisNorm < 1e-6 ? 0.0 : longitudinalAxisX / axisNorm;
                double axisY = axisNorm < 1e-6 ? 1.0 : longitudinalAxisY / axisNorm;

                double crossMotionRatio = 0.0;
                double crossTravel = 0.0;
                double parallelTravel = 0.0;
                if (!Double.isNaN(previousCentroidX) && !Double.isNaN(previousCentroidY) && objectSpeed > 1e-6) {
                    parallelTravel = Math.abs(objectShiftX * axisX + objectShiftY * axisY);
                    crossTravel = Math.abs(objectShiftX * (-axisY) + objectShiftY * axisX);
                    crossMotionRatio = crossTravel / (crossTravel + parallelTravel + 1e-6);
                }

                double travelScore = clamp(objectSpeed * 7.0, 0.0, 1.0);

                return new MotionMetrics(
                        intensity,
                        residualIntensity,
                        activeRatio,
                        centerRatio,
                        centroidX,
                        centroidY,
                        crossMotionRatio,
                        crossTravel,
                        parallelTravel,
                        travelScore,
                        shift.dx,
                        shift.dy
                );
            } finally {
                if (centerMask != null) {
                    centerMask.release();
                }
                if (motionMask != null) {
                    motionMask.release();
                }
                activeMask.release();
                residualGray.release();
                if (residualDiff != null) {
                    residualDiff.release();
                }
                currRoi.release();
                prevRoi.release();
            }
        } finally {
            currentShared.release();
            previousShared.release();
        }
    }

    private static GlobalShift estimateGlobalShiftOpenCl(Mat previous,
                                                         Mat current,
                                                         int width,
                                                         int height) {
        Mat previousGray = new Mat();
        Mat currentGray = new Mat();
        try {
            cvtColor(previous, previousGray, COLOR_BGR2GRAY);
            cvtColor(current, currentGray, COLOR_BGR2GRAY);

            int bestDx = 0;
            int bestDy = 0;
            double bestError = Double.MAX_VALUE;

            for (int dy = -GLOBAL_SHIFT_MAX_DY; dy <= GLOBAL_SHIFT_MAX_DY; dy++) {
                for (int dx = -GLOBAL_SHIFT_MAX_DX; dx <= GLOBAL_SHIFT_MAX_DX; dx++) {
                    int startX = Math.max(0, -dx);
                    int endX = Math.min(width, width - dx);
                    int startY = Math.max(0, -dy);
                    int endY = Math.min(height, height - dy);

                    int overlapWidth = endX - startX;
                    int overlapHeight = endY - startY;
                    if (overlapWidth < 24 || overlapHeight < 16) {
                        continue;
                    }

                    Rect prevRect = new Rect(startX, startY, overlapWidth, overlapHeight);
                    Rect currRect = new Rect(startX + dx, startY + dy, overlapWidth, overlapHeight);
                    Mat prevRoi = new Mat(previousGray, prevRect);
                    Mat currRoi = new Mat(currentGray, currRect);
                    try {
                        // In this tight nested loop GPU upload/download overhead is expensive, so keep it CPU-fast.
                        double error = grayDiffMean(prevRoi, currRoi, false);
                        error += Math.hypot(dx, dy) * 0.15;

                        if (error < bestError) {
                            bestError = error;
                            bestDx = dx;
                            bestDy = dy;
                        }
                    } finally {
                        currRoi.release();
                        prevRoi.release();
                    }
                }
            }

            return new GlobalShift(bestDx, bestDy);
        } finally {
            currentGray.release();
            previousGray.release();
        }
    }

    private static double rgbDiffMean01(Mat a, Mat b, boolean preferGpu) {
        Scalar diffMean = meanAbsDiff(a, b, preferGpu);
        return scalarAverage(diffMean, 3) / 255.0;
    }

    private static double grayDiffMean(Mat a, Mat b, boolean preferGpu) {
        Scalar diffMean = meanAbsDiff(a, b, preferGpu);
        return diffMean.get(0);
    }

    private static Scalar meanAbsDiff(Mat a, Mat b, boolean preferGpu) {
        if (preferGpu) {
            try {
                UMat ua = new UMat();
                UMat ub = new UMat();
                UMat ud = new UMat();
                a.copyTo(ua);
                b.copyTo(ub);
                absdiff(ua, ub, ud);
                Scalar s = mean(ud);
                ua.release();
                ub.release();
                ud.release();
                return s;
            } catch (Throwable ignored) {
                // Fall through to CPU path.
            }
        }
        Mat diff = new Mat();
        absdiff(a, b, diff);
        Scalar s = mean(diff);
        diff.release();
        return s;
    }

    private static Mat absDiffWithOptionalGpu(Mat a, Mat b, boolean preferGpu) {
        if (preferGpu) {
            try {
                UMat ua = new UMat();
                UMat ub = new UMat();
                UMat ud = new UMat();
                a.copyTo(ua);
                b.copyTo(ub);
                absdiff(ua, ub, ud);
                Mat out = new Mat();
                ud.copyTo(out);
                ua.release();
                ub.release();
                ud.release();
                return out;
            } catch (Throwable ignored) {
                // Fall through to CPU path.
            }
        }
        Mat out = new Mat();
        absdiff(a, b, out);
        return out;
    }

    private static boolean buildBinaryMaskWithOptionalGpu(Mat residualDiff,
                                                           Mat residualGray,
                                                           Mat activeMask,
                                                           boolean preferGpu) {
        if (!preferGpu) {
            return false;
        }
        try {
            UMat uResidual = new UMat();
            UMat uGray = new UMat();
            UMat uMask = new UMat();
            residualDiff.copyTo(uResidual);
            cvtColor(uResidual, uGray, COLOR_BGR2GRAY);
            threshold(uGray, uMask, 0.11 * 255.0, 255.0, THRESH_BINARY);
            uGray.copyTo(residualGray);
            uMask.copyTo(activeMask);
            uMask.release();
            uGray.release();
            uResidual.release();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double scalarAverage(Scalar scalar, int channels) {
        double sum = 0.0;
        for (int i = 0; i < channels; i++) {
            sum += scalar.get(i);
        }
        return sum / channels;
    }

    private static Rect intersectRect(int aStartX,
                                      int aStartY,
                                      int aEndX,
                                      int aEndY,
                                      int bStartX,
                                      int bStartY,
                                      int bEndX,
                                      int bEndY,
                                      int offsetX,
                                      int offsetY) {
        int x1 = Math.max(aStartX, bStartX);
        int y1 = Math.max(aStartY, bStartY);
        int x2 = Math.min(aEndX, bEndX);
        int y2 = Math.min(aEndY, bEndY);
        if (x2 <= x1 || y2 <= y1) {
            return null;
        }
        return new Rect(x1 + offsetX, y1 + offsetY, x2 - x1, y2 - y1);
    }

    private static MotionMetrics computeMotionMetrics(BufferedImage previous,
                                                      BufferedImage current,
                                                      double previousCentroidX,
                                                      double previousCentroidY,
                                                      double longitudinalAxisX,
                                                      double longitudinalAxisY) {
        int width = Math.min(previous.getWidth(), current.getWidth());
        int height = Math.min(previous.getHeight(), current.getHeight());

        GlobalShift shift = estimateGlobalShift(previous, current, width, height);

        double rawSum = 0.0;
        int rawSamples = 0;

        double residualSum = 0.0;
        int residualSamples = 0;

        int roiSamples = 0;
        int active = 0;

        double motionWeight = 0.0;
        double weightedX = 0.0;
        double weightedY = 0.0;
        double centerWeight = 0.0;

        int roiStartX = (int) Math.round(width * 0.10);
        int roiEndX = (int) Math.round(width * 0.90);
        int roiStartY = (int) Math.round(height * 0.22);
        int roiEndY = (int) Math.round(height * 0.95);

        int centerStartX = (int) Math.round(width * 0.26);
        int centerEndX = (int) Math.round(width * 0.74);
        int centerStartY = (int) Math.round(height * 0.25);
        int centerEndY = (int) Math.round(height * 0.90);

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int rgbA = previous.getRGB(x, y);
                int rgbRaw = current.getRGB(x, y);

                int rawDiffR = Math.abs(((rgbA >> 16) & 0xFF) - ((rgbRaw >> 16) & 0xFF));
                int rawDiffG = Math.abs(((rgbA >> 8) & 0xFF) - ((rgbRaw >> 8) & 0xFF));
                int rawDiffB = Math.abs((rgbA & 0xFF) - (rgbRaw & 0xFF));
                rawSum += (rawDiffR + rawDiffG + rawDiffB) / 765.0;
                rawSamples++;

                int shiftedX = x + shift.dx;
                int shiftedY = y + shift.dy;
                if (shiftedX < 0 || shiftedX >= width || shiftedY < 0 || shiftedY >= height) {
                    continue;
                }

                int rgbB = current.getRGB(shiftedX, shiftedY);

                int rDiff = Math.abs(((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF));
                int gDiff = Math.abs(((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF));
                int bDiff = Math.abs((rgbA & 0xFF) - (rgbB & 0xFF));

                double diff = (rDiff + gDiff + bDiff) / 765.0;
                residualSum += diff;
                residualSamples++;

                boolean inRoi = x >= roiStartX && x <= roiEndX && y >= roiStartY && y <= roiEndY;
                if (!inRoi) {
                    continue;
                }

                roiSamples++;

                if (diff >= 0.11) {
                    active++;
                    motionWeight += diff;
                    weightedX += x * diff;
                    weightedY += y * diff;
                    if (x >= centerStartX && x <= centerEndX && y >= centerStartY && y <= centerEndY) {
                        centerWeight += diff;
                    }
                }
            }
        }

        if (rawSamples == 0) {
            return MotionMetrics.empty();
        }

        double intensity = rawSum / rawSamples;
        double residualIntensity = residualSamples == 0 ? 0.0 : residualSum / residualSamples;
        double activeRatio = active / (double) Math.max(1, roiSamples);

        double centroidX;
        double centroidY;
        if (motionWeight <= 0.0) {
            centroidX = Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX;
            centroidY = Double.isNaN(previousCentroidY) ? 0.68 : previousCentroidY;
        } else {
            centroidX = (weightedX / motionWeight) / Math.max(1.0, (double) (width - 1));
            centroidY = (weightedY / motionWeight) / Math.max(1.0, (double) (height - 1));
        }
        centroidX = clamp(centroidX, 0.0, 1.0);
        centroidY = clamp(centroidY, 0.0, 1.0);

        double centerRatio = motionWeight <= 0.0 ? 0.0 : centerWeight / motionWeight;
        double objectShiftX = Double.isNaN(previousCentroidX) ? 0.0 : centroidX - previousCentroidX;
        double objectShiftY = Double.isNaN(previousCentroidY) ? 0.0 : centroidY - previousCentroidY;
        double objectSpeed = Math.hypot(objectShiftX, objectShiftY);

        double axisNorm = Math.hypot(longitudinalAxisX, longitudinalAxisY);
        double axisX = axisNorm < 1e-6 ? 0.0 : longitudinalAxisX / axisNorm;
        double axisY = axisNorm < 1e-6 ? 1.0 : longitudinalAxisY / axisNorm;

        double crossMotionRatio = 0.0;
        double crossTravel = 0.0;
        double parallelTravel = 0.0;
        if (!Double.isNaN(previousCentroidX) && !Double.isNaN(previousCentroidY) && objectSpeed > 1e-6) {
            parallelTravel = Math.abs(objectShiftX * axisX + objectShiftY * axisY);
            crossTravel = Math.abs(objectShiftX * (-axisY) + objectShiftY * axisX);
            crossMotionRatio = crossTravel / (crossTravel + parallelTravel + 1e-6);
        }

        double travelScore = clamp(objectSpeed * 7.0, 0.0, 1.0);

        return new MotionMetrics(
                intensity,
                residualIntensity,
                activeRatio,
                centerRatio,
                centroidX,
                centroidY,
                crossMotionRatio,
                crossTravel,
                parallelTravel,
                travelScore,
                shift.dx,
                shift.dy
        );
    }

    private static GlobalShift estimateGlobalShift(BufferedImage previous,
                                                   BufferedImage current,
                                                   int width,
                                                   int height) {
        int bestDx = 0;
        int bestDy = 0;
        double bestError = Double.MAX_VALUE;

        for (int dy = -GLOBAL_SHIFT_MAX_DY; dy <= GLOBAL_SHIFT_MAX_DY; dy++) {
            for (int dx = -GLOBAL_SHIFT_MAX_DX; dx <= GLOBAL_SHIFT_MAX_DX; dx++) {
                int startX = Math.max(0, -dx);
                int endX = Math.min(width, width - dx);
                int startY = Math.max(0, -dy);
                int endY = Math.min(height, height - dy);

                if (endX - startX < 24 || endY - startY < 16) {
                    continue;
                }

                double errorSum = 0.0;
                int samples = 0;
                for (int y = startY; y < endY; y += GLOBAL_SHIFT_SAMPLE_STRIDE) {
                    for (int x = startX; x < endX; x += GLOBAL_SHIFT_SAMPLE_STRIDE) {
                        int lumaPrev = luma(previous.getRGB(x, y));
                        int lumaCurr = luma(current.getRGB(x + dx, y + dy));
                        errorSum += Math.abs(lumaPrev - lumaCurr);
                        samples++;
                    }
                }

                if (samples == 0) {
                    continue;
                }

                double error = errorSum / samples;
                error += Math.hypot(dx, dy) * 0.15;

                if (error < bestError) {
                    bestError = error;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }

        return new GlobalShift(bestDx, bestDy);
    }

    private static int luma(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000;
    }

    private static final class StageProfiler {
        private final boolean enabled;
        private final String videoId;
        private final String fileName;
        private final String intent;
        private volatile int segmentCount;

        private final LongAdder framesSeen = new LongAdder();
        private final LongAdder framesSampled = new LongAdder();
        private final LongAdder matchesEmitted = new LongAdder();

        private final LongAdder loopNs = new LongAdder();
        private final LongAdder grabDecodeNs = new LongAdder();
        private final LongAdder convertScaleColorNs = new LongAdder();
        private final LongAdder motionNs = new LongAdder();
        private final LongAdder scoringNs = new LongAdder();
        private final LongAdder previewNs = new LongAdder();
        private final LongAdder progressUpdateNs = new LongAdder();

        private StageProfiler(boolean enabled, String videoId, String fileName, String intent, int segmentCount) {
            this.enabled = enabled;
            this.videoId = videoId;
            this.fileName = fileName;
            this.intent = intent;
            this.segmentCount = segmentCount;
        }

        private static StageProfiler enabled(String videoId, String fileName, String intent, int segmentCount) {
            return new StageProfiler(true, videoId, fileName, intent, segmentCount);
        }

        private static StageProfiler disabled() {
            return new StageProfiler(false, "", "", "", 0);
        }

        private void setSegmentCount(int segmentCount) {
            if (!enabled) {
                return;
            }
            this.segmentCount = segmentCount;
        }

        private void incrementFramesSeen() {
            if (enabled) {
                framesSeen.increment();
            }
        }

        private void incrementFramesSampled() {
            if (enabled) {
                framesSampled.increment();
            }
        }

        private void incrementMatchesEmitted() {
            if (enabled) {
                matchesEmitted.increment();
            }
        }

        private void addLoopNs(long value) {
            if (enabled) {
                loopNs.add(value);
            }
        }

        private void addGrabDecodeNs(long value) {
            if (enabled) {
                grabDecodeNs.add(value);
            }
        }

        private void addConvertScaleColorNs(long value) {
            if (enabled) {
                convertScaleColorNs.add(value);
            }
        }

        private void addMotionNs(long value) {
            if (enabled) {
                motionNs.add(value);
            }
        }

        private void addScoringNs(long value) {
            if (enabled) {
                scoringNs.add(value);
            }
        }

        private void addPreviewNs(long value) {
            if (enabled) {
                previewNs.add(value);
            }
        }

        private void addProgressUpdateNs(long value) {
            if (enabled) {
                progressUpdateNs.add(value);
            }
        }

        private void logSummary(long durationUs,
                                int finalMatches,
                                long sampleStepUs,
                                int analysisWidth,
                                String gpuStatus) {
            if (!enabled) {
                return;
            }

            long totalLoopNs = loopNs.sum();
            if (totalLoopNs <= 0) {
                return;
            }

            LinkedHashMap<String, Long> stageNs = new LinkedHashMap<>();
            stageNs.put("grabDecode", grabDecodeNs.sum());
            stageNs.put("convertScaleColor", convertScaleColorNs.sum());
            stageNs.put("motion", motionNs.sum());
            stageNs.put("scoring", scoringNs.sum());
            stageNs.put("preview", previewNs.sum());
            stageNs.put("progressUpdate", progressUpdateNs.sum());

            String topStages = stageNs.entrySet()
                    .stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .map(entry -> entry.getKey()
                            + "="
                            + String.format(Locale.ROOT, "%.1f%%", entry.getValue() * 100.0 / totalLoopNs)
                            + "(" + String.format(Locale.ROOT, "%.1f", entry.getValue() / 1_000_000.0) + "ms)")
                    .collect(Collectors.joining(", "));

            LOG.info(
                    "ANALYSIS_STAGE_PROFILE videoId={} file={} intent={} segments={} durationSec={} sampleStepMs={} analysisWidth={} gpuStatus='{}' framesSeen={} framesSampled={} matchesEmitted={} finalMatches={} topStages=[{}] totalsMs(loop={} grabDecode={} prep={} motion={} scoring={} preview={} progress={})",
                    videoId,
                    fileName,
                    intent,
                    segmentCount,
                    String.format(Locale.ROOT, "%.2f", durationUs / 1_000_000.0),
                    String.format(Locale.ROOT, "%.2f", sampleStepUs / 1000.0),
                    analysisWidth,
                    gpuStatus,
                    framesSeen.sum(),
                    framesSampled.sum(),
                    matchesEmitted.sum(),
                    finalMatches,
                    topStages,
                    String.format(Locale.ROOT, "%.1f", totalLoopNs / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", grabDecodeNs.sum() / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", convertScaleColorNs.sum() / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", motionNs.sum() / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", scoringNs.sum() / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", previewNs.sum() / 1_000_000.0),
                    String.format(Locale.ROOT, "%.1f", progressUpdateNs.sum() / 1_000_000.0)
            );
        }
    }

    private static final class ColorStats {
        private final double redDominance;
        private final double greenDominance;
        private final double blueDominance;
        private final double neutralDominance;

        private ColorStats(double redDominance,
                           double greenDominance,
                           double blueDominance,
                           double neutralDominance) {
            this.redDominance = redDominance;
            this.greenDominance = greenDominance;
            this.blueDominance = blueDominance;
            this.neutralDominance = neutralDominance;
        }

        private double dominance(ColorQuery query) {
            double best = 0.0;
            if (query.wantsRed()) {
                best = Math.max(best, redDominance);
            }
            if (query.wantsGreen()) {
                best = Math.max(best, greenDominance);
            }
            if (query.wantsBlue()) {
                best = Math.max(best, blueDominance);
            }
            return best;
        }
    }

    private static final class TrackPoint {
        private final double timestampSeconds;
        private final double centroidX;
        private final double centroidY;

        private TrackPoint(double timestampSeconds, double centroidX, double centroidY) {
            this.timestampSeconds = timestampSeconds;
            this.centroidX = centroidX;
            this.centroidY = centroidY;
        }
    }

    private static final class MotionMetrics {
        private final double intensity;
        private final double residualIntensity;
        private final double activeRatio;
        private final double centerRatio;
        private final double centroidX;
        private final double centroidY;
        private final double crossMotionRatio;
        private final double crossTravel;
        private final double parallelTravel;
        private final double travelScore;
        private final double globalShiftX;
        private final double globalShiftY;

        private MotionMetrics(double intensity,
                              double residualIntensity,
                              double activeRatio,
                              double centerRatio,
                              double centroidX,
                              double centroidY,
                              double crossMotionRatio,
                              double crossTravel,
                              double parallelTravel,
                              double travelScore,
                              double globalShiftX,
                              double globalShiftY) {
            this.intensity = intensity;
            this.residualIntensity = residualIntensity;
            this.activeRatio = activeRatio;
            this.centerRatio = centerRatio;
            this.centroidX = centroidX;
            this.centroidY = centroidY;
            this.crossMotionRatio = crossMotionRatio;
            this.crossTravel = crossTravel;
            this.parallelTravel = parallelTravel;
            this.travelScore = travelScore;
            this.globalShiftX = globalShiftX;
            this.globalShiftY = globalShiftY;
        }

        private static MotionMetrics empty() {
            return new MotionMetrics(0.0, 0.0, 0.0, 0.0, 0.5, 0.68, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static final class GlobalShift {
        private final int dx;
        private final int dy;

        private GlobalShift(int dx, int dy) {
            this.dx = dx;
            this.dy = dy;
        }
    }

    private static String createPreviewDataUrl(BufferedImage image) {
        try {
            BufferedImage preview = scale(image, 260);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(preview, "jpg", output);
            String encoded = Base64.getEncoder().encodeToString(output.toByteArray());
            return "data:image/jpeg;base64," + encoded;
        } catch (IOException ex) {
            return "";
        }
    }
}
