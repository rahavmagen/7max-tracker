package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final PasswordEncoder passwordEncoder;

    public Map<String, Object> login(String username, String password) {
        User user = userRepository.findByUsernameIgnoreCase(username)
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!Boolean.TRUE.equals(user.getActive())) {
            throw new RuntimeException("Account is disabled");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new RuntimeException("Invalid credentials");
        }

        user.setLoginCount((user.getLoginCount() == null ? 0 : user.getLoginCount()) + 1);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);

        String token = jwtUtil.generate(user);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("role", user.getRole().name());
        result.put("username", user.getUsername());
        result.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        if (user.getPlayer() != null) {
            result.put("playerId", user.getPlayer().getId());
        }
        return result;
    }

    public void changePassword(String username, String oldPassword, String newPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("Current password is incorrect");
        }

        if (newPassword == null || newPassword.length() < 4) {
            throw new RuntimeException("New password must be at least 4 characters");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    public void changeRole(String targetUsername, String newRole) {
        User user = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new RuntimeException("User not found: " + targetUsername));
        user.setRole(User.Role.valueOf(newRole));
        userRepository.save(user);
    }

    public void adminResetPassword(String targetUsername, String newPassword) {
        if (newPassword == null || newPassword.length() < 4) {
            throw new RuntimeException("Password must be at least 4 characters");
        }
        User user = userRepository.findByUsername(targetUsername)
                .orElseThrow(() -> new RuntimeException("User not found: " + targetUsername));
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        userRepository.save(user);
    }
}
