package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.PatientCriticalLisHandling;

public interface PatientCriticalLisHandlingRepository extends JpaRepository<PatientCriticalLisHandling, Integer> {
    List<PatientCriticalLisHandling> findAllByIsDeletedFalse();
    List<PatientCriticalLisHandling> findByIdInAndIsDeletedFalse(List<Integer> ids);

    Optional<PatientCriticalLisHandling> findByPidAndIsDeletedFalse(Long pid);
    Optional<PatientCriticalLisHandling> findByIdAndIsDeletedFalse(Integer id);
}