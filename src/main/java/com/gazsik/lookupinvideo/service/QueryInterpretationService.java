package com.gazsik.lookupinvideo.service;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import com.gazsik.lookupinvideo.domain.model.ColorQuery;
import com.gazsik.lookupinvideo.domain.model.SearchQueryInterpretation;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
public class QueryInterpretationService {

    public SearchQueryInterpretation interpret(String rawQuery) {
        String safeQuery = rawQuery == null ? "" : rawQuery.trim();
        String normalizedQuery = normalizeQuery(safeQuery);
        QueryIntent intent = resolveIntent(normalizedQuery);
        ColorQuery colorQuery = intent == QueryIntent.COLOR
                ? ColorQuery.fromNormalizedQuery(normalizedQuery)
                : ColorQuery.none();

        return new SearchQueryInterpretation(safeQuery, normalizedQuery, intent, colorQuery);
    }

    public String normalizeQuery(String query) {
        String safe = query == null ? "" : query;
        String normalized = Normalizer.normalize(safe, Normalizer.Form.NFD);
        String stripped = normalized.replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT);
    }

    public QueryIntent resolveIntent(String normalizedQuery) {
        if (containsColorKeyword(normalizedQuery)) {
            return QueryIntent.COLOR;
        }

        if (containsAny(normalizedQuery, "szarvas", "deer", "wildlife", "animal", "vad")) {
            return QueryIntent.WILDLIFE;
        }

        if (containsAny(normalizedQuery, "savalt", "savvaltas", "lane change", "lane_change")) {
            return QueryIntent.LANE_CHANGE;
        }

        if (containsAny(normalizedQuery, "kanyar", "turn", "balra", "jobbra")) {
            return QueryIntent.TURN;
        }

        if (containsAny(normalizedQuery, "keresztbe", "crossing", "cross vehicle", "crossing vehicle")) {
            return QueryIntent.CROSSING_VEHICLE;
        }

        if (containsAny(normalizedQuery, "anomaly", "anomalia", "szabalytalan", "wrong way", "wrong-way", "veszely")) {
            return QueryIntent.ANOMALY;
        }

        if (containsAny(normalizedQuery, "akadaly", "obstacle", "road obstacle")) {
            return QueryIntent.ROAD_OBSTACLE;
        }

        return QueryIntent.MOTION;
    }

    private static boolean containsColorKeyword(String normalizedQuery) {
        return containsAny(normalizedQuery, "piros", "red", "zold", "green", "kek", "blue");
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
