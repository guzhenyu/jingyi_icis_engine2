package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;

public interface HisPatientRecordRepository extends JpaRepository<HisPatientRecord, Long> {
    List<HisPatientRecord> findByAdmissionStatusAndDeptCodeInOrderByMrnAsc(
        Integer admissionStatus, List<String> deptCodes
    );
    List<HisPatientRecord> findByAdmissionStatusOrderByMrnAsc(Integer admissionStatus);
    List<HisPatientRecord> findByPid(String pid);  // HisPatientId

    Optional<HisPatientRecord> findByPidAndAdmissionStatus(String pid, Integer admissionStatus);
}