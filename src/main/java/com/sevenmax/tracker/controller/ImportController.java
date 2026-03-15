package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/import")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ImportController {

    private final ImportService importService;

    @PostMapping("/players")
    public ResponseEntity<Map<String, Object>> importPlayers(
            @RequestParam("max7") MultipartFile max7File,
            @RequestParam(value = "clearExisting", defaultValue = "false") boolean clearExisting) {
        try {
            Map<String, Object> result = importService.importFromFiles(max7File, clearExisting);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
