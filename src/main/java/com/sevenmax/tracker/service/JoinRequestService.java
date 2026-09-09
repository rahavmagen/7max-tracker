package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.JoinRequest;
import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.JoinRequestRepository;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.UserRepository;
import com.sevenmax.tracker.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class JoinRequestService {

    private final JoinRequestRepository joinRequestRepository;
    private final PlayerRepository playerRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    /** Creates the player + login immediately (no admin approval wait) and returns a login-shaped
     *  response so the frontend can log the new user straight in, the same as POST /api/auth/login. */
    @Transactional
    public Map<String, Object> submit(Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank()) throw new RuntimeException("Username is required");
        String fullName = body.get("fullName");
        if (fullName == null || fullName.isBlank()) throw new RuntimeException("Full name is required");
        String phone = body.get("phone");
        if (phone == null || phone.isBlank()) throw new RuntimeException("Phone is required");

        username = username.trim();
        if (playerRepository.existsByUsername(username)) {
            throw new RuntimeException("Username already taken");
        }

        JoinRequest req = new JoinRequest();
        req.setUsername(username);
        req.setFullName(fullName.trim());
        req.setPhone(phone.trim());
        String clubPlayerId = body.get("clubPlayerId");
        if (clubPlayerId != null && !clubPlayerId.isBlank()) req.setClubPlayerId(clubPlayerId.trim());
        req.setCreatedAt(LocalDateTime.now());
        req.setStatus("APPROVED");
        req.setReviewedAt(LocalDateTime.now());
        joinRequestRepository.save(req);

        User user = createPlayerAndUser(req);
        log.info("JoinRequest auto-approved — player and user created immediately for '{}'", username);

        String token = jwtUtil.generate(user);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("role", user.getRole().name());
        result.put("username", user.getUsername());
        result.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        result.put("playerId", user.getPlayer().getId());
        result.put("isAgent", false);
        result.put("isWorker", false);
        return result;
    }

    public List<JoinRequest> getPending() {
        return joinRequestRepository.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    public List<JoinRequest> getHistory() {
        return joinRequestRepository.findByStatusInOrderByCreatedAtDesc(List.of("APPROVED", "REJECTED"));
    }

    @Transactional
    public void approve(Long id) {
        JoinRequest req = joinRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found: " + id));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("Request is not pending");
        }
        if (playerRepository.existsByUsername(req.getUsername())) {
            throw new RuntimeException("Username already taken — cannot approve");
        }

        createPlayerAndUser(req);

        req.setStatus("APPROVED");
        req.setReviewedAt(LocalDateTime.now());
        joinRequestRepository.save(req);
        log.info("JoinRequest {} approved — player and user created for '{}'", id, req.getUsername());
    }

    /** Creates the Player + login User for a join request. Password defaults to the phone
     *  number's digits (matching every other player account created this way in the app). */
    private User createPlayerAndUser(JoinRequest req) {
        Player player = new Player();
        player.setUsername(req.getUsername());
        player.setFullName(req.getFullName());
        player.setPhone(req.getPhone());
        player.setClubPlayerId(req.getClubPlayerId());
        player.setActive(true);
        player.setBalance(BigDecimal.ZERO);
        player.setCreditTotal(BigDecimal.ZERO);
        player.setCurrentChips(BigDecimal.ZERO);
        player.setDepositsTotal(BigDecimal.ZERO);
        player = playerRepository.save(player);

        String rawPassword = req.getPhone().replaceAll("[^0-9]", "");
        if (rawPassword.isBlank()) rawPassword = "123456";

        User user = new User();
        user.setUsername(req.getUsername());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(User.Role.PLAYER);
        user.setPlayer(player);
        user.setMustChangePassword(true);
        user.setActive(true);
        return userRepository.save(user);
    }

    @Transactional
    public void reject(Long id) {
        JoinRequest req = joinRequestRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Request not found: " + id));
        if (!"PENDING".equals(req.getStatus())) {
            throw new RuntimeException("Request is not pending");
        }
        req.setStatus("REJECTED");
        req.setReviewedAt(LocalDateTime.now());
        joinRequestRepository.save(req);
        log.info("JoinRequest {} rejected for username '{}'", id, req.getUsername());
    }
}
