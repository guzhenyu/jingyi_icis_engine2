package com.jingyicare.jingyi_icis_engine.repository.reports;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.reports.PatientNursingReport;

@Repository
public interface PatientNursingReportRepository extends JpaRepository<PatientNursingReport, Long> {
    List<PatientNursingReport> findByPid(Long pid);
    List<PatientNursingReport> findByPidAndEffectiveTimeMidnightBetween(
        Long pid, LocalDateTime start, LocalDateTime end
    );
    List<PatientNursingReport> findByEffectiveTimeMidnight(LocalDateTime effectiveTimeMidnight);
    List<PatientNursingReport> findByLastProcessedAtBefore(LocalDateTime lastProcessedAt);
    List<PatientNursingReport> findByPidOrderByEffectiveTimeMidnightAsc(Long pid);

    Optional<PatientNursingReport> findByPidAndEffectiveTimeMidnight(Long pid, LocalDateTime effectiveTimeMidnight);
}