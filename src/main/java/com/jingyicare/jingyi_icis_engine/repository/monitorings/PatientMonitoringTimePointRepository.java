package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringTimePoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientMonitoringTimePointRepository extends JpaRepository<PatientMonitoringTimePoint, Long> {
    List<PatientMonitoringTimePoint> findAll();

    List<PatientMonitoringTimePoint> findByPidAndIsDeletedFalse(Long pid);

    List<PatientMonitoringTimePoint> findByPidAndTimePointBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime startTime, LocalDateTime endTime
    );

    Optional<PatientMonitoringTimePoint> findByIdAndIsDeletedFalse(Long id);

    @Query("SELECT p FROM PatientMonitoringTimePoint p WHERE p.pid = :pid AND p.timePoint IN :timePoints AND p.isDeleted = false")
    List<PatientMonitoringTimePoint> findExistingTimePointsAndIsDeletedFalse(@Param("pid") long pid, @Param("timePoints") Set<LocalDateTime> timePoints);

    List<PatientMonitoringTimePoint> findByPidAndTimePointInAndIsDeletedFalse(Long pid, Set<LocalDateTime> timePoints);
}