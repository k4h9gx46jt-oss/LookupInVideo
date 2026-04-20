package com.gazsik.lookupinvideo.infrastructure.processing;

import com.gazsik.lookupinvideo.domain.enums.EventType;
import com.gazsik.lookupinvideo.domain.enums.QueryIntent;
import org.springframework.stereotype.Component;

@Component
public class EventScoringService {

    public boolean isMatch(QueryIntent intent, double score) {
        return switch (intent) {
            case COLOR -> score >= 0.14;
            case WILDLIFE -> score >= 0.21;
            // TURN: horizDom * motionGate alapu jel, kanyarnal ~0.5-0.9, egyenesen ~0
            case TURN -> score >= 0.40;
            default -> score >= 0.18;
        };
    }

    public String modeLabel(QueryIntent intent) {
        return switch (intent) {
            case COLOR -> "Szinalapu kereses (piros / zold / kek)";
            case WILDLIFE -> "Vadatkeles keresese keresztmozgas alapjan";
            case TURN -> "Kanyar-esemeny keresese";
            case LANE_CHANGE -> "Savvaltas keresese";
            case CROSSING_VEHICLE -> "Keresztbe meno jarmu keresese";
            case ANOMALY -> "Anomalia/szabalytalansag keresese";
            case ROAD_OBSTACLE -> "Utakadaly keresese";
            case MOTION -> "Demo: mozgasalapu jelenet-kereses";
        };
    }

    public String modeNote(QueryIntent intent) {
        return switch (intent) {
            case COLOR -> "A piros/red, zold/green es kek/blue kulcsszavak szinalapu keresest hasznalnak, mozgas-boosttal a hirtelen kepen athuzo targyakhoz.";
            case WILDLIFE -> "A szarvas/deer/vad kulcsszavaknal a keresztiranyu mozgas dominans, az oncoming vehicle jellegu mintakat szurjuk.";
            case TURN -> "A kanyar intent jelenleg a mozgasalapu baseline-ra fallbackel, a dedikalt esemeny-detektor kesobb jon.";
            case LANE_CHANGE -> "A savvaltas intent jelenleg a mozgasalapu baseline-ra fallbackel, a geometriara epulo lane-modell kesobb jon.";
            case CROSSING_VEHICLE -> "A keresztbe meno jarmu intent jelenleg a mozgasalapu baseline-ra fallbackel, dedikalt tracker alapu esemenylogika kesobb jon.";
            case ANOMALY -> "Az anomalia intent jelenleg a mozgasalapu baseline-ra fallbackel; candidate + verifier ketlepcsos logika kesobb jon.";
            case ROAD_OBSTACLE -> "Az utakadaly intent jelenleg a mozgasalapu baseline-ra fallbackel; objektumdetektor integracio kesobb jon.";
            case MOTION -> "A megadott szoveget fogadjuk, de objektumfelismeres helyett jelenleg mozgas-intenzitas alapjan rangsorolunk.";
        };
    }

    public EventType mapEventType(QueryIntent intent) {
        return switch (intent) {
            case COLOR -> EventType.COLOR_DOMINANCE;
            case WILDLIFE -> EventType.WILDLIFE_CROSSING;
            case TURN -> EventType.TURN;
            case LANE_CHANGE -> EventType.LANE_CHANGE;
            case CROSSING_VEHICLE -> EventType.CROSSING_VEHICLE;
            case ANOMALY -> EventType.ANOMALY;
            case ROAD_OBSTACLE -> EventType.ROAD_OBSTACLE;
            case MOTION -> EventType.GENERIC_MOTION;
        };
    }

    public boolean usesWildlifePath(QueryIntent intent) {
        return intent == QueryIntent.WILDLIFE;
    }
}
