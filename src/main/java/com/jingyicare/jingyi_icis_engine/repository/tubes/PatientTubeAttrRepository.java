package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeAttr;

public interface PatientTubeAttrRepository extends JpaRepository<PatientTubeAttr, Long> {
    List<PatientTubeAttr> findAll();  // 查询所有病人管道属性记录

    // 根据病人插管记录ID查询属性
    List<PatientTubeAttr> findByPatientTubeRecordId(Long patientTubeRecordId);  // 按病人插管记录ID查询

    List<PatientTubeAttr> findByPatientTubeRecordIdIn(List<Long> recordIds);

    // 根据病人插管记录ID和管道属性ID查询属性
    Optional<PatientTubeAttr> findByPatientTubeRecordIdAndTubeAttrId(Long patientTubeRecordId, Integer tubeAttrId);
}

/*
package com.jingyicare.jingyi_icis_engine.repository.tubes;

import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeAttr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PatientTubeAttrRepository extends JpaRepository<PatientTubeAttr, Long> {

    
}
*/