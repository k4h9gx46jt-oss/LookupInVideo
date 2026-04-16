package com.gazsik.lookupinvideo.model;

public class SceneMatch {

    private final double timestampSeconds;
    private final double confidence;
    private final String reason;
    private final String previewDataUrl;

    public SceneMatch(double timestampSeconds, double confidence, String reason, String previewDataUrl) {
        this.timestampSeconds = timestampSeconds;
        this.confidence = confidence;
        this.reason = reason;
        this.previewDataUrl = previewDataUrl;
    }

    public double getTimestampSeconds() {
        return timestampSeconds;
    }

    public double getConfidence() {
        return confidence;
    }

    public String getReason() {
        return reason;
    }

    public String getPreviewDataUrl() {
        return previewDataUrl;
    }

    public String getFormattedTimestamp() {
        int totalSeconds = (int) Math.floor(timestampSeconds);
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%02d:%02d", minutes, seconds);
    }
}
