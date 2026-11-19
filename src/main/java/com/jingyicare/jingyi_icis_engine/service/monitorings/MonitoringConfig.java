package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MonitoringConfig {
    public static MonitoringParamPB fromMonitoringParam(
        MonitoringParam param, Integer patientOrDeptParamId,
        ValueMetaPB valueMeta, Integer nextDisplayOrder
    ) {
        MonitoringParamPB.Builder paramBuilder = MonitoringParamPB.newBuilder()
            .setId(patientOrDeptParamId != null ? patientOrDeptParamId : 0)
            .setCode(param.getCode())
            .setName(param.getName())
            .setNameEn(param.getNameEn())
            .setValueMeta(valueMeta)
            .setBalanceType(param.getBalanceType())
            .setCategory(param.getCategory())
            .setChartSign(param.getChartSign())
            .setDisplayOrder(nextDisplayOrder != null ? nextDisplayOrder : param.getDisplayOrder());
        if (!StrUtils.isBlank(param.getUiModalCode())) {
            paramBuilder.setUiModalCode(param.getUiModalCode());
        }
        return paramBuilder.build();
    }

    public MonitoringConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired MonitoringParamRepository monitoringParamRepository,
        @Autowired DeptMonitoringParamRepository deptMonitoringParamRepository,
        @Autowired DeptMonitoringGroupRepository deptMonitoringGroupRepository,
        @Autowired DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository,
        @Autowired PatientMonitoringMetaService patientMonitoringMetaService,
        @Autowired PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository,
        @Autowired DeptSystemSettingsRepository deptSettingRepository,
        @Value("${monitoring.enable_drainage:true}") boolean enableDrainage,
        @Value("${monitoring.enable_drainage_params:true}") boolean enableDrainageParams
    ) {
        this.context = context;
        this.protoService = protoService;
        this.configShiftUtils = configShiftUtils;

        this.monitoringPb = protoService.getConfig().getMonitoring();
        this.BALANCE_IN_ID = monitoringPb.getEnums().getBalanceIn().getId();
        this.BALANCE_OUT_ID = monitoringPb.getEnums().getBalanceOut().getId();
        this.BALANCE_NET_ID = monitoringPb.getEnums().getBalanceNet().getId();
        this.GROUP_TYPE_BALANCE_ID = monitoringPb.getEnums().getGroupTypeBalance().getId();
        this.OUT_GROUP_NAME = monitoringPb.getBalanceOutGroupName();
        this.DRAINAGE_TUBE_PREFIX = "tube_" + StrUtils.toPinyinInitials(protoService.getConfig().getTube().getDrainageTubeType()) + "_";
        this.tubeParamList = protoService.getConfig().getTube().getTubeSetting().getParamList();
        validateMonitoringConfig();

        this.paramModalMap = monitoringPb.getParamModalOptionList().stream()
            .collect(Collectors.toMap(
                MonitoringParamModalOptionsPB::getParamCode,
                modalOptions -> modalOptions
            ));

        this.monitoringParamRepository = monitoringParamRepository;
        this.deptMonitoringParamRepository = deptMonitoringParamRepository;
        this.deptMonitoringGroupRepository = deptMonitoringGroupRepository;
        this.deptMonitoringGroupParamRepository = deptMonitoringGroupParamRepository;
        this.patientMonitoringMetaService = patientMonitoringMetaService;
        this.patientMonitoringGroupParamRepository = patientMonitoringGroupParamRepository;
        this.deptSettingRepository = deptSettingRepository;
        this.enableDrainage = enableDrainage;
        this.enableDrainageParams = enableDrainageParams;
    }

    public Integer getBalanceGroupTypeId() { return GROUP_TYPE_BALANCE_ID; }
    public String getDrainageTubeParamPrefix() { return DRAINAGE_TUBE_PREFIX; }

    private void validateMonitoringConfig() {
        // 观察项参数是否合法
        Map<String, MonitoringParamPB> allParamMap = new HashMap<>();
        for (MonitoringParamPB paramPb : monitoringPb.getMonitoringParamList()) {
            StatusCode statusCode = isValidParamPb(paramPb);
            if (statusCode != StatusCode.OK) LogUtils.flushAndQuit(context);
            allParamMap.put(paramPb.getCode(), paramPb);
        }

        // 检查平衡量分组
        Set<String> requiredBalanceGroupNames = new HashSet<>(Set.of(
            monitoringPb.getBalanceInGroupName(),
            monitoringPb.getBalanceOutGroupName(),
            monitoringPb.getBalanceNetGroupName()
        ));

        Set<String> existingBalanceGroupNames = new HashSet<>();
        for (MonitoringGroupPB group : monitoringPb.getBalanceGroupList()) {
            final String groupName = group.getName();

            // 检查平衡量分组是否有重复的组名
            if (existingBalanceGroupNames.contains(groupName)) {
                log.error("Duplicate group name found in MonitoringPB.balance_group: {}", groupName);
                LogUtils.flushAndQuit(context);
            }
            existingBalanceGroupNames.add(group.getName());

            // 检查预设的平衡量分组组名是否存在
            if (requiredBalanceGroupNames.contains(groupName)) {
                requiredBalanceGroupNames.remove(groupName);
            }

            // 检查平衡量分组参数的合法性
            for (MonitoringGroupParamPB paramPb : group.getMonitoringParamList()) {
                String code = paramPb.getCode();

                MonitoringParamPB paramPbFromMap = allParamMap.get(code);
                if (paramPbFromMap == null) {
                    log.error("MonitoringGroupParamPB code not found in MonitoringParamPB: {}", code);
                    LogUtils.flushAndQuit(context);
                }
                Integer balanceType = paramPbFromMap.getBalanceType();
                if (balanceType == BALANCE_IN_ID && !groupName.equals(monitoringPb.getBalanceInGroupName())) {
                    log.error("MonitoringGroupParamPB code: {} belongs to BalanceIn, but group name is not BalanceInGroupName: {}", code, groupName);
                    LogUtils.flushAndQuit(context);
                }
                if (balanceType == BALANCE_OUT_ID && !groupName.equals(monitoringPb.getBalanceOutGroupName())) {
                    log.error("MonitoringGroupParamPB code: {} belongs to BalanceOut, but group name is not BalanceOutGroupName: {}", code, groupName);
                    LogUtils.flushAndQuit(context);
                }
                if (balanceType == BALANCE_NET_ID && !groupName.equals(monitoringPb.getBalanceNetGroupName())) {
                    log.error("MonitoringGroupParamPB code: {} belongs to BalanceNet, but group name is not BalanceNetGroupName: {}", code, groupName);
                    LogUtils.flushAndQuit(context);
                }
            }
        }

        // 检查平衡量分组是否包含所有必需的组名
        if (!requiredBalanceGroupNames.isEmpty()) {
            log.error("MonitoringPB.balance_group does not contain required group names: {}",
                requiredBalanceGroupNames);
            LogUtils.flushAndQuit(context);
        }
    }

    public MonitoringParamModalOptionsPB getParamModalOptions(String paramCode) {
        return paramModalMap.get(paramCode);
    }

    ////// 系统初始化工具函数: checkIntegrity, refresh
    @Transactional
    public void checkIntegrity() {
        checkBgaParamIntegrity();
    }

    private void checkBgaParamIntegrity() {
        // 检查BGA参数是否存在于观察项参数中
        List<MonitoringParamPB> monitoringParamPbs = monitoringPb.getMonitoringParamList();
        Set<String> monitoringParamCodes = monitoringParamPbs.stream()
            .map(MonitoringParamPB::getCode)
            .collect(Collectors.toSet());

        for (String bgaParamCode : protoService.getConfig().getBga().getBgaParamCodeList()) {
            if (!monitoringParamCodes.contains(bgaParamCode)) {
                log.error("BGA param code not found in monitoring params: {}", bgaParamCode);
                LogUtils.flushAndQuit(context);
            }
        }
    }

    public void refresh() {
    }

    ////// 外部工具函数
    public StatusCode isValidParamPb(MonitoringParamPB paramPb) {
        return MonitoringMapper.isValidParamPb(paramPb, monitoringPb);
    }

    @Transactional
    public Map<String, ValueMetaPB> getMonitoringParamMetas(String deptId, Set<String> codes) {
        // 获取系统参数，以及部门定制化参数
        List<MonitoringParam> monitoringParams = monitoringParamRepository.findByCodeIn(codes);
        Map<String, DeptMonitoringParam> deptParamMap = deptMonitoringParamRepository
            .findByIdDeptIdAndIdCodeIn(deptId, codes).stream()
            .collect(Collectors.toMap(
                param -> param.getId().getCode(),
                param -> param
            ));

        // 获取参数元数据
        Map<String, ValueMetaPB> paramMetaMap = new HashMap<>();
        for (MonitoringParam param : monitoringParams) {
            String code = param.getCode();
            ValueMetaPB valueMeta = null;

            DeptMonitoringParam deptParam = deptParamMap.get(code);
            if (deptParam == null) {
                // 如果部门没有定制化参数，则使用系统参数
                valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
            } else {
                // 如果部门有定制化参数，则使用部门参数
                valueMeta = ProtoUtils.decodeValueMeta(deptParam.getTypePb());
            }
            if (valueMeta == null) {
                log.error("ValueMetaPB not found for code: {}, deptId {}", code, deptId);
                continue;
            }
            paramMetaMap.put(code, valueMeta);
        }

        return paramMetaMap;
    }

    public ValueMetaPB getMonitoringParamMeta(String deptId, String code) {
        DeptMonitoringParam deptParam = deptMonitoringParamRepository
            .findByIdDeptIdAndIdCode(deptId, code).orElse(null);
        if (deptParam != null) {
            return ProtoUtils.decodeValueMeta(deptParam.getTypePb());
        }

        MonitoringParam monitoringParam = monitoringParamRepository.findByCode(code).orElse(null);
        if (monitoringParam != null) {
            return ProtoUtils.decodeValueMeta(monitoringParam.getTypePb());
        }

        return null;
    }

    public Map<String, MonitoringParamPB> getMonitoringParams(String deptId) {
        // 获取系统参数，以及部门定制化参数
        List<MonitoringParam> monitoringParams = monitoringParamRepository.findAll();
        Map<String, DeptMonitoringParam> deptParamMap = deptMonitoringParamRepository
            .findByIdDeptId(deptId).stream().collect(Collectors.toMap(
                param -> param.getId().getCode(),
                param -> param
            ));

        // 获取部门参数PB
        Map<String, MonitoringParamPB> monitoringParamMap = new HashMap<>();
        Set<String> drainageTubeCodes = new HashSet<>();
        for (MonitoringParam param : monitoringParams) {
            String code = param.getCode();

            ValueMetaPB valueMeta = null;
            DeptMonitoringParam deptParam = deptParamMap.get(code);
            if (deptParam != null) {
                // 如果部门有定制化参数，则使用部门参数
                valueMeta = ProtoUtils.decodeValueMeta(deptParam.getTypePb());
            } else {
                // 如果部门没有定制化参数，则使用系统参数
                valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
            }
            if (valueMeta == null) {
                log.error("ValueMetaPB not found for code: {}, deptId {}", code, deptId);
                continue;
            }

            MonitoringParamPB paramPb = fromMonitoringParam(
                param, null/*patientOrDeptParamId*/, valueMeta, null/*displayOrder*/);
            monitoringParamMap.put(code, paramPb);

            if (code.startsWith(DRAINAGE_TUBE_PREFIX)) {
                drainageTubeCodes.add(code);
            }
        }

        // 装配引流管参数
        if (!drainageTubeCodes.isEmpty()) {
            for (String code : drainageTubeCodes) {
                for (MonitoringParamPB tubeParam : tubeParamList) {
                    MonitoringParamPB newTubeParam = tubeParam.toBuilder()
                        .setCode(code + "_" + tubeParam.getCode())
                        .build();
                    monitoringParamMap.put(newTubeParam.getCode(), newTubeParam);
                }
            }
        }

        return monitoringParamMap;
    }

    public MonitoringParamPB getMonitoringParam(String deptId, String code) {
        // 获取系统参数
        MonitoringParam monitoringParam = monitoringParamRepository.findByCode(code).orElse(null);
        if (monitoringParam == null) {
            log.error("MonitoringParam not found for code: {}", code);
            return null;
        }

        // 获取参数元数据
        ValueMetaPB valueMeta = null;
        DeptMonitoringParam deptParam = deptMonitoringParamRepository
            .findByIdDeptIdAndIdCode(deptId, code).orElse(null);
        if (deptParam != null) {
            // 如果部门有定制化参数，则使用部门参数
            valueMeta = ProtoUtils.decodeValueMeta(deptParam.getTypePb());
        } else {
            // 如果部门没有定制化参数，则使用系统参数
            valueMeta = ProtoUtils.decodeValueMeta(monitoringParam.getTypePb());
        }
        if (valueMeta == null) {
            log.error("ValueMetaPB not found for code: {}, deptId {}", code, deptId);
            return null;
        }

        return fromMonitoringParam(
            monitoringParam, null/*patientOrDeptParamId*/, valueMeta, null/*displayOrder*/
        );
    }

    // pmr = patient monitoring record
    public Pair<LocalDateTime, LocalDateTime> normalizePmrQueryTimeRange(
        int groupType, String deptId, LocalDateTime startTime, LocalDateTime endTime
    ) {
        LocalDateTime pmrQueryStartUtc = startTime;
        LocalDateTime pmrQueryEndUtc = endTime;
        if (groupType == monitoringPb.getEnums().getGroupTypeBalance().getId()) {
            List<LocalDateTime> balanceStatTimeUtcHistory = configShiftUtils.getBalanceStatTimeUtcHistory(deptId);
            pmrQueryStartUtc = configShiftUtils.getBalanceStatStartUtc(balanceStatTimeUtcHistory, startTime);
            pmrQueryEndUtc = configShiftUtils.getBalanceStatStartUtc(balanceStatTimeUtcHistory, endTime);
            if (!endTime.equals(pmrQueryEndUtc)) pmrQueryEndUtc = pmrQueryEndUtc.plusDays(1);
        } else {
            DeptMonitoringSettingsPB monitoringSettings = getDeptMonitoringSettings(deptId);
            int headCustomTimeGraceMinutes = monitoringSettings.getHeadCustomTimePointGraceMinutes();
            int tailCustomTimeGraceMinutes = monitoringSettings.getTailCustomTimePointGraceMinutes();
            pmrQueryStartUtc = pmrQueryStartUtc.minusMinutes(headCustomTimeGraceMinutes);
            pmrQueryEndUtc = pmrQueryEndUtc.plusMinutes(tailCustomTimeGraceMinutes);
        }

        return new Pair<>(pmrQueryStartUtc, pmrQueryEndUtc);
    }

    // 获取观察项分组
    // 关键子目标：
    //  - Map<String/*code*/, MonitoringParam> deptParamMap
    //    如果部门有定制化参数，则使用部门参数，否则使用系统参数
    //  - List<DeptMonitoringGroup> deptGroups + Map<deptGroupId, List<Pair<Code, DisplayOrder>>> deptGroupParams
    //    如果pid为空，则使用部门分组及其参数
    //    如果pid不为空：
    //        如果患者分组为空，则用部门分组初始化患者分组；使用部门分组及其参数
    //        如果患者分组不为空，则根据患者分组和部门分组的映射，获取对应的部门分组
    //              如果部门分组对应的患者分组参数存在，用患者参数
    //              如果部门分组对应的患者分组参数不存在，则用部门分组参数
    // 用子目标装配List<MonitoringGroupBetaPB>
    public List<MonitoringGroupBetaPB> getMonitoringGroups(
        Long pid, String deptId, int groupType, List<String> balanceOutTubeParamCodes, String accountId
    ) {
        Map<String, MonitoringParam> paramMap = buildMonitoringParamMap(deptId);

        // 患者绑定和组内参数在同一把业务锁下读取。
        Pair<List<DeptMonitoringGroup>, Map<Integer, List<MonitoringGroupParamPB>>> pair =
            pid == null || pid <= 0 ? getGroupsAndParams(pid, deptId, groupType, accountId)
                : patientMonitoringMetaService.readUnderLock(pid,
                    () -> getGroupsAndParams(pid, deptId, groupType, accountId));
        List<DeptMonitoringGroup> deptGroups = pair.getFirst();
        Map<Integer, List<MonitoringGroupParamPB>> deptGroupParams = pair.getSecond();

        return assembleMonitoringGroupBetaPbs(
            paramMap, deptGroups, deptGroupParams, balanceOutTubeParamCodes, deptId
        );
    }

    @Transactional
    private Map<String, MonitoringParam> buildMonitoringParamMap(String deptId) {
        Map<String/*code*/, MonitoringParam> paramMap = monitoringParamRepository.findAll()
            .stream().collect(Collectors.toMap(MonitoringParam::getCode, param -> param));
        for (DeptMonitoringParam deptParam : deptMonitoringParamRepository.findByIdDeptId(deptId)) {
            String code = deptParam.getId().getCode();
            MonitoringParam param = paramMap.get(code);
            if (param == null) {
                log.error("MonitoringParam not found for code {}, deptId {}", code, deptId);
                continue;
            }
            // 科室配置只覆盖返回副本，避免脏检查将其写回全局参数。
            paramMap.put(code, param.toBuilder().typePb(deptParam.getTypePb()).build());
        }
        return paramMap;
    }

    @Transactional
    private Map<Integer/*deptGroupId*/, List<MonitoringGroupParamPB>> getDeptGroupParams(List<Integer> deptGroupIds) {
        return deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdInAndIsDeletedFalse(deptGroupIds)
            .stream()
            .collect(Collectors.groupingBy(
                DeptMonitoringGroupParam::getDeptMonitoringGroupId,
                Collectors.mapping(
                    param -> MonitoringGroupParamPB.newBuilder()
                        .setId(param.getId())
                        .setCode(param.getMonitoringParamCode())
                        .setDisplayOrder(param.getDisplayOrder())
                        .build(),
                    Collectors.toList()
                )
            ));
    }

    private Pair<List<DeptMonitoringGroup>, Map<Integer, List<MonitoringGroupParamPB>>> getGroupsAndParams(
        Long pid, String deptId, int groupType, String accountId
    ) {
        List<DeptMonitoringGroup> deptGroups = new ArrayList<>();
        Map<Integer, List<MonitoringGroupParamPB>> origDeptGroupParams = new HashMap<>();

        // 返回部门分组及参数
        if (pid == null || pid <= 0) {
            // 如果pid为空，则使用部门分组及其参数
            deptGroups = deptMonitoringGroupRepository.findByDeptIdAndGroupTypeAndIsDeletedFalse(deptId, groupType);
            List<Integer> deptGroupIds = deptGroups.stream()
                .map(DeptMonitoringGroup::getId)
                .toList();

            // 获取部门分组参数
            origDeptGroupParams = getDeptGroupParams(deptGroupIds);
            return new Pair<>(deptGroups, origDeptGroupParams);
        }

        // 计算病人分组及参数
        List<PatientMonitoringGroup> patientGroups = patientMonitoringMetaService
            .findOrInitGroups(pid, deptId, accountId);
        if (patientGroups.isEmpty()) return new Pair<>(deptGroups, origDeptGroupParams);

        // 如果病人已有分组，则获得对应的部门分组；找到病人参数
        List<Integer> allDeptGroupIds = new ArrayList<>();
        for (PatientMonitoringGroup patientGroup : patientGroups) {
            allDeptGroupIds.add(patientGroup.getDeptMonitoringGroupId());
        }
        deptGroups = deptMonitoringGroupRepository.findByIdInAndGroupType(allDeptGroupIds, groupType);  // **
        List<Integer> deptGroupIds = new ArrayList<>();
        Set<Integer> deptGroupIdsSet = new HashSet<>();
        for (DeptMonitoringGroup deptGroup : deptGroups) {
            deptGroupIds.add(deptGroup.getId());
            deptGroupIdsSet.add(deptGroup.getId());
        }
        origDeptGroupParams = getDeptGroupParams(deptGroupIds);  // **

        // 过滤病人分组
        List<Integer> qualifiedPatientGroupIds = new ArrayList<>();
        Map<Integer/*patientGroupId*/, Integer/*deptGroupId*/> groupIdMap = new HashMap<>();
        for (PatientMonitoringGroup patientGroup : patientGroups) {
            Integer deptGroupId = patientGroup.getDeptMonitoringGroupId();
            if (deptGroupIdsSet.contains(deptGroupId)) {
                qualifiedPatientGroupIds.add(patientGroup.getId());
                groupIdMap.put(patientGroup.getId(), deptGroupId);
            }
        }
        Map<Integer, List<MonitoringGroupParamPB>> deptGroupParams = patientMonitoringGroupParamRepository
            .findByPatientMonitoringGroupIdInAndIsDeletedFalse(qualifiedPatientGroupIds)
            .stream()
            .collect(Collectors.groupingBy(patientParam -> {
                Integer patientGroupId = patientParam.getPatientMonitoringGroupId();
                Integer deptGroupId = groupIdMap.get(patientGroupId);
                if (deptGroupId == null) {
                    log.error("Dept group not found for patient group id: {}", patientGroupId);
                    return null;
                }
                return deptGroupId;
            }, Collectors.mapping(
                param -> MonitoringGroupParamPB.newBuilder()
                    .setId(param.getId())
                    .setCode(param.getMonitoringParamCode())
                    .setDisplayOrder(param.getDisplayOrder())
                    .build(),
                Collectors.toList()
            )));  // **
        for (Map.Entry<Integer, List<MonitoringGroupParamPB>> entry : origDeptGroupParams.entrySet()) {
            Integer deptGroupId = entry.getKey();
            if (!deptGroupParams.containsKey(deptGroupId)) {
                deptGroupParams.put(deptGroupId, new ArrayList<>(entry.getValue()));
            }
        }  // **
        return new Pair<>(deptGroups, deptGroupParams);
    }

    private List<MonitoringGroupBetaPB> assembleMonitoringGroupBetaPbs(
        Map<String, MonitoringParam> paramMap,
        List<DeptMonitoringGroup> deptGroups,
        Map<Integer, List<MonitoringGroupParamPB>> deptGroupParams,
        List<String> balanceOutTubeParamCodes,
        String deptId
    ) {
        List<MonitoringGroupBetaPB> monitoringGroups = new ArrayList<>();
        deptGroups.sort(Comparator.comparingInt(DeptMonitoringGroup::getDisplayOrder)
            .thenComparingInt(DeptMonitoringGroup::getId));
        for (DeptMonitoringGroup deptGroup : deptGroups) {
            Integer groupId = deptGroup.getId();
            Boolean isBalanceOutGroup = deptGroup.getGroupType().equals(GROUP_TYPE_BALANCE_ID)
                && deptGroup.getName().equals(OUT_GROUP_NAME);

            // 组装观察项参数
            List<MonitoringParamPB> paramPbList = new ArrayList<>();
            List<MonitoringGroupParamPB> groupParams = deptGroupParams
                .getOrDefault(groupId, new ArrayList<>()).stream()
                .sorted(Comparator
                    .comparingInt(MonitoringGroupParamPB::getDisplayOrder)
                    .thenComparingInt(MonitoringGroupParamPB::getId))
                .toList();
            Integer nextDisplayOrder = 1;
            for (MonitoringGroupParamPB groupParam : groupParams) {
                Integer groupParamId = groupParam.getId();
                String code = groupParam.getCode();
                Integer displayOrder = groupParam.getDisplayOrder();
                MonitoringParam param = paramMap.get(code);
                if (param == null) {
                    log.error("MonitoringParam not found for code: {}, deptId {}", code, deptId);
                    continue;
                }

                ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
                if (valueMeta == null) {
                    log.error("ValueMetaPB not found for code: {}, deptId {}", code, deptId);
                    continue;
                }

                MonitoringParamPB paramPb = fromMonitoringParam(
                    param, groupParamId, valueMeta, displayOrder);
                paramPbList.add(paramPb);

                if (displayOrder >= nextDisplayOrder) {
                    nextDisplayOrder = displayOrder + 1;
                }
            }

            if (isBalanceOutGroup && enableDrainage && balanceOutTubeParamCodes != null) {
                // 如果是平衡量出组，则需要添加引流管道参数
                for (String code : balanceOutTubeParamCodes) {
                    MonitoringParam param = paramMap.get(code);
                    if (param == null) {
                        log.error("MonitoringParam not found for balance out tube code: {}, deptId {}", code, deptId);
                        continue;
                    }
                    ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(param.getTypePb());
                    if (valueMeta == null) {
                        log.error("ValueMetaPB not found for balance out tube code: {}, deptId {}", code, deptId);
                        continue;
                    }
                    MonitoringParamPB paramPb = fromMonitoringParam(
                        param, null/*patientOrDeptParamId*/, valueMeta, nextDisplayOrder++);
                    paramPbList.add(paramPb);
                    if (enableDrainageParams) {
                        for (MonitoringParamPB tubeParam : tubeParamList) {
                            MonitoringParamPB newTubeParam = tubeParam.toBuilder()
                                .setCode(code + "_" + tubeParam.getCode())
                                .build();
                            paramPbList.add(newTubeParam);
                        }
                    }
                }
            }

            MonitoringGroupBetaPB.Builder groupBuilder = MonitoringGroupBetaPB.newBuilder()
                .setId(groupId)
                .setName(deptGroup.getName())
                .setGroupType(deptGroup.getGroupType())
                .setDisplayOrder(deptGroup.getDisplayOrder());
            ValueMetaPB sumValueMeta = ProtoUtils.decodeValueMeta(deptGroup.getSumTypePb());
            if (sumValueMeta != null) groupBuilder.setSumValueMeta(sumValueMeta);
            groupBuilder.addAllParam(paramPbList);
            monitoringGroups.add(groupBuilder.build());
        }

        return monitoringGroups;
    }

    public DeptMonitoringSettingsPB getDeptMonitoringSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_MONITORING_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepository.findById(settingsId).orElse(null);

        DeptMonitoringSettingsPB settingsPb = null;
        if (entity != null) {
            settingsPb = ProtoUtils.decodeDeptMonitoringSettings(entity.getSettingsPb());
        }
        if (settingsPb == null) return DeptMonitoringSettingsPB.newBuilder().build();

        return settingsPb;
    }

    public void setDeptMonitoringSettings(String deptId, DeptMonitoringSettingsPB settingsPb, String modifiedBy) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_MONITORING_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepository.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeDeptMonitoringSettings(settingsPb);

        // 保存到数据库
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingRepository.save(entity);
    }

    private final Integer BALANCE_IN_ID;
    private final Integer BALANCE_OUT_ID;
    private final Integer BALANCE_NET_ID;
    private final Integer GROUP_TYPE_BALANCE_ID;
    private final String OUT_GROUP_NAME;
    private final String DRAINAGE_TUBE_PREFIX;
    private final List<MonitoringParamPB> tubeParamList;
    private final boolean enableDrainage;
    private final boolean enableDrainageParams;

    private final Map<String, MonitoringParamModalOptionsPB> paramModalMap;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final ConfigShiftUtils configShiftUtils;
    private final MonitoringPB monitoringPb;
    private final MonitoringParamRepository monitoringParamRepository;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final DeptMonitoringGroupRepository deptMonitoringGroupRepository;
    private final DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository;
    private final PatientMonitoringMetaService patientMonitoringMetaService;
    private final PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository;
    private final DeptSystemSettingsRepository deptSettingRepository;
}
