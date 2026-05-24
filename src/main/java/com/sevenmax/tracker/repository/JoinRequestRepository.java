package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.JoinRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface JoinRequestRepository extends JpaRepository<JoinRequest, Long> {
    List<JoinRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<JoinRequest> findByStatusInOrderByCreatedAtDesc(List<String> statuses);
    boolean existsByUsernameAndStatus(String username, String status);
}
