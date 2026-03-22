package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.entity.LessonEvent;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.service.LessonService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/lesson")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LessonController {

    private final LessonService lessonService;
    private final PlayerRepository playerRepository;

    @GetMapping("/event")
    public ResponseEntity<?> getEvent(Authentication auth) {
        var event = lessonService.getUpcomingEvent();
        if (event.isEmpty()) return ResponseEntity.ok(Map.of());
        LessonEvent e = event.get();
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", e.getId());
        dto.put("eventDate", e.getEventDate().toString());
        dto.put("title", e.getTitle() != null ? e.getTitle() : "");
        if (auth != null) {
            dto.put("isRegistered", lessonService.isRegistered(e.getId(), auth.getName()));
        }
        return ResponseEntity.ok(dto);
    }

    @PostMapping("/event")
    public ResponseEntity<?> setEvent(@RequestBody Map<String, Object> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        try {
            LocalDate date = LocalDate.parse(body.get("eventDate").toString());
            String title = body.containsKey("title") ? (String) body.get("title") : null;
            LessonEvent event = lessonService.setEvent(date, title);
            return ResponseEntity.ok(Map.of("id", event.getId(), "eventDate", event.getEventDate().toString()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/registrations")
    public ResponseEntity<?> getRegistrations(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).build();
        var event = lessonService.getUpcomingEvent();
        if (event.isEmpty()) return ResponseEntity.ok(List.of());
        return ResponseEntity.ok(
            lessonService.getRegistrations(event.get().getId()).stream()
                .map(r -> Map.of(
                    "id", r.getId(),
                    "username", r.getUsername(),
                    "fullName", r.getFullName() != null ? r.getFullName() : "",
                    "phone", r.getPhone() != null ? r.getPhone() : "",
                    "registeredAt", r.getRegisteredAt().toString()
                ))
                .collect(Collectors.toList())
        );
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, Object> body, Authentication auth) {
        try {
            var event = lessonService.getUpcomingEvent();
            if (event.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "No upcoming event"));
            String fullName = (String) body.get("fullName");
            String phone = (String) body.get("phone");
            lessonService.register(event.get().getId(), auth.getName(), fullName, phone);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/register")
    public ResponseEntity<?> unregister(Authentication auth) {
        var event = lessonService.getUpcomingEvent();
        if (event.isEmpty()) return ResponseEntity.ok(Map.of("success", true));
        lessonService.unregister(event.get().getId(), auth.getName());
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/my-player")
    public ResponseEntity<?> getMyPlayer(Authentication auth) {
        return playerRepository.findByUsername(auth.getName())
            .map(p -> ResponseEntity.ok(Map.<String, Object>of(
                "fullName", p.getFullName() != null ? p.getFullName() : "",
                "phone", p.getPhone() != null ? p.getPhone() : ""
            )))
            .orElse(ResponseEntity.ok(Map.of("fullName", "", "phone", "")));
    }

    private boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"));
    }
}
