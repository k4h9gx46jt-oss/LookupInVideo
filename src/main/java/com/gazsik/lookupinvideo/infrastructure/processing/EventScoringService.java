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
            // TURN: lateralSweepScore * motionGate (0=szimmetrikus/egyenes, 1=teljesen aszimmetrikus/kanyar)
            case TURN -> score >= 0.30;
            // LANE_CHANGE: koherens horizontalis kameraeltolas, alacsony residual + nem teljes kanyar
            case LANE_CHANGE -> score >= 0.28;
            // CROSSING_VEHICLE: keresztiranyu mozgas + centroid attranszlal a kepen
            case CROSSING_VEHICLE -> score >= 0.30;
            // ROAD_OBSTACLE: ego-megallas / akadaly elotti elgyengulo mozgas
            case ROAD_OBSTACLE -> score >= 0.32;
            // ANOMALY: burst-szeru, hirtelen-mozgasos esemeny
            case ANOMALY -> score >= 0.34;
            // ONCOMING_TRUCK: szembejovo nagy jarmu (kamion / busz) - kozeli silhouette
            // a vanishing point korul, modell silhouette-grow + horizon-vehicle profil
            case ONCOMING_TRUCK -> score >= 0.28;
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
            case ONCOMING_TRUCK -> "Szembejovo kamion / busz keresese";
            case MOTION -> "Demo: mozgasalapu jelenet-kereses";
        };
    }

    public String modeNote(QueryIntent intent) {
        return switch (intent) {
            case COLOR -> "A piros/red, zold/green es kek/blue kulcsszavak szinalapu keresest hasznalnak, mozgas-boosttal a hirtelen kepen athuzo targyakhoz.";
            case WILDLIFE -> "A szarvas/deer/vad kulcsszavaknal a keresztiranyu mozgas dominans, az oncoming vehicle jellegu mintakat szurjuk.";
            case TURN -> "Ket fuggetlen jel kombinacioja: (1) kepszel-aszimmetria (lateralSweep) — nagy kanyarnal is mukodik, nem telitodik; (2) horizontalis shift + koherencia — kis/kozepes kanyarhoz. A ket jel maximuma * mozgaskapu adja a vegs\u0151 pontszamot.";
            case LANE_CHANGE -> "Savvaltas: az aláírt globalShiftX EMA koherenciaja (~2 s ablakon) jelzi az ego ater\u00e9st, miközben a kanyar-szimmetria es a keresztmozgas alacsony marad. Score = coherence * motionGate * (1 - sweep) * (1 - crossMotionRatio).";
            case CROSSING_VEHICLE -> "Keresztbe meno jarmu: crossMotionRatio * travelScore * residualGate * crossTravelGate, plusz centroid-elmozdulas legalabb ~0.10 kepszelesseg ~1.2 s alatt — szarvas-szuro nelkul, igy autoszinek is mehetnek.";
            case ANOMALY -> "Anomalia: burstScore (residualIntensity-EMA elteres) gate-elve max(crossMotionRatio, lateralSweepScore)-szal — minimalis residualIntensity korlattal a zaj ellen.";
            case ROAD_OBSTACLE -> "Utakadaly / megallas: hosszu intensity-EMA es shiftMag-EMA letorese (drop ratio) + alacsony abszolut intensity. Score = stopDrop * stopGate.";
            case ONCOMING_TRUCK -> "Szembejovo kamion / busz: a residualMaszk centroid a kep felso felehez kozel (kozeli vanishing point), magas lateralTrack DE alacsony tenyleges crossTravel (silhouette in-place novekedése), neutral vagy szines jarmu-jel.";
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
            case ONCOMING_TRUCK -> EventType.CROSSING_VEHICLE;
            case MOTION -> EventType.GENERIC_MOTION;
        };
    }

    public boolean usesWildlifePath(QueryIntent intent) {
        return intent == QueryIntent.WILDLIFE;
    }
}
