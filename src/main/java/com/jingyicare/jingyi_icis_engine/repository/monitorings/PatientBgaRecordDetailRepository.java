package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecordDetail;

public interface PatientBgaRecordDetailRepository extends JpaRepository<PatientBgaRecordDetail, Long> {
    List<PatientBgaRecordDetail> findByRecordIdAndIsDeletedFalse(Long recordId);
    List<PatientBgaRecordDetail> findByRecordIdInAndIsDeletedFalse(List<Long> recordIds);

    @Query(value = "SELECT rd.* FROM patient_bga_record_details rd JOIN patient_bga_records r " +
            "ON rd.record_id = r.id " +
            "WHERE r.is_deleted = false AND rd.is_deleted = false " +
            "AND r.bga_category_id = :bgaCategoryId AND rd.monitoring_param_code = :monitoringParamCode " +
            "AND r.pid = :pid AND r.effective_time >= :startTime AND r.effective_time < :endTime",
        nativeQuery = true)
    List<PatientBgaRecordDetail> findRecordDetails(
         @Param("pid") Long pid,
         @Param("bgaCategoryId") Integer bgaCategoryId,
         @Param("monitoringParamCode") String monitoringParamCode,
         @Param("startTime") LocalDateTime startTime, 
         @Param("endTime") LocalDateTime endTime);
}