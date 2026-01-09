package com.jingyicare.jingyi_icis_engine.repository.checklists;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.checklists.PatientChecklistGroup;

public interface PatientChecklistGroupRepository extends JpaRepository<PatientChecklistGroup, Long> {
    List<PatientChecklistGroup> findByRecordIdInAndIsDeletedFalse(List<Integer> recordIds);
}