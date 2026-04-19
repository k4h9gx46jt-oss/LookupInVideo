package com.gazsik.lookupinvideo.tools;

import com.gazsik.lookupinvideo.model.SceneMatch;
import com.gazsik.lookupinvideo.model.SearchOutcome;
import com.gazsik.lookupinvideo.service.VideoSearchService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small CLI helper to benchmark/tune the search algorithm on known sample clips
 * without using JShell.
 */
public final class VideoSearchValidator {

    private static final List<String> DEFAULT_FILES = List.of(
            "/Users/SEV0A/java/LookupInVideo/liveformowncam/NO20260415-163405-000388.mp4",
            "/Users/SEV0A/java/LookupInVideo/liveformowncam/NO20260415-160202-000356.mp4",
            "/Users/SEV0A/java/LookupInVideo/liveformowncam/NO20260415-160302-000357.mp4",
            "/Users/SEV0A/java/LookupInVideo/liveformowncam/NO20260415-160803-000362.mp4",
            "/Users/SEV0A/java/LookupInVideo/demoVideo/Demo1.mp4"
    );

    private VideoSearchValidator() {
    }

    public static void main(String[] args) throws Exception {
        String query = args.length > 0 ? args[0] : "szarvas";

        List<String> paths;
        if (args.length > 1) {
            paths = Arrays.asList(args).subList(1, args.length);
        } else {
            paths = DEFAULT_FILES;
        }

        List<Path> existing = new ArrayList<>();
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.exists(path)) {
                existing.add(path);
            }
        }

        if (existing.isEmpty()) {
            System.out.println("No existing input files.");
            return;
        }

        VideoSearchService service = new VideoSearchService("uploads", 0, 900L, true, false, 0);
        try {
            System.out.println("| file | matches | top_reason | elapsed_ms |");
            System.out.println("|---|---:|---|---:|");
            for (Path path : existing) {
                long t0 = System.nanoTime();
                SearchOutcome out = service.searchByPath(path, query);
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;

                List<SceneMatch> matches = out.getMatches();
                int count = matches == null ? 0 : matches.size();
                String top = "-";
                if (count > 0) {
                    String reason = matches.get(0).getReason();
                    top = (reason == null || reason.isBlank()) ? "-" : reason.replace("|", "/");
                }

                System.out.printf("| %s | %d | %s | %d |%n",
                        path.getFileName(), count, top, elapsedMs);
            }
        } finally {
            service.shutdownExecutors();
        }
    }
}
