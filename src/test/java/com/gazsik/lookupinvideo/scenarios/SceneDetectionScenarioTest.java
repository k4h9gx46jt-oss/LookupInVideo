package com.gazsik.lookupinvideo.scenarios;

import com.gazsik.lookupinvideo.model.SceneMatch;
import com.gazsik.lookupinvideo.model.SearchOutcome;
import com.gazsik.lookupinvideo.service.VideoSearchService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression scenarios for the wildlife ("szarvas") detector.
 *
 * <p>Two flavours of asserts live here:
 * <ul>
 *   <li><b>POSITIVE</b> cases — clips known to contain a real deer crossing. The detector must
 *       emit at least one match within the labelled time window. Regressions that drop the
 *       known-good deer get caught immediately.</li>
 *   <li><b>NEGATIVE</b> cases — clips where the dominant event is an oncoming truck/bus or an
 *       overtaking manoeuvre. These must NOT produce a deer match anywhere near the labelled
 *       event window. Regressions that re-introduce the truck-as-deer false-positive class
 *       get caught immediately.</li>
 * </ul>
 *
 * <p>Each test uses {@link Assumptions#assumeTrue(boolean)} on the underlying mp4 path, so the
 * test is silently skipped on machines where the operator-supplied dashcam corpus is not
 * checked out (CI agents, fresh clones, etc.). The full table of cases lives in
 * {@code doc/keresesmodszertan.md} so the scenarios can be re-curated without touching code.
 */
@SpringBootTest(properties = {
        // Match production analysis configuration exactly so EMA / warmup / segmentation state
        // at sample times is the same as in the running app — otherwise borderline hits
        // (e.g. Demo1 deer at ~21.9% confidence with the wildlife gate at 21%) flip across
        // the gate depending on where intra-video segment boundaries land.
        "lookup.video.analysis.max-threads=10",
        "lookup.video.analysis.intra-segment-count=7",
        "lookup.video.analysis.decode-threads=7",
        "lookup.video.analysis.timeout-seconds=300",
        "lookup.video.analysis.profile-stages=false"
})
class SceneDetectionScenarioTest {

    private static final String QUERY = "szarvas";
    /**
     * Query string used for {@code positive_oncomingTruck_*} cases. Hits the
     * {@code ONCOMING_TRUCK} intent in {@code QueryInterpretationService} (see
     * {@code KW_ONCOMING_TRUCK} for the keyword set: "kamion szembe", "oncoming truck",
     * "white/red/grey truck", "feher/piros/szurke kamion", etc.).
     */
    private static final String TRUCK_QUERY = "oncoming truck";
    private static final Path LIVE_DIR = Paths.get("liveformowncam");
    private static final Path DEMO_DIR = Paths.get("demoVideo");

    @Autowired
    private VideoSearchService videoSearchService;

    // ---------------------------------------------------------------------
    // POSITIVE cases — must produce a deer match in the expected window.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("Demo1.mp4: deer crossing 24-29s must be detected")
    void positive_demo1_deerCrossing() {
        Path video = DEMO_DIR.resolve("Demo1.mp4");
        assumeFile(video);
        SearchOutcome outcome = videoSearchService.searchByPath(video, QUERY);
        assertHasMatchInWindow(outcome, 22.0, 30.0,
                "Demo1.mp4 should keep the canonical deer-crossing hit between 22-30s");
    }

    @Test
    @DisplayName("YTDown deer-hit clip: deer crossing 23-27s (left-to-right) must be detected")
    void positive_ytDownDeerHit_leftToRightCrossing() {
        Path video = DEMO_DIR.resolve(
                "YTDown.com_YouTube_Deer-Hit-SMACK-at-60-MPH-II-Dash-Cam-WAR_Media_ARAbzOi-Z8Q_001_1080p.mp4");
        assumeFile(video);
        SearchOutcome outcome = videoSearchService.searchByPath(video, QUERY);
        assertHasMatchInWindow(outcome, 21.0, 29.0,
                "YTDown deer-hit clip should detect the left-to-right deer between 21-29s");
    }

    // ---------------------------------------------------------------------
    // NEGATIVE cases — oncoming truck / overtake must NOT register as deer.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("NO20260415-153500-000329.mp4: oncoming white truck — no deer hit")
    void negative_oncomingTruck_153500() {
        assertNoDeerInLiveClip("NO20260415-153500-000329.mp4", 0.0, 60.0);
    }

    @Test
    @DisplayName("NO20260415-153800-000332.MP4: overtake manoeuvre 43-55s — no deer hit")
    void negative_overtake_153800() {
        assertNoDeerInLiveClip("NO20260415-153800-000332.MP4", 25.0, 60.0);
    }

    @Test
    @DisplayName("NO20260415-154200-000336.mp4: oncoming white truck @ 49s — no deer hit")
    void negative_oncomingTruck_154200() {
        assertNoDeerInLiveClip("NO20260415-154200-000336.mp4", 40.0, 55.0);
    }

    @Test
    @DisplayName("NO20260415-154401-000338.mp4: oncoming white-cab + red-trailer truck @ 6-8s — no deer hit")
    void negative_oncomingTruck_154401() {
        assertNoDeerInLiveClip("NO20260415-154401-000338.mp4", 0.0, 12.0);
    }

    @Test
    @DisplayName("NO20260415-154801-000342.mp4: oncoming grey truck @ 10s — no deer hit")
    void negative_oncomingTruck_154801() {
        assertNoDeerInLiveClip("NO20260415-154801-000342.mp4", 5.0, 15.0);
    }

    @Test
    @DisplayName("NO20260415-155602-000350.mp4: oncoming white truck @ 49s — no deer hit")
    void negative_oncomingTruck_155602() {
        assertNoDeerInLiveClip("NO20260415-155602-000350.mp4", 40.0, 55.0);
    }

    @Test
    @DisplayName("NO20260415-160503-000359.mp4: oncoming red truck @ 44-45s — no deer hit")
    void negative_oncomingTruck_160503() {
        assertNoDeerInLiveClip("NO20260415-160503-000359.mp4", 30.0, 50.0);
    }

    // ---------------------------------------------------------------------
    // POSITIVE oncoming-truck cases — same clips as above, but the dedicated
    // ONCOMING_TRUCK intent should pick up the truck in the labelled window.
    // Each test is the positive complement of the corresponding `negative_*`
    // deer assert; they share the video and the time window.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("NO20260415-153500-000329.mp4: oncoming white truck — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_153500() {
        assertOncomingTruckInLiveClip("NO20260415-153500-000329.mp4", 0.0, 60.0);
    }

    @Test
    @DisplayName("NO20260415-153800-000332.MP4: overtake / oncoming traffic 25-60s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_153800() {
        assertOncomingTruckInLiveClip("NO20260415-153800-000332.MP4", 25.0, 60.0);
    }

    @Test
    @DisplayName("NO20260415-154200-000336.mp4: oncoming white truck @ 49s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_154200() {
        assertOncomingTruckInLiveClip("NO20260415-154200-000336.mp4", 40.0, 55.0);
    }

    @Test
    @DisplayName("NO20260415-154401-000338.mp4: oncoming white-cab + red-trailer truck @ 6-8s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_154401() {
        assertOncomingTruckInLiveClip("NO20260415-154401-000338.mp4", 0.0, 12.0);
    }

    @Test
    @DisplayName("NO20260415-154801-000342.mp4: oncoming grey truck @ 10s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_154801() {
        assertOncomingTruckInLiveClip("NO20260415-154801-000342.mp4", 5.0, 30.0);
    }

    @Test
    @DisplayName("NO20260415-155602-000350.mp4: oncoming white truck @ 49s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_155602() {
        assertOncomingTruckInLiveClip("NO20260415-155602-000350.mp4", 40.0, 55.0);
    }

    @Test
    @DisplayName("NO20260415-160503-000359.mp4: oncoming red truck @ 44-45s — ONCOMING_TRUCK hit expected")
    void positive_oncomingTruck_160503() {
        assertOncomingTruckInLiveClip("NO20260415-160503-000359.mp4", 30.0, 50.0);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private void assertNoDeerInLiveClip(String fileName, double windowStart, double windowEnd) {
        Path video = LIVE_DIR.resolve(fileName);
        assumeFile(video);
        SearchOutcome outcome = videoSearchService.searchByPath(video, QUERY);
        assertNoMatchInWindow(outcome, windowStart, windowEnd, fileName
                + ": oncoming-truck / overtake event must not be classified as deer");
    }

    private void assertOncomingTruckInLiveClip(String fileName, double windowStart, double windowEnd) {
        Path video = LIVE_DIR.resolve(fileName);
        assumeFile(video);
        SearchOutcome outcome = videoSearchService.searchByPath(video, TRUCK_QUERY);
        assertHasMatchInWindow(outcome, windowStart, windowEnd, fileName
                + ": oncoming-truck event must be detected by the ONCOMING_TRUCK intent");
    }

    private static void assumeFile(Path path) {
        Assumptions.assumeTrue(Files.exists(path),
                "skipping scenario — sample video missing: " + path.toAbsolutePath());
    }

    private static void assertHasMatchInWindow(SearchOutcome outcome,
                                               double startSeconds,
                                               double endSeconds,
                                               String message) {
        List<SceneMatch> hits = outcome.getMatches().stream()
                .filter(m -> m.getTimestampSeconds() >= startSeconds && m.getTimestampSeconds() <= endSeconds)
                .collect(Collectors.toList());
        assertFalse(hits.isEmpty(),
                () -> message + " — got " + summarizeMatches(outcome.getMatches())
                        + " | mode=" + outcome.getNote());
    }

    private static void assertNoMatchInWindow(SearchOutcome outcome,
                                              double startSeconds,
                                              double endSeconds,
                                              String message) {
        List<SceneMatch> hits = outcome.getMatches().stream()
                .filter(m -> m.getTimestampSeconds() >= startSeconds && m.getTimestampSeconds() <= endSeconds)
                .collect(Collectors.toList());
        assertTrue(hits.isEmpty(),
                () -> message + " — unexpected match(es): " + summarizeMatches(hits)
                        + " | mode=" + outcome.getNote());
    }

    private static String summarizeMatches(List<SceneMatch> matches) {
        if (matches.isEmpty()) {
            return "(none)";
        }
        return matches.stream()
                .map(m -> String.format(Locale.ROOT, "[t=%.1fs conf=%.2f reason='%s']",
                        m.getTimestampSeconds(), m.getConfidence(), m.getReason()))
                .collect(Collectors.joining(", "));
    }
}
