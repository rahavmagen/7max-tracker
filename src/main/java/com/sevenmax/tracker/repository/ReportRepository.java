package com.sevenmax.tracker.repository;

import com.sevenmax.tracker.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportRepository extends JpaRepository<Report, Long> {
    List<Report> findAllByOrderByUploadedAtDesc();
}
