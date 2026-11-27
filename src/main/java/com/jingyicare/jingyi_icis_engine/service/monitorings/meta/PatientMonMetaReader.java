package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;

@Service
public class PatientMonMetaReader {
    public record PatientMonMeta(
        List<MonitoringGroupPB> monitoringGroups,
        List<MonitoringGroupPB> balanceGroups,
        Map<String, MonitoringParamPB> paramsByCode
    ) {
        public PatientMonMeta {
            monitoringGroups = List.copyOf(monitoringGroups);
            balanceGroups = List.copyOf(balanceGroups);
            paramsByCode = Map.copyOf(paramsByCode);
        }
    }

    public PatientMonMetaReader(
        PatientMonitoringGroupRepository patientGroupRepository,
        PatientMonitoringGroupParamRepository patientGroupParamRepository,
        DeptMonMetaReader deptMonMetaReader,
        PatientReader patientReader
    ) {
        this.patientGroupRepository = patientGroupRepository;
        this.patientGroupParamRepository = patientGroupParamRepository;
        this.deptMonMetaReader = deptMonMetaReader;
        this.patientReader = patientReader;
    }

    /** 读取基础分组和有效参数，未绑定时读取科室默认分组。 */
    public Optional<PatientMonMeta> findMonitoringMeta(long pid) {
        PatientRecord patient = patientReader.findById(pid).orElse(null);
        if (patient == null || patient.getDeptId().isBlank()) return Optional.empty();
        String deptId = patient.getDeptId();
        List<PatientMonitoringGroup> bindings = listActiveGroups(pid);

        // 已有绑定以患者分组为准，获取患者对应的科室分组 groups
        List<DeptMonitoringGroup> deptGroups = bindings.isEmpty()
            ? deptMonMetaReader.listActiveGroups(deptId)
            : deptMonMetaReader.listGroupsByIdsIncludingDeleted(bindings.stream()
                .map(PatientMonitoringGroup::getDeptMonitoringGroupId).toList());
        List<DeptMonitoringGroup> groups = deptGroups.stream()
            .filter(group -> Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_MONITORING)
                || Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE))
            .toList();

        Map<String, MonitoringParamPB> paramsByCode = deptMonMetaReader.getMonitoringParams(deptId);
        List<MonitoringGroupPB> groupsWithParams =
            readMonitoringGroups(groups, bindings, paramsByCode.keySet());
        List<MonitoringGroupPB> monitoringGroups = new ArrayList<>();
        List<MonitoringGroupPB> balanceGroups = new ArrayList<>();
        for (MonitoringGroupPB group : groupsWithParams) {
            if (group.getGroupType() == MonEnums.GROUP_TYPE_BALANCE) {
                balanceGroups.add(group);
            } else {
                monitoringGroups.add(group);
            }
        }
        return Optional.of(new PatientMonMeta(monitoringGroups, balanceGroups, paramsByCode));
    }

    List<MonitoringGroupPB> readMonitoringGroups(
        List<DeptMonitoringGroup> groups, List<PatientMonitoringGroup> bindings,
        Set<String> knownParamCodes
    ) {
        Set<Integer> deptGroupIds = groups.stream().map(DeptMonitoringGroup::getId)
            .collect(Collectors.toSet());

        // 获取患者分组自定义参数 (以deptGroupId为key): customParamsByDeptGroupId
        Map<Integer, List<PatientMonitoringGroupParam>> customParamsByDeptGroupId =
            listCustomParamsByDeptGroupId(bindings.stream()
                .filter(binding -> deptGroupIds.contains(binding.getDeptMonitoringGroupId()))
                .map(PatientMonitoringGroup::getId).toList());

        // 获取科室分组参数: defaultParamsByDeptGroupId
        List<Integer> defaultGroupIds = groups.stream().map(DeptMonitoringGroup::getId)
            .filter(groupId -> !customParamsByDeptGroupId.containsKey(groupId)).toList();
        Map<Integer, List<DeptMonitoringGroupParam>> defaultParamsByDeptGroupId =
            deptMonMetaReader.listActiveGroupParamsByGroupIds(defaultGroupIds);

        List<DeptMonitoringGroup> orderedGroups = groups.stream()
            .sorted(Comparator.comparingInt(DeptMonitoringGroup::getDisplayOrder)
                .thenComparingInt(DeptMonitoringGroup::getId))
            .toList();
        List<MonitoringGroupPB> result = new ArrayList<>();
        for (DeptMonitoringGroup group : orderedGroups) {
            Integer groupId = group.getId();

            // 有患者自定义参数时优先使用，否则读取科室默认参数。
            List<MonitoringGroupParamPB> params = new ArrayList<>();
            List<PatientMonitoringGroupParam> customParams = customParamsByDeptGroupId.get(groupId);
            if (customParams != null) {
                params.addAll(customParams.stream()
                    .map(MonMapper::toMonitoringGroupParamPB).toList());
            } else {
                List<DeptMonitoringGroupParam> defaultParams =
                    defaultParamsByDeptGroupId.get(groupId);
                if (defaultParams != null) {
                    params.addAll(defaultParams.stream()
                        .map(MonMapper::toMonitoringGroupParamPB).toList());
                }
            }
            params = params.stream()
                .filter(param -> knownParamCodes.contains(param.getCode()))
                .sorted(Comparator.comparingInt(MonitoringGroupParamPB::getDisplayOrder)
                    .thenComparingInt(MonitoringGroupParamPB::getId))
                .toList();

            // 转换为 MonitoringGroupPB
            result.add(MonMapper.toMonitoringGroupPB(group, params));
        }
        return result;
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

    public Map<Integer, List<PatientMonitoringGroupParam>> listCustomParamsByDeptGroupId(
        List<Integer> patientGroupIds
    ) {
        if (patientGroupIds.isEmpty()) return Map.of();
        return patientGroupParamRepository.findActiveParamsWithDeptGroupId(patientGroupIds).stream()
            .collect(Collectors.groupingBy(row -> row.getDeptGroupId(),
                Collectors.mapping(row -> row.getParam(), Collectors.toList())));
    }

    private final PatientMonitoringGroupRepository patientGroupRepository;
    private final PatientMonitoringGroupParamRepository patientGroupParamRepository;
    private final DeptMonMetaReader deptMonMetaReader;
    private final PatientReader patientReader;
}
