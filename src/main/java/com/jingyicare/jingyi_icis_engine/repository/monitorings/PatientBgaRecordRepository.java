package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecord;

public interface PatientBgaRecordRepository extends JpaRepository<PatientBgaRecord, Long> {
    Optional<PatientBgaRecord> findByIdAndIsDeletedFalse(Long pid);

    List<PatientBgaRecord> findByIdInAndIsDeletedFalse(List<Long> ids);

    List<PatientBgaRecord> findByPidAndIsDeletedFalseOrderByEffectiveTime(Long pid);

    Optional<PatientBgaRecord> findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
        Long pid, Integer bgaCategoryId, LocalDateTime effectiveTime);

    Optional<PatientBgaRecord> findByPidAndBgaCategoryIdAndEffectiveTime(
        Long pid, Integer bgaCategoryId, LocalDateTime effectiveTime);

    Boolean existsByPidAndBgaCategoryIdAndEffectiveTime(
        Long pid, Integer bgaCategoryId, LocalDateTime effectiveTime);

    Boolean existsByPidAndRawRecordId(Long pid, Long rawRecordId);

    List<PatientBgaRecord> findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime startTime, LocalDateTime endTime);
}