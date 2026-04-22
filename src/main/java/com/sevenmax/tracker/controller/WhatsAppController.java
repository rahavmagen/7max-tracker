package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.WhatsAppService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/whatsapp")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WhatsAppController {

    private final WhatsAppService whatsAppService;

    @PostMapping("/send")
    public ResponseEntity<Map<String, Object>> send(@RequestBody SendRequest req) {
        if (req.phoneNumbers() == null || req.phoneNumbers().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No recipients"));
        }
        if (req.message() == null || req.message().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Message is empty"));
        }
        List<String> failed = whatsAppService.sendToAll(req.phoneNumbers(), req.message());
        int total = req.phoneNumbers().size();
        int success = total - failed.size();
        return ResponseEntity.ok(Map.of(
            "successCount", success,
            "failCount", failed.size(),
            "failedNumbers", failed
        ));
    }

    record SendRequest(List<String> phoneNumbers, String message) {}
}
