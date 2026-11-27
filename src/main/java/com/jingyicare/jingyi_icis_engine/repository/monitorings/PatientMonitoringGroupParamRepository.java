package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientMonitoringGroupParamRepository extends JpaRepository<PatientMonitoringGroupParam, Integer> {

    List<PatientMonitoringGroupParam> findAll();

    List<PatientMonitoringGroupParam> findByPatientMonitoringGroupIdAndIsDeletedFalse(Integer patientMonitoringGroupId);

    List<PatientMonitoringGroupParam> findByMonitoringParamCodeAndIsDeletedFalse(String monitoringParamCode);

    List<PatientMonitoringGroupParam> findByPatientMonitoringGroupIdInAndIsDeletedFalse(List<Integer> patientMonitoringGroupIds);

    @Query("""
        SELECT g.deptMonitoringGroupId AS deptGroupId, p AS param
        FROM PatientMonitoringGroupParam p
        JOIN PatientMonitoringGroup g ON g.id = p.patientMonitoringGroupId
        WHERE g.id IN :patientGroupIds AND g.isDeleted = false AND p.isDeleted = false
        """)
    List<DeptGroupParam> findActiveParamsWithDeptGroupId(
        @Param("patientGroupIds") List<Integer> patientGroupIds
    );

    Optional<PatientMonitoringGroupParam> findByPatientMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(Integer patientMonitoringGroupId, String monitoringParamCode);

    @Query(value = "select pmgp.* from patient_monitoring_group_params pmgp " +
           "join patient_monitoring_groups pmg on pmgp.patient_monitoring_group_id = pmg.id " +
           "where pmg.pid = :pid and pmgp.is_deleted = :isDeleted", nativeQuery = true)
    List<PatientMonitoringGroupParam> findByPidAndIsDeleted(Long pid, Boolean isDeleted);

    interface DeptGroupParam {
        Integer getDeptGroupId();

        PatientMonitoringGroupParam getParam();
    }
}
