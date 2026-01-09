package com.jingyicare.jingyi_icis_engine.repository.reports;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.reports.WardReport;

@Repository
public interface WardReportRepository extends JpaRepository<WardReport, Long> {
    Optional<WardReport> findByDeptIdAndShiftStartTimeAndIsDeletedFalse(String deptId, LocalDateTime shiftStartTime);
}
