package com.gazsik.lookupinvideo.service;

import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import com.gazsik.lookupinvideo.domain.model.ColorQuery;
import com.gazsik.lookupinvideo.domain.model.SearchQueryInterpretation;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Locale;

@Service
public class QueryInterpretationService {

    // -------------------------------------------------------------------------
    // Kulcsszó-csoportok intent-enként (szinonima + kontextus bővítés)
    // Prioritásrend: COLOR > WILDLIFE > LANE_CHANGE > TURN >
    //                CROSSING_VEHICLE > SUDDEN_STOP > OVERTAKE > ANOMALY >
    //                ROAD_OBSTACLE > MOTION
    // -------------------------------------------------------------------------

    // COLOR — szín kulcsszavak
    private static final String[] KW_COLOR = {
        "piros", "red",
        "zold", "green",
        "kek", "blue"
    };

    // WILDLIFE — vadállat, szarvas, útátkelő állat
    private static final String[] KW_WILDLIFE = {
        "szarvas", "vad", "oz",                            // magyar
        "deer", "wildlife", "animal",                      // angol alap
        "wild animal", "animal crossing", "road animal",   // szinonimák
        "animal on road", "roadside animal"                // kontextus
    };

    // LANE_CHANGE — sávváltás
    private static final String[] KW_LANE_CHANGE = {
        "savalt", "savvaltas", "besorol",                  // magyar
        "kicsúszik a savbol", "atmegy masik savba",
        "lane change", "lane_change", "changing lane",     // angol alap
        "merge", "merging", "drift", "weaving"             // szinonimák
    };

    // TURN — kanyarodás
    private static final String[] KW_TURN = {
        "kanyar", "balra kanyar", "jobbra kanyar",         // magyar
        "balra", "jobbra",
        "turn", "turning", "left turn", "right turn",      // angol alap
        "corner", "curve", "bend"                          // szinonimák
    };

    // CROSSING_VEHICLE — keresztbe menő jármű
    private static final String[] KW_CROSSING_VEHICLE = {
        "keresztbe", "keresztbe megy", "keresztezi",       // magyar
        "athalad keresztben",
        "crossing", "cross vehicle", "crossing vehicle",   // angol alap
        "vehicle crossing", "crosses the road",            // szinonimák
        "crossing traffic", "perpendicular motion",
        "lateral crossing"
    };

    // SUDDEN_STOP — hirtelen megállás / elakadás
    private static final String[] KW_SUDDEN_STOP = {
        "megall", "hirtelen megall", "elakad",             // magyar
        "allo jarmu", "blokkol",
        "sudden stop", "stop", "halt", "stuck",            // angol alap
        "blocking", "stationary object", "abrupt stop"     // szinonimák
    };

    // OVERTAKE — előzés / elhaladás
    private static final String[] KW_OVERTAKE = {
        "megeloz", "elhalad", "elozos", "kerules",         // magyar
        "overtake", "overtaking", "pass",                  // angol alap
        "passing vehicle", "pass-by", "pass by"            // szinonimák
    };

    // ANOMALY — szabálytalanság, veszélyes helyzet
    private static final String[] KW_ANOMALY = {
        "anomalia", "szabalytalan", "rendellenes",         // magyar
        "veszely", "veszedelmes", "veszhelyzet",
        "anomaly", "irregularity", "violation",            // angol alap
        "wrong way", "wrong-way", "unsafe behavior",       // szinonimák
        "illegal movement", "dangerous event",
        "sudden movement", "hirtelen mozgas",
        "abrupt motion", "gyors athaladasara",
        "sudden appearance", "dash", "sprint"
    };

    // ROAD_OBSTACLE — útakadály
    private static final String[] KW_ROAD_OBSTACLE = {
        "akadaly", "uton akadaly", "utakadaly",            // magyar
        "obstacle", "road obstacle",                       // angol alap
        "debris", "fallen object", "road hazard"           // szinonimák
    };

    // ONCOMING_TRUCK — szembejövő kamion / busz / nagy jármű (új intent)
    // Azon mintákra, amiket korábban WILDLIFE-ként téves szarvas-találatnak vett a
    // detektor: szembejövő fehér / piros / szürke kamion vagy busz, ami a vanishing
    // point közelében közeledik a kamerához.
    private static final String[] KW_ONCOMING_TRUCK = {
        "kamion szembe", "kamion szembejon", "szembejovo kamion",  // magyar alap
        "szembe kamion", "szembe jovo kamion",
        "kamion kozelit", "kozelito kamion",
        "feher kamion", "piros kamion", "szurke kamion",           // szín-variánsok (magyar)
        "feher kamion szembe", "piros kamion szembe", "szurke kamion szembe",
        "busz szembe", "szembejovo busz",
        "oncoming truck", "approaching truck", "truck oncoming",   // angol alap
        "truck approaching", "incoming truck",
        "white truck oncoming", "red truck oncoming", "grey truck oncoming",
        "white truck", "red truck", "grey truck", "gray truck",
        "oncoming bus", "approaching bus",
        "oncoming traffic truck", "head-on truck", "head on truck"
    };

    // -------------------------------------------------------------------------

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
        if (containsAny(normalizedQuery, KW_COLOR)) {
            return QueryIntent.COLOR;
        }
        if (containsAny(normalizedQuery, KW_ONCOMING_TRUCK)) {
            return QueryIntent.ONCOMING_TRUCK;
        }
        if (containsAny(normalizedQuery, KW_WILDLIFE)) {
            return QueryIntent.WILDLIFE;
        }
        if (containsAny(normalizedQuery, KW_LANE_CHANGE)) {
            return QueryIntent.LANE_CHANGE;
        }
        if (containsAny(normalizedQuery, KW_TURN)) {
            return QueryIntent.TURN;
        }
        if (containsAny(normalizedQuery, KW_CROSSING_VEHICLE)) {
            return QueryIntent.CROSSING_VEHICLE;
        }
        // SUDDEN_STOP és OVERTAKE jelenleg ANOMALY ill. MOTION csoportba esnek,
        // amíg dedikált intent nem kerül az enumba.
        if (containsAny(normalizedQuery, KW_SUDDEN_STOP)) {
            return QueryIntent.ROAD_OBSTACLE;
        }
        if (containsAny(normalizedQuery, KW_OVERTAKE)) {
            return QueryIntent.CROSSING_VEHICLE;
        }
        if (containsAny(normalizedQuery, KW_ANOMALY)) {
            return QueryIntent.ANOMALY;
        }
        if (containsAny(normalizedQuery, KW_ROAD_OBSTACLE)) {
            return QueryIntent.ROAD_OBSTACLE;
        }
        return QueryIntent.MOTION;
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
