package com.sevenmax.tracker.controller;

import com.sevenmax.tracker.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(authService.login(body.get("username"), body.get("password")));
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        try {
            authService.changePassword(auth.getName(), body.get("oldPassword"), body.get("newPassword"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/admin/reset-password")
    public ResponseEntity<Map<String, Object>> adminResetPassword(
            @RequestBody Map<String, String> body,
            Authentication auth) {
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MANAGER"))) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }
        try {
            authService.adminResetPassword(body.get("username"), body.get("newPassword"));
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(Authentication auth) {
        if (auth == null) return ResponseEntity.status(401).build();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) auth.getDetails();
        Map<String, Object> result = new java.util.HashMap<>(details);
        result.put("username", auth.getName());
        return ResponseEntity.ok(result);
    }
}
