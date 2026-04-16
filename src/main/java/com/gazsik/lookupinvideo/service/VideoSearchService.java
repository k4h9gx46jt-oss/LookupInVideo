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

    private static final long SAMPLE_STEP_US = 1_000_000L;
    private static final int MAX_MATCHES = 12;

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
        boolean redMode = normalizedQuery.contains("piros") || normalizedQuery.contains("red");

        List<SceneMatch> matches = new ArrayList<>();

        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoPath.toFile())) {
            grabber.start();

            long durationUs = Math.max(grabber.getLengthInTime(), 0L);
            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage previousSample = null;

            for (long timestampUs = 0L; timestampUs <= durationUs; timestampUs += SAMPLE_STEP_US) {
                grabber.setTimestamp(timestampUs);
                Frame frame = grabber.grabImage();
                if (frame == null) {
                    continue;
                }

                BufferedImage image = converter.convert(frame);
                if (image == null) {
                    continue;
                }

                BufferedImage scaled = scale(image, 320);
                double redScore = computeRedRatio(scaled);
                double motionScore = previousSample == null ? 0.0 : computeMotionScore(previousSample, scaled);
                previousSample = scaled;

                double score = redMode ? redScore : motionScore;
                if (!isMatch(redMode, score)) {
                    continue;
                }

                String reason = redMode
                        ? String.format(Locale.ROOT, "Piros dominancia: %.1f%%", redScore * 100)
                        : String.format(Locale.ROOT, "Mozgas-intenzitas: %.1f%%", motionScore * 100);
                String preview = createPreviewDataUrl(image);
                matches.add(new SceneMatch(timestampUs / 1_000_000.0, score, reason, preview));
            }

            grabber.stop();

            matches.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());
            if (matches.size() > MAX_MATCHES) {
                matches = new ArrayList<>(matches.subList(0, MAX_MATCHES));
            }
            matches.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));

            String mode = redMode
                    ? "Szinalapu kereses (piros dominancia)"
                    : "Demo: mozgasalapu jelenet-kereses";
            String note = redMode
                    ? "A kereses most a piros szin dominanciajara optimalizalt, jo alap a tovabbi boviteshez."
                    : "A megadott szoveget fogadjuk, de objektumfelismeres helyett jelenleg mozgas-intenzitas alapjan rangsorolunk.";

            return new SearchOutcome(videoId, query, mode, note, durationUs / 1_000_000.0, matches);
        } catch (Exception ex) {
            throw new IllegalStateException("A video feldolgozasa sikertelen: " + ex.getMessage(), ex);
        }
    }

    private static boolean isMatch(boolean redMode, double score) {
        return redMode ? score >= 0.12 : score >= 0.18;
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

    private static double computeMotionScore(BufferedImage previous, BufferedImage current) {
        int width = Math.min(previous.getWidth(), current.getWidth());
        int height = Math.min(previous.getHeight(), current.getHeight());

        double sum = 0.0;
        int samples = 0;

        for (int y = 0; y < height; y += 2) {
            for (int x = 0; x < width; x += 2) {
                int rgbA = previous.getRGB(x, y);
                int rgbB = current.getRGB(x, y);

                int rDiff = Math.abs(((rgbA >> 16) & 0xFF) - ((rgbB >> 16) & 0xFF));
                int gDiff = Math.abs(((rgbA >> 8) & 0xFF) - ((rgbB >> 8) & 0xFF));
                int bDiff = Math.abs((rgbA & 0xFF) - (rgbB & 0xFF));

                sum += (rDiff + gDiff + bDiff) / 765.0;
                samples++;
            }
        }

        if (samples == 0) {
            return 0.0;
        }
        return sum / samples;
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
