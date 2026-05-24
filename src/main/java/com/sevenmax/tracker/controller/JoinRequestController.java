package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.JoinRequest;
import com.sevenmax.tracker.service.JoinRequestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/join")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JoinRequestController {

    private final JoinRequestService joinRequestService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, String> body) {
        try {
            joinRequestService.submit(body);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/pending")
    public ResponseEntity<List<JoinRequest>> pending() {
        return ResponseEntity.ok(joinRequestService.getPending());
    }

    @GetMapping("/history")
    public ResponseEntity<List<JoinRequest>> history() {
        return ResponseEntity.ok(joinRequestService.getHistory());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable Long id) {
        try {
            joinRequestService.approve(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable Long id) {
        try {
            joinRequestService.reject(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
