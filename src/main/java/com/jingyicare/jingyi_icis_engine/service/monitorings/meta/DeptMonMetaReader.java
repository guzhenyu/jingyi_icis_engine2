package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonMapper;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Service
@Slf4j
public class DeptMonMetaReader {
    public DeptMonMetaReader(
        DeptMonitoringParamRepository paramRepo,
        DeptMonitoringGroupRepository groupRepo,
        DeptMonitoringGroupParamRepository groupParamRepo,
        MonMetaReader monMetaReader,
        MonEnums monEnums,
        Config config
    ) {
        this.paramRepo = paramRepo;
        this.groupRepo = groupRepo;
        this.groupParamRepo = groupParamRepo;
        this.monMetaReader = monMetaReader;
        this.monEnums = monEnums;
        this.tubeParamList = config.getTube().getTubeSetting().getParamList();
    }

    public Map<String, MonitoringParamPB> getMonitoringParams(String deptId) {
        List<MonitoringParam> globalParams = monMetaReader.listGlobalParams();
        Map<String, DeptMonitoringParam> overrides = listParamOverridesByDept(deptId).stream()
            .collect(Collectors.toMap(param -> param.getId().getCode(), param -> param));
        Map<String, MonitoringParamPB> paramsByCode =
            toMonitoringParamPBs(deptId, globalParams, overrides);

        // 先收集有效主项，追加的派生项不参与再次展开。
        List<String> tubeParamCodes = paramsByCode.keySet().stream()
            .filter(monEnums::isDrainageTubeParam).toList();
        for (String code : tubeParamCodes) {
            for (MonitoringParamPB template : tubeParamList) {
                MonitoringParamPB tubeParam = template.toBuilder()
                    .setCode(code + "_" + template.getCode()).build();
                paramsByCode.put(tubeParam.getCode(), tubeParam);
            }
        }
        return paramsByCode;
    }

    public Map<String, MonitoringParamPB> getMonitoringParams(String deptId, Set<String> codes) {
        if (codes.isEmpty()) return Map.of();

        Map<String, MonitoringParam> globalParams = monMetaReader.findGlobalParams(codes);
        Map<String, DeptMonitoringParam> overrides = findParamOverrides(deptId, codes);
        return toMonitoringParamPBs(deptId, globalParams.values(), overrides);
    }

    public MonitoringParamPB getMonitoringParam(String deptId, String code) {
        MonitoringParam param = monMetaReader.findGlobalParam(code).orElse(null);
        if (param == null) {
            log.error("MonitoringParam not found for code: {}", code);
            return null;
        }
        return toMonitoringParamPB(deptId, param, findParamOverride(deptId, code).orElse(null));
    }

    public Optional<DeptMonitoringParam> findParamOverride(String deptId, String paramCode) {
        return paramRepo.findByIdDeptIdAndIdCode(deptId, paramCode);
    }

    public List<DeptMonitoringParam> listParamOverridesByDept(String deptId) {
        return paramRepo.findByIdDeptId(deptId);
    }

    public Map<String, MonitoringParam> listEffectiveMonitoringParams(String deptId) {
        Map<String, MonitoringParam> paramsByCode = monMetaReader.listGlobalParams().stream()
            .collect(Collectors.toMap(MonitoringParam::getCode, param -> param));
        for (DeptMonitoringParam override : listParamOverridesByDept(deptId)) {
            String code = override.getId().getCode();
            MonitoringParam param = paramsByCode.get(code);
            if (param == null) {
                log.error("MonitoringParam not found for code {}, deptId {}", code, deptId);
                continue;
            }
            // 全局查询返回副本，科室类型覆盖不会修改全局字典。
            param.setTypePb(override.getTypePb());
        }
        return paramsByCode;
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

    public List<MonitoringGroupBetaPB> listDeptMonitoringGroups(
        String deptId, int groupType, List<String> tubeParamCodes, boolean includeTubeDetails
    ) {
        Map<String, MonitoringParam> paramsByCode = listEffectiveMonitoringParams(deptId);
        List<DeptMonitoringGroup> groups = listActiveGroups(deptId, groupType);
        List<Integer> groupIds = groups.stream().map(DeptMonitoringGroup::getId).toList();
        Map<Integer, List<MonitoringGroupParamPB>> paramsByGroupId = new HashMap<>();
        listActiveGroupParamsByGroupIds(groupIds).forEach(
            (groupId, groupParams) -> paramsByGroupId.put(groupId,
                groupParams.stream().map(MonMapper::toMonitoringGroupParamPB).toList()));

        return MonMapper.toMonitoringGroupBetaPBs(groups, paramsByGroupId, paramsByCode,
            tubeParamCodes, includeTubeDetails ? tubeParamList : List.of());
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

    // 患者已有绑定可能指向已软删的科室分组。
    public Optional<DeptMonitoringGroup> findGroupByIdIncludingDeleted(int groupId) {
        return groupRepo.findById(groupId);
    }

    public List<DeptMonitoringGroup> listGroupsByIdsIncludingDeleted(List<Integer> groupIds) {
        if (groupIds.isEmpty()) return List.of();
        return groupRepo.findByIdIn(groupIds);
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

    private Map<String, MonitoringParamPB> toMonitoringParamPBs(
        String deptId, Collection<MonitoringParam> params,
        Map<String, DeptMonitoringParam> overrides
    ) {
        Map<String, MonitoringParamPB> paramsByCode = new HashMap<>();
        for (MonitoringParam param : params) {
            String code = param.getCode();
            MonitoringParamPB paramPB = toMonitoringParamPB(deptId, param, overrides.get(code));
            if (paramPB != null) paramsByCode.put(code, paramPB);
        }
        return paramsByCode;
    }

    private MonitoringParamPB toMonitoringParamPB(
        String deptId, MonitoringParam param, DeptMonitoringParam override
    ) {
        ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(
            override == null ? param.getTypePb() : override.getTypePb());
        if (valueMeta == null) {
            log.error("ValueMetaPB not found for code: {}, deptId {}", param.getCode(), deptId);
            return null;
        }
        return MonMapper.toMonitoringParamPB(param, valueMeta);
    }

    private final DeptMonitoringParamRepository paramRepo;
    private final DeptMonitoringGroupRepository groupRepo;
    private final DeptMonitoringGroupParamRepository groupParamRepo;
    private final MonMetaReader monMetaReader;
    private final MonEnums monEnums;
    private final List<MonitoringParamPB> tubeParamList;
}
