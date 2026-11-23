package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;

@Service
public class PatientMonMetaReader {
    public PatientMonMetaReader(
        PatientMonitoringGroupRepository patientGroupRepository,
        PatientMonitoringGroupParamRepository patientGroupParamRepository
    ) {
        this.patientGroupRepository = patientGroupRepository;
        this.patientGroupParamRepository = patientGroupParamRepository;
    }

    public boolean hasActiveDeptGroupBindings(List<Integer> groupIds) {
        return !groupIds.isEmpty()
            && patientGroupRepository.existsByDeptMonitoringGroupIdInAndIsDeletedFalse(groupIds);
    }

    public List<PatientMonitoringGroup> listActiveGroups(long pid) {
        return patientGroupRepository.findByPidAndIsDeletedFalse(pid);
    }

    public Optional<PatientMonitoringGroup> findActiveGroupByDeptGroupId(
        long pid, int deptGroupId
    ) {
        return patientGroupRepository
            .findByDeptMonitoringGroupIdAndPidAndIsDeletedFalse(deptGroupId, pid);
    }

    public List<PatientMonitoringGroupParam> listActiveGroupParams(int patientGroupId) {
        return patientGroupParamRepository.findByPatientMonitoringGroupIdAndIsDeletedFalse(
            patientGroupId);
    }

    public Map<Integer, List<PatientMonitoringGroupParam>> listActiveGroupParamsByGroupIds(
        List<Integer> patientGroupIds
    ) {
        if (patientGroupIds.isEmpty()) return Map.of();
        return patientGroupParamRepository
            .findByPatientMonitoringGroupIdInAndIsDeletedFalse(patientGroupIds).stream()
            .collect(Collectors.groupingBy(
                PatientMonitoringGroupParam::getPatientMonitoringGroupId));
    }

    private final PatientMonitoringGroupRepository patientGroupRepository;
    private final PatientMonitoringGroupParamRepository patientGroupParamRepository;
}
