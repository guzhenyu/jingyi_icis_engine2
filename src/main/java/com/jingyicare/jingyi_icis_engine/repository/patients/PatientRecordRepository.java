package com.jingyicare.jingyi_icis_engine.repository.patients;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;

public interface PatientRecordRepository extends JpaRepository<PatientRecord, Long>, JpaSpecificationExecutor<PatientRecord> {

    List<PatientRecord> findAll();

    List<PatientRecord> findByIdIn(List<Long> ids);

    List<PatientRecord> findByHisMrnInAndAdmissionStatusIn(List<String> hisMrns, List<Integer> admissionStatus);

    List<PatientRecord> findByAdmissionStatusInOrderByHisMrnAsc(List<Integer> admissionStatuses);

    List<PatientRecord> findByDeptIdAndAdmissionStatusInAndAdmissionTimeBefore(
        String deptId, List<Integer> admissionStatuses, LocalDateTime admissionTime);

    @Query("SELECT p FROM PatientRecord p WHERE p.deptId = :deptId " +
        "AND p.admissionStatus=3 AND p.admissionTime < :admissionTime " +
        "AND (p.dischargeTime IS NOT NULL AND p.dischargeTime > :dischargeTime)"
    )
    List<PatientRecord> findDischargedPatients(
        @Param("deptId") String deptId,
        @Param("admissionTime") LocalDateTime admissionTime,
        @Param("dischargeTime") LocalDateTime dischargeTime);

    // @Query(value = "SELECT p1.* FROM " +
    //     "patient_records p1 JOIN" +
    //     "(SELECT his_mrn, MAX(id) AS max_id FROM patient_records " +
    //     "  WHERE dept_id = :deptId AND admission_status = :status GROUP BY his_mrn) p2 " +
    //     "ON p1.id = p2.max_id",
    //     nativeQuery = true
    // )
    // List<PatientRecord> findByDeptIdAndAdmissionStatus(
    //     @Param("deptId") String deptId, @Param("status") Integer status);
    List<PatientRecord> findByDeptIdAndAdmissionStatus(String deptId, Integer status);

    @Query(value = "SELECT p1.* FROM " +
        "patient_records p1 JOIN " +
        "(SELECT his_mrn, MAX(id) AS max_id FROM patient_records " +
        "  WHERE dept_id = :deptId AND admission_status IN :statuses GROUP BY his_mrn) p2 " +
        "ON p1.id = p2.max_id",
        nativeQuery = true
    )
    List<PatientRecord> findByDeptIdAndAdmissionStatusIn(
            @Param("deptId") String deptId, @Param("statuses") List<Integer> statuses);

    @Query(value = "SELECT * FROM patient_records " +
                "WHERE dept_id = :deptId AND his_mrn = :hisMrn AND admission_status != :status ",
        nativeQuery = true)
    List<PatientRecord> findByMrnAndAdmissionStatusNotEquals(
        @Param("deptId") String deptId, @Param("hisMrn") String hisMrn, @Param("status") Integer status);
    
    @Query(value = "SELECT * FROM patient_records " +
                "WHERE dept_id = :deptId AND his_mrn = :hisMrn AND admission_status = :status ",
        nativeQuery = true)
    List<PatientRecord> findByMrnAndAdmissionStatus(
        @Param("deptId") String deptId, @Param("hisMrn") String hisMrn, @Param("status") Integer status);

    @Query(value = "SELECT * FROM patient_records WHERE dept_id = :deptId",
        nativeQuery = true)
    List<PatientRecord> findByDeptId(@Param("deptId") String deptId);

    @Query(value = "SELECT * FROM patient_records " +
                "WHERE (his_mrn LIKE %:mrnOrName% OR icu_name LIKE %:mrnOrName%)",
        nativeQuery = true)
    List<PatientRecord> findByMrnOrName(@Param("mrnOrName") String mrnOrName);

    Optional<PatientRecord> findById(Long id);

    Optional<PatientRecord> findByIdAndAdmissionStatus(Long id, Integer admissionStatus);

    // List<PatientRecord> findByDeptIdAndHisMrnOrderByIdDesc(String deptId,String hisMrn);

    // @Query("SELECT p FROM PatientRecord p WHERE p.deptId = :deptId AND p.hisMrn = :hisMrn AND p.admissionStatus IN ('PENDING_ADMISSION', 'IN_ICU')")
    // List<PatientRecord> findByDeptIdAndHisMrnByAdmissionStatus(String deptId, String hisMrn);

    // // 查询患者入科前24小时内的出科记录。如果有出科记录，护士可以选择是否将新的入科患者记录与上一条出入科患者记录进行合并
    // @Query("SELECT p FROM PatientRecord p WHERE p.deptId = :deptId AND p.hisMrn = :hisMrn AND p.admissionStatus = :admissionStatus AND p.dischargeTime >= :startTime AND p.dischargeTime <= :endTime ORDER BY p.dischargeTime DESC LIMIT 1")
    // Optional<PatientRecord> findFirstByDeptIdAndAdmissionStatusAndDischargeTimeRange(String deptId, String hisMrn, Integer admissionStatus, LocalDateTime startTime, LocalDateTime endTime);
}

