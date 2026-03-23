package com.sevenmax.tracker.config;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.User;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DataInitializer implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PlayerRepository playerRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Create admin user if none exists
        if (!userRepository.existsByUsername("admin")) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin123"));
            admin.setRole(User.Role.ADMIN);
            admin.setMustChangePassword(false);
            admin.setActive(true);
            userRepository.save(admin);
            System.out.println("[Auth] Created admin user (password: admin123)");
        }

        // Create User records for all players that don't have one yet
        List<Player> players = playerRepository.findAll();
        int created = 0;
        for (Player player : players) {
            String username = player.getUsername();
            if (username == null || username.isBlank()) continue;
            if (userRepository.existsByUsername(username)) continue;

            // Default password: phone digits only, or "123456" if no phone
            String rawPassword = (player.getPhone() != null && !player.getPhone().isBlank())
                    ? player.getPhone().replaceAll("[^0-9]", "")
                    : "123456";
            if (rawPassword.isBlank()) rawPassword = "123456";

            User u = new User();
            u.setUsername(username);
            u.setPasswordHash(passwordEncoder.encode(rawPassword));
            u.setRole(User.Role.PLAYER);
            u.setPlayer(player);
            u.setMustChangePassword(true);
            u.setActive(true);
            userRepository.save(u);
            created++;
        }

        if (created > 0) {
            System.out.println("[Auth] Created " + created + " player user accounts");
        }
    }
}
