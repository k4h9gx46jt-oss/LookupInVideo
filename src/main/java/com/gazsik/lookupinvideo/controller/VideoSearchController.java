package com.gazsik.lookupinvideo.controller;

import com.gazsik.lookupinvideo.model.JobProgress;
import com.gazsik.lookupinvideo.model.SearchOutcome;
import com.gazsik.lookupinvideo.service.VideoSearchService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Controller
public class VideoSearchController {

    private static final List<String> VIDEO_EXTENSIONS =
            List.of(".mp4", ".avi", ".mov", ".mkv", ".wmv", ".flv", ".webm", ".m4v", ".mpeg", ".mpg");

    private final VideoSearchService videoSearchService;

    public VideoSearchController(VideoSearchService videoSearchService) {
        this.videoSearchService = videoSearchService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("query", "");
        model.addAttribute("videoDir", "");
        model.addAttribute("videoFiles", Collections.emptyList());
        return "index";
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(value = "dir", defaultValue = "") String dir,
                         @RequestParam(value = "query", defaultValue = "") String query,
                         Model model) {
        model.addAttribute("query", query);
        model.addAttribute("videoDir", dir);

        if (dir.isBlank()) {
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        Path dirPath = Paths.get(dir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dirPath)) {
            model.addAttribute("error", "A megadott konyvtar nem letezik: " + dir);
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        try (Stream<Path> stream = Files.list(dirPath)) {
            List<String> files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return VIDEO_EXTENSIONS.stream().anyMatch(name::endsWith);
                    })
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            model.addAttribute("videoFiles", files);
        } catch (IOException ex) {
            model.addAttribute("error", "Nem sikerult beolvasni a konyvtarat: " + ex.getMessage());
            model.addAttribute("videoFiles", Collections.emptyList());
        }

        return "index";
    }

    @PostMapping("/search")
    public String search(@RequestParam("video") MultipartFile video,
                         @RequestParam("query") String query,
                         Model model) {
        if (video == null || video.isEmpty()) {
            model.addAttribute("error", "Tolts fel egy videot a kereseshez.");
            model.addAttribute("query", query == null ? "" : query);
            model.addAttribute("videoDir", "");
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        if (query == null || query.isBlank()) {
            model.addAttribute("error", "Adj meg egy szoveges keresest (pl. piros auto vagy kutya).");
            model.addAttribute("query", "");
            model.addAttribute("videoDir", "");
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        try {
            SearchOutcome outcome = videoSearchService.storeAndSearch(video, query);
            model.addAttribute("outcome", outcome);
            return "results";
        } catch (Exception ex) {
            model.addAttribute("error", "Hiba tortent a feldolgozas kozben: " + ex.getMessage());
            model.addAttribute("query", query);
            model.addAttribute("videoDir", "");
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }
    }

    @PostMapping("/search-local")
    public String searchLocal(@RequestParam("videoDir") String videoDir,
                              @RequestParam("videoFile") String videoFile,
                              @RequestParam("query") String query,
                              Model model) {
        if (videoDir == null || videoDir.isBlank() || videoFile == null || videoFile.isBlank()) {
            model.addAttribute("error", "Valassz ki egy videofajlt a kereseshez.");
            model.addAttribute("query", query == null ? "" : query);
            model.addAttribute("videoDir", videoDir == null ? "" : videoDir);
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        if (query == null || query.isBlank()) {
            model.addAttribute("error", "Adj meg egy szoveges keresest (pl. piros auto vagy kutya).");
            model.addAttribute("query", "");
            model.addAttribute("videoDir", videoDir);
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        Path videoPath = Paths.get(videoDir).toAbsolutePath().normalize().resolve(videoFile);
        if (!Files.exists(videoPath) || !Files.isRegularFile(videoPath)) {
            model.addAttribute("error", "A fajl nem talalhato: " + videoPath);
            model.addAttribute("query", query);
            model.addAttribute("videoDir", videoDir);
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }

        try {
            SearchOutcome outcome = videoSearchService.searchByPath(videoPath, query);
            model.addAttribute("outcome", outcome);
            return "results";
        } catch (Exception ex) {
            model.addAttribute("error", "Hiba tortent a feldolgozas kozben: " + ex.getMessage());
            model.addAttribute("query", query);
            model.addAttribute("videoDir", videoDir);
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }
    }

    @PostMapping("/search-dir")
    @ResponseBody
    public Map<String, String> searchDir(@RequestParam("videoDir") String videoDir,
                                         @RequestParam("query") String query) {
        if (videoDir == null || videoDir.isBlank()) {
            return Map.of("error", "Add meg a konyvtar eleresi utjat.");
        }
        if (query == null || query.isBlank()) {
            return Map.of("error", "Add meg a keresesi szoveget.");
        }
        Path dirPath = Paths.get(videoDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dirPath)) {
            return Map.of("error", "A konyvtar nem letezik: " + videoDir);
        }
        try {
            String jobId = videoSearchService.startDirectorySearch(dirPath, query);
            return Map.of("jobId", jobId);
        } catch (IllegalArgumentException ex) {
            return Map.of("error", ex.getMessage());
        } catch (IOException ex) {
            return Map.of("error", "Hiba a konyvtar olvasasakor: " + ex.getMessage());
        }
    }

    @GetMapping("/progress/{jobId}")
    @ResponseBody
    public ResponseEntity<?> getProgress(@PathVariable String jobId) {
        JobProgress progress = videoSearchService.getProgress(jobId);
        if (progress == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(progress);
    }

    @GetMapping("/results/{jobId}")
    public String getDirResults(@PathVariable String jobId, Model model) {
        List<SearchOutcome> outcomes = videoSearchService.getDirectoryResults(jobId);
        if (outcomes == null) {
            model.addAttribute("error", "Az eredmenyek nem elerhetok (meg folyamatban lehet).");
            model.addAttribute("query", "");
            model.addAttribute("videoDir", "");
            model.addAttribute("videoFiles", Collections.emptyList());
            return "index";
        }
        JobProgress progress = videoSearchService.getProgress(jobId);
        int totalScanned = progress != null ? progress.getTotal() : outcomes.size();
        int totalMatches = 0;
        for (SearchOutcome o : outcomes) totalMatches += o.getMatches().size();
        model.addAttribute("outcomes", outcomes);
        model.addAttribute("totalScanned", totalScanned);
        model.addAttribute("totalMatches", totalMatches);
        String q = outcomes.isEmpty() ? "" : outcomes.get(0).getQuery();
        model.addAttribute("query", q);
        return "results-dir";
    }

    @GetMapping("/media/{videoId}")
    public ResponseEntity<Resource> stream(@PathVariable String videoId) {
        Path path = videoSearchService.resolveVideoPath(videoId);
        if (path == null || !Files.exists(path)) {
            return ResponseEntity.notFound().build();
        }

        String contentType;
        try {
            contentType = Files.probeContentType(path);
        } catch (Exception ex) {
            contentType = null;
        }
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        Resource resource = new FileSystemResource(path.toFile());

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + path.getFileName() + "\"")
                .body(resource);
    }
}
