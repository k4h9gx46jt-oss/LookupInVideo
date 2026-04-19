package com.gazsik.lookupinvideo.model;

import java.util.List;

public class SearchOutcome {

    private final String videoId;
    private final String query;
    private final String mode;
    private final String note;
    private final double durationSeconds;
    private final List<SceneMatch> matches;
    private final String fileName;

    public SearchOutcome(String videoId,
                         String query,
                         String mode,
                         String note,
                         double durationSeconds,
                         List<SceneMatch> matches) {
        this(videoId, query, mode, note, durationSeconds, matches, "");
    }

    public SearchOutcome(String videoId,
                         String query,
                         String mode,
                         String note,
                         double durationSeconds,
                         List<SceneMatch> matches,
                         String fileName) {
        this.videoId = videoId;
        this.query = query;
        this.mode = mode;
        this.note = note;
        this.durationSeconds = durationSeconds;
        this.matches = List.copyOf(matches);
        this.fileName = fileName != null ? fileName : "";
    }

    public String getVideoId() {
        return videoId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getQuery() {
        return query;
    }

    public String getMode() {
        return mode;
    }

    public String getNote() {
        return note;
    }

    public double getDurationSeconds() {
        return durationSeconds;
    }

    public List<SceneMatch> getMatches() {
        return matches;
    }

    public String getFormattedDuration() {
        int totalSeconds = (int) Math.floor(durationSeconds);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
}
