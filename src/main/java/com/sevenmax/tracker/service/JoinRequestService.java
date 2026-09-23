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
    private final PlayerService playerService;

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
        String clubPlayerId = body.get("clubPlayerId");
        clubPlayerId = (clubPlayerId != null && !clubPlayerId.isBlank()) ? clubPlayerId.trim() : null;

        // A real member (already in the system from the ClubGG import, with real games under a
        // clubPlayerId) hitting /join to make their first deposit must land on THEIR existing
        // player, not a new empty one — even if they type their username with different casing,
        // or leave clubPlayerId blank. findExistingPlayer matches on either signal before we ever
        // consider creating a new row (that used to be a bare exact-case username check only).
        Player existing = findExistingPlayer(username, clubPlayerId);
        if (existing != null && userRepository.existsByPlayerId(existing.getId())) {
            throw new RuntimeException("Username already taken");
        }

        JoinRequest req = new JoinRequest();
        req.setUsername(username);
        req.setFullName(fullName.trim());
        req.setPhone(phone.trim());
        if (clubPlayerId != null) req.setClubPlayerId(clubPlayerId);
        req.setCreatedAt(LocalDateTime.now());
        req.setStatus("APPROVED");
        req.setReviewedAt(LocalDateTime.now());
        joinRequestRepository.save(req);

        User user = existing != null ? attachLogin(existing, req) : createPlayerAndUser(req);
        log.info("JoinRequest auto-approved — {} for '{}'",
                existing != null ? "login attached to existing player id=" + existing.getId() : "player and user created",
                username);

        String token = jwtUtil.generate(user);
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("role", user.getRole().name());
        result.put("username", user.getUsername());
        result.put("mustChangePassword", Boolean.TRUE.equals(user.getMustChangePassword()));
        result.put("playerId", user.getPlayer().getId());
        result.put("isAgent", Boolean.TRUE.equals(user.getPlayer().getIsAgent()));
        result.put("isWorker", Boolean.TRUE.equals(user.getPlayer().getIsWorker()));
        return result;
    }

    /** Same club player (dash-insensitive), or same username (case/spacing/punctuation-insensitive
     *  via PlayerService.findPlayerByUsername) — either signal means this join request belongs to
     *  a player who's already in the system, not a new one. */
    private Player findExistingPlayer(String username, String clubPlayerId) {
        if (clubPlayerId != null) {
            Player byClub = playerRepository.findByClubPlayerIdSafe(clubPlayerId).stream().findFirst().orElse(null);
            if (byClub != null) return byClub;
        }
        return playerService.findPlayerByUsername(username).orElse(null);
    }

    /** The join request matched an existing (imported, never-logged-in) player — attach a login to
     *  THEM instead of creating a duplicate Player row, backfilling whatever join-request details
     *  (phone/fullName/clubPlayerId) the existing record is still missing. Never overwrites a field
     *  the import already set. */
    private User attachLogin(Player existing, JoinRequest req) {
        if ((existing.getPhone() == null || existing.getPhone().isBlank()) && req.getPhone() != null) {
            existing.setPhone(req.getPhone());
        }
        if ((existing.getFullName() == null || existing.getFullName().isBlank()) && req.getFullName() != null) {
            existing.setFullName(req.getFullName());
        }
        if ((existing.getClubPlayerId() == null || existing.getClubPlayerId().isBlank()) && req.getClubPlayerId() != null) {
            existing.setClubPlayerId(req.getClubPlayerId());
        }
        Player player = playerRepository.save(existing);

        String rawPassword = req.getPhone().replaceAll("[^0-9]", "");
        if (rawPassword.isBlank()) rawPassword = "123456";

        User user = new User();
        user.setUsername(player.getUsername());
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setRole(User.Role.PLAYER);
        user.setPlayer(player);
        user.setMustChangePassword(true);
        user.setActive(true);
        return userRepository.save(user);
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
        Player existing = findExistingPlayer(req.getUsername(), req.getClubPlayerId());
        if (existing != null && userRepository.existsByPlayerId(existing.getId())) {
            throw new RuntimeException("Username already taken — cannot approve");
        }

        if (existing != null) attachLogin(existing, req); else createPlayerAndUser(req);

        req.setStatus("APPROVED");
        req.setReviewedAt(LocalDateTime.now());
        joinRequestRepository.save(req);
        log.info("JoinRequest {} approved — {} for '{}'", id,
                existing != null ? "login attached to existing player" : "player and user created", req.getUsername());
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
