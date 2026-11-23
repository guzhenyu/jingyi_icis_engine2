package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;

@Service
public class DeptMonMetaReader {
    public DeptMonMetaReader(
        DeptMonitoringParamRepository paramRepo,
        DeptMonitoringGroupRepository groupRepo,
        DeptMonitoringGroupParamRepository groupParamRepo
    ) {
        this.paramRepo = paramRepo;
        this.groupRepo = groupRepo;
        this.groupParamRepo = groupParamRepo;
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

    public List<DeptMonitoringGroup> listActiveGroups(String deptId) {
        return groupRepo.findByDeptIdAndIsDeletedFalse(deptId);
    }

    public Optional<DeptMonitoringGroup> findActiveGroup(String deptId, int groupId) {
        return groupRepo.findByIdAndDeptIdAndIsDeletedFalse(groupId, deptId);
    }

    // findGroupByIdIncludingDeleted listGroupsByIdsAndTypeIncludingDeleted
    // 用于查找患者的特殊化分组配置
    public Optional<DeptMonitoringGroup> findGroupByIdIncludingDeleted(int groupId) {
        return groupRepo.findById(groupId);
    }

    public List<DeptMonitoringGroup> listGroupsByIdsAndTypeIncludingDeleted(
        List<Integer> groupIds, int groupType
    ) {
        if (groupIds.isEmpty()) return List.of();
        return groupRepo.findByIdInAndGroupType(groupIds, groupType);
    }

    public List<DeptMonitoringGroupParam> listActiveGroupParams(int groupId) {
        return groupParamRepo.findByDeptMonitoringGroupIdAndIsDeletedFalse(groupId);
    }

    public Map<Integer, List<DeptMonitoringGroupParam>> listActiveGroupParamsByGroupIds(
        List<Integer> groupIds
    ) {
        if (groupIds.isEmpty()) return Map.of();
        return groupParamRepo.findByDeptMonitoringGroupIdInAndIsDeletedFalse(groupIds).stream()
            .collect(Collectors.groupingBy(DeptMonitoringGroupParam::getDeptMonitoringGroupId));
    }

    private final DeptMonitoringParamRepository paramRepo;
    private final DeptMonitoringGroupRepository groupRepo;
    private final DeptMonitoringGroupParamRepository groupParamRepo;
}
