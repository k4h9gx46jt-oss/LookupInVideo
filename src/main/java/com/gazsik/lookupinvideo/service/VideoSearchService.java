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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class VideoSearchService {

    private static final long DEFAULT_SAMPLE_STEP_US = 1_000_000L;
    private static final long DEER_SAMPLE_STEP_US = 250_000L;
    private static final int MAX_MATCHES = 12;
    private static final double DEER_TIMESTAMP_LEAD_SECONDS = 1.1;

    private final Path storagePath;
    private final Map<String, Path> videoRegistry = new ConcurrentHashMap<>();

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
        return analyzeVideo(videoId, targetPath, query == null ? "" : query.trim());
    }

    public Path resolveVideoPath(String videoId) {
        return videoRegistry.get(videoId);
    }

    private SearchOutcome analyzeVideo(String videoId, Path videoPath, String query) {
        String normalizedQuery = stripAccents(query).toLowerCase(Locale.ROOT);
        QueryMode mode = resolveMode(normalizedQuery);

        List<SceneMatch> matches = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.start();

            long durationUs = Math.max(grabber.getLengthInTime(), 0L);
            long sampleStepUs = sampleStepForMode(mode);
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage previousSample = null;
            double previousCentroidX = Double.NaN;
            double motionEma = 0.0;
            long nextSampleUs = 0L;

            Frame frame;
            while ((frame = grabber.grabImage()) != null) {
                long timestampUs = Math.max(grabber.getTimestamp(), 0L);
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
                        : computeMotionMetrics(previousSample, scaled, previousCentroidX);
                previousCentroidX = motionMetrics.centroidX;

                double burstScore = Math.max(0.0, motionMetrics.intensity - motionEma);
                motionEma = motionEma == 0.0
                        ? motionMetrics.intensity
                        : (motionEma * 0.85 + motionMetrics.intensity * 0.15);
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
                            "Szarvas-heurisztika: %.1f%% | Mozgas: %.1f%% | Kozepso sav: %.1f%%",
                            score * 100,
                            motionMetrics.intensity * 100,
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
                note = "A szarvas/deer kulcsszavaknal surubb mintavetellel, kozepso-sav hangsullyal es mozgas-kitorest figyelo heurisztikaval pontozunk.";
            } else {
                modeLabel = "Demo: mozgasalapu jelenet-kereses";
                note = "A megadott szoveget fogadjuk, de objektumfelismeres helyett jelenleg mozgas-intenzitas alapjan rangsorolunk.";
            }

            return new SearchOutcome(videoId, query, modeLabel, note, durationUs / 1_000_000.0, matches);
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
            case DEER -> score >= 0.08;
            case MOTION -> score >= 0.18;
        };
    }

    private static List<SceneMatch> postProcessDeerMatches(List<SceneMatch> candidates) {
        if (candidates.isEmpty()) {
            return candidates;
        }

        candidates.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());
        double topScore = candidates.get(0).getConfidence();
        double dynamicThreshold = Math.max(0.08, topScore * 0.65);

        List<SceneMatch> filtered = new ArrayList<>();
        for (SceneMatch candidate : candidates) {
            if (candidate.getConfidence() < dynamicThreshold) {
                continue;
            }
            if (isTooCloseInTime(filtered, candidate, 0.8)) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= MAX_MATCHES) {
                break;
            }
        }

        if (filtered.isEmpty()) {
            filtered.add(candidates.get(0));
        }
        return filtered;
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
        // Prefer local crossing-like movement over broad scene changes.
        double compactness = motion.activeRatio <= 0.0001
                ? 0.0
                : clamp((motion.intensity / motion.activeRatio) / 4.0, 0.0, 1.0);

        double combined =
                motion.intensity * 1.8 +
                motion.centerRatio * 0.35 +
                motion.lateralShift * 1.2 +
                burstScore * 2.5 +
                compactness * 0.18;

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
                                                      double previousCentroidX) {
        int width = Math.min(previous.getWidth(), current.getWidth());
        int height = Math.min(previous.getHeight(), current.getHeight());

        double sum = 0.0;
        int samples = 0;
        int active = 0;

        double motionWeight = 0.0;
        double weightedX = 0.0;
        double centerWeight = 0.0;

        int centerStart = (int) Math.round(width * 0.28);
        int centerEnd = (int) Math.round(width * 0.72);

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int rgbA = previous.getRGB(x, y);
                int rgbB = current.getRGB(x, y);

                int rDiff = Math.abs(((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF));
                int gDiff = Math.abs(((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF));
                int bDiff = Math.abs((rgbA & 0xFF) - (rgbB & 0xFF));

                double diff = (rDiff + gDiff + bDiff) / 765.0;
                sum += diff;
                samples++;

                if (diff >= 0.16) {
                    active++;
                    motionWeight += diff;
                    weightedX += x * diff;
                    if (x >= centerStart && x <= centerEnd) {
                        centerWeight += diff;
                    }
                }
            }
        }

        if (samples == 0) {
            return MotionMetrics.empty();
        }

        double intensity = sum / samples;
        double activeRatio = active / (double) samples;

        double centroidX;
        if (motionWeight <= 0.0) {
            centroidX = Double.isNaN(previousCentroidX) ? 0.5 : previousCentroidX;
        } else {
            centroidX = (weightedX / motionWeight) / Math.max(1.0, (double) (width - 1));
        }
        centroidX = clamp(centroidX, 0.0, 1.0);

        double centerRatio = motionWeight <= 0.0 ? 0.0 : centerWeight / motionWeight;
        double lateralShift = Double.isNaN(previousCentroidX) ? 0.0 : Math.abs(centroidX - previousCentroidX);

        return new MotionMetrics(intensity, activeRatio, centerRatio, centroidX, lateralShift);
    }

    private enum QueryMode {
        RED,
        DEER,
        MOTION
    }

    private static final class MotionMetrics {
        private final double intensity;
        private final double activeRatio;
        private final double centerRatio;
        private final double centroidX;
        private final double lateralShift;

        private MotionMetrics(double intensity,
                              double activeRatio,
                              double centerRatio,
                              double centroidX,
                              double lateralShift) {
            this.intensity = intensity;
            this.activeRatio = activeRatio;
            this.centerRatio = centerRatio;
            this.centroidX = centroidX;
            this.lateralShift = lateralShift;
        }

        private static MotionMetrics empty() {
            return new MotionMetrics(0.0, 0.0, 0.0, 0.5, 0.0);
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
