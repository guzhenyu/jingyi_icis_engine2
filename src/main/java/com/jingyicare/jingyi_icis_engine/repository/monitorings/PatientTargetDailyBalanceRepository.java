package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientTargetDailyBalance;

public interface PatientTargetDailyBalanceRepository extends JpaRepository<PatientTargetDailyBalance, Long> {

    List<PatientTargetDailyBalance> findAll();

    List<PatientTargetDailyBalance> findAllByIsDeletedFalse();

    List<PatientTargetDailyBalance> findByPidAndIsDeletedFalse(Long pid);

    List<PatientTargetDailyBalance> findByPidAndShiftStartTimeBetweenAndIsDeletedFalse(
            Long pid, LocalDateTime startTime, LocalDateTime endTime);
    
    List<PatientTargetDailyBalance> findByPidAndShiftStartTimeAndIsDeletedFalse(
        Long pid, LocalDateTime startTime);

    Optional<PatientTargetDailyBalance> findByIdAndIsDeletedFalse(Long id);
}
