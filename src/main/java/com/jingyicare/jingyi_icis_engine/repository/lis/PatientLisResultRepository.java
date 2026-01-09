package com.jingyicare.jingyi_icis_engine.repository.lis;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.lis.PatientLisResult;

public interface PatientLisResultRepository extends JpaRepository<PatientLisResult, Long> {
    List<PatientLisResult> findByReportIdAndIsDeletedFalse(String reportId);
    List<PatientLisResult> findByReportIdInAndIsDeletedFalse(List<String> reportIds);
}
