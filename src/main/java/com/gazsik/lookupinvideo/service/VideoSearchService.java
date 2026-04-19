package com.gazsik.lookupinvideo.service;

import com.gazsik.lookupinvideo.model.SceneMatch;
import com.gazsik.lookupinvideo.model.SearchOutcome;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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
import java.text.Normalizer;
import com.gazsik.lookupinvideo.model.JobProgress;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class VideoSearchService {

    private static final long DEFAULT_SAMPLE_STEP_US = 1_000_000L;
    private static final long DEER_SAMPLE_STEP_US = 250_000L;
    private static final int MAX_MATCHES = 12;
    private static final double DEER_TIMESTAMP_LEAD_SECONDS = 1.1;
    private static final double DEER_CLUSTER_WINDOW_SECONDS = 2.6;
    private static final int GLOBAL_SHIFT_MAX_DX = 8;
    private static final int GLOBAL_SHIFT_MAX_DY = 6;
    private static final int GLOBAL_SHIFT_SAMPLE_STRIDE = 4;
    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".avi", ".mov", ".mkv", ".wmv", ".flv", ".webm", ".m4v", ".mpeg", ".mpg");

    private final Path storagePath;
    private final Map<String, Path> videoRegistry = new ConcurrentHashMap<>();
    private final Map<String, JobProgress> progressMap = new ConcurrentHashMap<>();
    private final Map<String, List<SearchOutcome>> dirResultMap = new ConcurrentHashMap<>();

    public VideoSearchService(@Value("${lookup.video.storage-path:uploads}") String storageDir) throws IOException {
        this.storagePath = Paths.get(storageDir).toAbsolutePath().normalize();
        Files.createDirectories(this.storagePath);
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
        int threadCount = Math.min(videoFiles.size(), Runtime.getRuntime().availableProcessors());
        JobProgress progress = new JobProgress();
        progress.startParallel(videoFiles.size(), threadCount);
        progressMap.put(jobId, progress);

        final String q = query == null ? "" : query.trim();
        final int total = videoFiles.size();

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        List<CompletableFuture<SearchOutcome>> futures = videoFiles.stream()
                .map(videoPath -> CompletableFuture.supplyAsync(() -> {
                    String fileName = videoPath.getFileName().toString();
                    String videoId = UUID.randomUUID().toString();
                    videoRegistry.put(videoId, videoPath);
                    try {
                        SearchOutcome outcome = analyzeVideo(videoId, videoPath, q, fileName, progress);
                        progress.fileCompleted(fileName);
                        return outcome.getMatches().isEmpty() ? null : outcome;
                    } catch (Exception ex) {
                        progress.fileCompleted(fileName);
                        return null;
                    }
                }, pool))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    List<SearchOutcome> outcomes = futures.stream()
                            .map(f -> { try { return f.get(); } catch (Exception e) { return null; } })
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(SearchOutcome::getFileName))
                            .collect(Collectors.toList());
                    dirResultMap.put(jobId, outcomes);
                    progress.done(total);
                    pool.shutdown();
                });

        return jobId;
    }

    public JobProgress getProgress(String jobId) {
        return progressMap.get(jobId);
    }

    public List<SearchOutcome> getDirectoryResults(String jobId) {
        return dirResultMap.get(jobId);
    }

    private SearchOutcome analyzeVideo(String videoId, Path videoPath, String query,
                                        String fileName, JobProgress progress) {
        String normalizedQuery = stripAccents(query).toLowerCase(Locale.ROOT);
        QueryMode mode = resolveMode(normalizedQuery);

        List<SceneMatch> matches = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.start();

            long durationUs = Math.max(grabber.getLengthInTime(), 0L);
            long sampleStepUs = sampleStepForMode(mode);
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage previousSample = null;
            double previousResidualCentroidX = Double.NaN;
            double previousResidualCentroidY = Double.NaN;
            double longitudinalAxisX = 0.0;
            double longitudinalAxisY = 1.0;
            double motionEma = 0.0;
            long nextSampleUs = 0L;

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                long timestampUs = Math.max(grabber.getTimestamp(), 0L);
                if (progress != null && durationUs > 0) {
                    progress.updateFrame((int) (timestampUs * 100L / durationUs));
                }
                if (timestampUs < nextSampleUs) {
                    continue;
                }

                BufferedImage image = converter.convert(frame);
                if (image == null) {
                    continue;
                }

                BufferedImage scaled = scale(image, 320);
                double redScore = computeRedRatio(scaled);

                MotionMetrics motionMetrics = previousSample == null
                        ? MotionMetrics.empty()
                    : computeMotionMetrics(
                    previousSample,
                    scaled,
                    previousResidualCentroidX,
                    previousResidualCentroidY,
                    longitudinalAxisX,
                    longitudinalAxisY
                );
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

                double burstBase = mode == QueryMode.DEER ? motionMetrics.residualIntensity : motionMetrics.intensity;
                double burstScore = Math.max(0.0, burstBase - motionEma);
                motionEma = motionEma == 0.0
                    ? burstBase
                    : (motionEma * 0.85 + burstBase * 0.15);
                previousSample = scaled;

                double score;
                String reason;

                if (mode == QueryMode.RED) {
                    score = redScore;
                    reason = String.format(Locale.ROOT, "Piros dominancia: %.1f%%", redScore * 100);
                } else if (mode == QueryMode.DEER) {
                    score = computeDeerScore(motionMetrics, burstScore);
                    reason = String.format(
                            Locale.ROOT,
                            "Szarvas-heurisztika: %.1f%% | Keresztmozgas: %.1f%% | Kiemelt mozgas: %.1f%% | Kozepso regio: %.1f%%",
                            score * 100,
                            motionMetrics.crossMotionRatio * 100,
                            motionMetrics.residualIntensity * 100,
                            motionMetrics.centerRatio * 100
                    );
                } else {
                    score = motionMetrics.intensity;
                    reason = String.format(Locale.ROOT, "Mozgas-intenzitas: %.1f%%", motionMetrics.intensity * 100);
                }

                if (!isMatch(mode, score)) {
                    continue;
                }

                String preview = createPreviewDataUrl(image);
                double timestampSeconds = timestampUs / 1_000_000.0;
                if (mode == QueryMode.DEER) {
                    // Deer crossings are often scored at motion peak; shift towards crossing start for better UX.
                    timestampSeconds = Math.max(0.0, timestampSeconds - DEER_TIMESTAMP_LEAD_SECONDS);
                }
                matches.add(new SceneMatch(timestampSeconds, score, reason, preview));

                do {
                    nextSampleUs += sampleStepUs;
                } while (timestampUs >= nextSampleUs);

                if (durationUs > 0 && timestampUs >= durationUs) {
                    break;
                }
            }

            grabber.stop();

            if (mode == QueryMode.DEER) {
                matches = postProcessDeerMatches(matches);
            }

            matches.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());
            if (matches.size() > MAX_MATCHES) {
                matches = new ArrayList<>(matches.subList(0, MAX_MATCHES));
            }
            matches.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));

            String modeLabel;
            String note;
            if (mode == QueryMode.RED) {
                modeLabel = "Szinalapu kereses (piros dominancia)";
                note = "A kereses most a piros szin dominanciajara optimalizalt, jo alap a tovabbi boviteshez.";
            } else if (mode == QueryMode.DEER) {
                modeLabel = "Szarvas keresese keresztmozgas alapjan";
                note = "A szarvas/deer kulcsszavaknal hattermozgast kompenzalunk, majd a keresztiranyu lokalis mozgast pontozzuk (ugyanazzal a logikaval jobbrol-balra es balrol-jobbra esetben is).";
            } else {
                modeLabel = "Demo: mozgasalapu jelenet-kereses";
                note = "A megadott szoveget fogadjuk, de objektumfelismeres helyett jelenleg mozgas-intenzitas alapjan rangsorolunk.";
            }

            return new SearchOutcome(videoId, query, modeLabel, note, durationUs / 1_000_000.0, matches, fileName);
        } catch (Exception ex) {
            throw new IllegalStateException("A video feldolgozasa sikertelen: " + ex.getMessage(), ex);
        }
    }

    private static QueryMode resolveMode(String normalizedQuery) {
        if (normalizedQuery.contains("piros") || normalizedQuery.contains("red")) {
            return QueryMode.RED;
        }
        if (normalizedQuery.contains("szarvas") || normalizedQuery.contains("deer")) {
            return QueryMode.DEER;
        }
        return QueryMode.MOTION;
    }

    private static long sampleStepForMode(QueryMode mode) {
        return mode == QueryMode.DEER ? DEER_SAMPLE_STEP_US : DEFAULT_SAMPLE_STEP_US;
    }

    private static boolean isMatch(QueryMode mode, double score) {
        return switch (mode) {
            case RED -> score >= 0.12;
            case DEER -> score >= 0.09;
            case MOTION -> score >= 0.18;
        };
    }

    private static List<SceneMatch> postProcessDeerMatches(List<SceneMatch> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        List<SceneMatch> byTime = new ArrayList<>(candidates);
        byTime.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));

        List<SceneMatch> representatives = selectTemporalRepresentatives(byTime, DEER_CLUSTER_WINDOW_SECONDS);
        representatives.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());

        double topScore = representatives.get(0).getConfidence();
        double dynamicThreshold = clamp(topScore * 0.50, 0.09, 0.29);

        List<SceneMatch> filtered = new ArrayList<>();
        for (SceneMatch candidate : representatives) {
            if (candidate.getConfidence() < dynamicThreshold && filtered.size() >= 3) {
                continue;
            }
            if (isTooCloseInTime(filtered, candidate, 0.95)) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= MAX_MATCHES) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            filtered.add(representatives.get(0));
        }
        return filtered;
    }

    private static List<SceneMatch> selectTemporalRepresentatives(List<SceneMatch> byTime, double clusterWindowSeconds) {
        List<SceneMatch> representatives = new ArrayList<>();

        SceneMatch earliestInCluster = null;
        SceneMatch strongestInCluster = null;
        double clusterStart = 0.0;

        for (SceneMatch candidate : byTime) {
            if (strongestInCluster == null) {
                earliestInCluster = candidate;
                strongestInCluster = candidate;
                clusterStart = candidate.getTimestampSeconds();
                continue;
            }

            if (candidate.getTimestampSeconds() - clusterStart <= clusterWindowSeconds) {
                if (candidate.getConfidence() > strongestInCluster.getConfidence()) {
                    strongestInCluster = candidate;
                }
                continue;
            }

            representatives.add(mergeClusterMatch(strongestInCluster, earliestInCluster));
            earliestInCluster = candidate;
            strongestInCluster = candidate;
            clusterStart = candidate.getTimestampSeconds();
        }

        if (strongestInCluster != null && earliestInCluster != null) {
            representatives.add(mergeClusterMatch(strongestInCluster, earliestInCluster));
        }

        return representatives;
    }

    private static SceneMatch mergeClusterMatch(SceneMatch strongest, SceneMatch earliest) {
        return new SceneMatch(
                earliest.getTimestampSeconds(),
                strongest.getConfidence(),
                strongest.getReason(),
                strongest.getPreviewDataUrl()
        );
    }

    private static boolean isTooCloseInTime(List<SceneMatch> selected, SceneMatch candidate, double minGapSeconds) {
        for (SceneMatch existing : selected) {
            if (Math.abs(existing.getTimestampSeconds() - candidate.getTimestampSeconds()) < minGapSeconds) {
                return true;
            }
        }
        return false;
    }

    private static double computeDeerScore(MotionMetrics motion, double burstScore) {
        double compactness = motion.activeRatio <= 0.0001
                ? 0.0
            : clamp((motion.residualIntensity / motion.activeRatio) / 3.7, 0.0, 1.0);
        double foregroundIsolation = 1.0 - clamp(motion.activeRatio / 0.14, 0.0, 1.0);
        double residualGate = clamp((motion.residualIntensity - 0.010) / 0.070, 0.0, 1.0);
        double directionalConfidence = clamp(motion.crossMotionRatio * motion.travelScore * residualGate, 0.0, 1.0);
        double laneCenterFocus = 1.0 - clamp(Math.abs(motion.centroidX - 0.5) / 0.5, 0.0, 1.0);

        double combined =
            directionalConfidence * 0.40 +
            motion.residualIntensity * 0.22 +
            motion.centerRatio * 0.11 +
            burstScore * 0.10 +
            foregroundIsolation * 0.06 +
            compactness * 0.07 +
            laneCenterFocus * 0.04;

        return clamp(combined, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

    private static String stripAccents(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{M}", "");
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

    private static double computeRedRatio(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int total = 0;
        int redDominant = 0;

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;

                if (r > 90 && r > (int) (g * 1.25) && r > (int) (b * 1.25)) {
                    redDominant++;
                }
                total++;
            }
        }

        if (total == 0) {
            return 0.0;
        }
        return redDominant / (double) total;
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
        if (!Double.isNaN(previousCentroidX) && !Double.isNaN(previousCentroidY) && objectSpeed > 1e-6) {
            double parallel = Math.abs(objectShiftX * axisX + objectShiftY * axisY);
            double cross = Math.abs(objectShiftX * (-axisY) + objectShiftY * axisX);
            crossMotionRatio = cross / (cross + parallel + 1e-6);
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

    private enum QueryMode {
        RED,
        DEER,
        MOTION
    }

    private static final class MotionMetrics {
        private final double intensity;
        private final double residualIntensity;
        private final double activeRatio;
        private final double centerRatio;
        private final double centroidX;
        private final double centroidY;
        private final double crossMotionRatio;
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
            this.travelScore = travelScore;
            this.globalShiftX = globalShiftX;
            this.globalShiftY = globalShiftY;
        }

        private static MotionMetrics empty() {
            return new MotionMetrics(0.0, 0.0, 0.0, 0.0, 0.5, 0.68, 0.0, 0.0, 0.0, 0.0);
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
