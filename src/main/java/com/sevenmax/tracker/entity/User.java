package com.sevenmax.tracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @ManyToOne
    @JoinColumn(name = "player_id")
    private Player player; // null for managers

    // for future sub-manager pyramid
    @ManyToOne
    @JoinColumn(name = "manager_id")
    private User manager;

    private Boolean active = true;

    private Boolean mustChangePassword = true;

    @Column(updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Role {
        ADMIN, MANAGER, SUB_MANAGER, PLAYER
    }
}
