package com.gazsik.lookupinvideo.infrastructure.processing;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import com.gazsik.lookupinvideo.model.SceneMatch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class EventPostProcessor {

    private static final int MAX_MATCHES = 12;
    private static final double WILDLIFE_CLUSTER_WINDOW_SECONDS = 2.6;
    /** Smaller dedup window for vehicle / event-style intents (avoid spamming a single moving object). */
    private static final double EVENT_CLUSTER_WINDOW_SECONDS = 1.4;
    private static final double EVENT_MIN_GAP_SECONDS = 0.7;

    public List<SceneMatch> postProcess(QueryIntent intent, List<SceneMatch> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (intent == QueryIntent.WILDLIFE) {
            return postProcessWildlifeMatches(candidates);
        }
        if (intent == QueryIntent.LANE_CHANGE
                || intent == QueryIntent.CROSSING_VEHICLE
                || intent == QueryIntent.ROAD_OBSTACLE
                || intent == QueryIntent.ANOMALY
                || intent == QueryIntent.TURN) {
            return postProcessEventMatches(candidates);
        }
        return new ArrayList<>(candidates);
    }

    private List<SceneMatch> postProcessEventMatches(List<SceneMatch> candidates) {
        List<SceneMatch> byTime = new ArrayList<>(candidates);
        byTime.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));
        List<SceneMatch> representatives = selectTemporalRepresentatives(byTime, EVENT_CLUSTER_WINDOW_SECONDS);
        representatives.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());

        List<SceneMatch> filtered = new ArrayList<>();
        for (SceneMatch candidate : representatives) {
            if (isTooCloseInTime(filtered, candidate, EVENT_MIN_GAP_SECONDS)) {
                continue;
            }
            filtered.add(candidate);
            if (filtered.size() >= MAX_MATCHES) {
                break;
            }
        }
        return filtered;
    }

    private List<SceneMatch> postProcessWildlifeMatches(List<SceneMatch> candidates) {
        List<SceneMatch> byTime = new ArrayList<>(candidates);
        byTime.sort(Comparator.comparingDouble(SceneMatch::getTimestampSeconds));

        List<SceneMatch> representatives = selectTemporalRepresentatives(byTime, WILDLIFE_CLUSTER_WINDOW_SECONDS);
        representatives.sort(Comparator.comparingDouble(SceneMatch::getConfidence).reversed());

        double topScore = representatives.get(0).getConfidence();
        double dynamicThreshold = clamp(topScore * 0.60, 0.19, 0.36);

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

        if (filtered.isEmpty() && topScore >= 0.20) {
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

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
