package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringGroupRepository;

@Service
public class PatientMonMetaReader {
    public PatientMonMetaReader(PatientMonitoringGroupRepository patientGroupRepository) {
        this.patientGroupRepository = patientGroupRepository;
    }

    public boolean hasActiveDeptGroupBindings(List<Integer> groupIds) {
        return !groupIds.isEmpty()
            && patientGroupRepository.existsByDeptMonitoringGroupIdInAndIsDeletedFalse(groupIds);
    }

    private final PatientMonitoringGroupRepository patientGroupRepository;
}
