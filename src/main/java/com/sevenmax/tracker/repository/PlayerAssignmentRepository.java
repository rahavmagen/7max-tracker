package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.PlayerAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerAssignmentRepository extends JpaRepository<PlayerAssignment, Long> {
}
