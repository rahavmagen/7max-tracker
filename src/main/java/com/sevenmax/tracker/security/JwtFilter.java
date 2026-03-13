package com.sevenmax.tracker.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtUtil.parse(header.substring(7));
                String username = claims.getSubject();
                String role = claims.get("role", String.class);
                Object playerIdObj = claims.get("playerId");
                Long playerId = playerIdObj != null ? ((Number) playerIdObj).longValue() : null;

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var auth = new UsernamePasswordAuthenticationToken(username, null, authorities);

                Map<String, Object> details = new HashMap<>();
                details.put("role", role);
                if (playerId != null) details.put("playerId", playerId);
                auth.setDetails(details);

                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {}
        }
        chain.doFilter(request, response);
    }
}
