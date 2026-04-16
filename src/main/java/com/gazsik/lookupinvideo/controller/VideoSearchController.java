package com.gazsik.lookupinvideo.controller;

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
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@Controller
public class VideoSearchController {

    private final VideoSearchService videoSearchService;

    public VideoSearchController(VideoSearchService videoSearchService) {
        this.videoSearchService = videoSearchService;
    }

    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("query", "");
        return "index";
    }

    @PostMapping("/search")
    public String search(@RequestParam("video") MultipartFile video,
                         @RequestParam("query") String query,
                         Model model) {
        if (video == null || video.isEmpty()) {
            model.addAttribute("error", "Tolts fel egy videot a kereseshez.");
            model.addAttribute("query", query == null ? "" : query);
            return "index";
        }

        if (query == null || query.isBlank()) {
            model.addAttribute("error", "Adj meg egy szoveges keresest (pl. piros auto vagy kutya). ");
            model.addAttribute("query", "");
            return "index";
        }

        try {
            SearchOutcome outcome = videoSearchService.storeAndSearch(video, query);
            model.addAttribute("outcome", outcome);
            return "results";
        } catch (Exception ex) {
            model.addAttribute("error", "Hiba tortent a feldolgozas kozben: " + ex.getMessage());
            model.addAttribute("query", query);
            return "index";
        }
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
