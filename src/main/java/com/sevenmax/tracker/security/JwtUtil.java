package com.sevenmax.tracker.security;

import com.sevenmax.tracker.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class JwtUtil {

    private static final String SECRET = "7max-poker-tracker-secret-key-for-jwt-2026!!";

    private SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    public String generate(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole().name());
        if (user.getPlayer() != null) {
            claims.put("playerId", user.getPlayer().getId());
        }
        return Jwts.builder()
                .subject(user.getUsername())
                .claims(claims)
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
