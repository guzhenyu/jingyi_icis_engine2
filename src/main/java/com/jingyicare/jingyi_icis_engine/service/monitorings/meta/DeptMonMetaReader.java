package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;

@Service
public class DeptMonMetaReader {
    public DeptMonMetaReader(
        DeptMonitoringParamRepository paramRepo, DeptMonitoringGroupRepository groupRepo
    ) {
        this.paramRepo = paramRepo;
        this.groupRepo = groupRepo;
    }

    public Optional<DeptMonitoringParam> findParamOverride(String deptId, String paramCode) {
        return paramRepo.findByIdDeptIdAndIdCode(deptId, paramCode);
    }

    public List<DeptMonitoringParam> listParamOverridesByDept(String deptId) {
        return paramRepo.findByIdDeptId(deptId);
    }

    public List<DeptMonitoringParam> listParamOverridesByCode(String paramCode) {
        return paramRepo.findByIdCode(paramCode);
    }

    public Map<String, DeptMonitoringParam> findParamOverrides(
        String deptId, Set<String> paramCodes
    ) {
        if (paramCodes.isEmpty()) return Map.of();
        return paramRepo.findByIdDeptIdAndIdCodeIn(deptId, paramCodes).stream()
            .collect(Collectors.toMap(param -> param.getId().getCode(), param -> param));
    }

    public boolean hasDeptOverride(String paramCode) {
        return paramRepo.existsByIdCode(paramCode);
    }

    public List<DeptMonitoringGroup> listActiveGroups(String deptId, int groupType) {
        return groupRepo.findByDeptIdAndGroupTypeAndIsDeletedFalse(deptId, groupType);
    }

    private final DeptMonitoringParamRepository paramRepo;
    private final DeptMonitoringGroupRepository groupRepo;
}
