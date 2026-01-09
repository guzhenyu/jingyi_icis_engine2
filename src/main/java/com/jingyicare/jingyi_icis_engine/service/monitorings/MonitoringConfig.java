package com.jingyicare.jingyi_icis_engine.service.monitorings;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import ch.qos.logback.classic.LoggerContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.logging.log4j.LogManager;
import org.slf4j.LoggerFactory;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
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
        @Autowired PatientMonitoringGroupRepository patientMonitoringGroupRepository,
        @Autowired PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository,
        @Autowired RbacDepartmentRepository departmentRepository,
        @Autowired BgaParamRepository bgaParamRepository,
        @Autowired DeptSystemSettingsRepository deptSettingRepository,
        @Autowired EntityManager entityManager
    ) {
        this.context = context;
        this.protoService = protoService;
        this.configShiftUtils = configShiftUtils;

        this.monitoringPb = protoService.getConfig().getMonitoring();
        this.BALANCE_IN_ID = monitoringPb.getEnums().getBalanceIn().getId();
        this.BALANCE_OUT_ID = monitoringPb.getEnums().getBalanceOut().getId();
        this.BALANCE_NET_ID = monitoringPb.getEnums().getBalanceNet().getId();
        this.GROUP_TYPE_BALANCE_ID = monitoringPb.getEnums().getGroupTypeBalance().getId();
        this.GROUP_TYPE_MONITORING_ID = monitoringPb.getEnums().getGroupTypeMonitoring().getId();
        this.BALANCE_GROUP_PARAM_TYPES = new HashSet<>(monitoringPb.getBalanceParamTypeList());
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
        this.patientMonitoringGroupRepository = patientMonitoringGroupRepository;
        this.patientMonitoringGroupParamRepository = patientMonitoringGroupParamRepository;
        this.departmentRepository = departmentRepository;
        this.bgaParamRepository = bgaParamRepository;
        this.deptSettingRepository = deptSettingRepository;
        this.entityManager = entityManager;
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

    ////// 系统初始化工具函数: initialize, checkIntegrity, refresh
    @Transactional
    public void initialize() {
        initializeMonitoringParams();
    }

    @Transactional
    private void initializeMonitoringParams() {
        // 检查系统配置参数
        List<MonitoringParamPB> monitoringParamPbs = monitoringPb.getMonitoringParamList();

        // 如果数据库中缺少参数，则插入
        List<MonitoringParam> monitoringParams = monitoringParamRepository.findAll();
        Set<String> existingCodes = new HashSet<>();
        Integer nextDisplayOrder = 1;
        for (MonitoringParam param : monitoringParams) {
            existingCodes.add(param.getCode());
            if (param.getDisplayOrder() >= nextDisplayOrder) {
                nextDisplayOrder = param.getDisplayOrder() + 1;
            }
        }

        for (MonitoringParamPB paramPB : monitoringParamPbs) {
            // 如果参数已存在，则跳过
            if (existingCodes.contains(paramPB.getCode())) continue;

            // 如果参数不存在，则插入
            try {
                insertMonitoringParam(paramPB, nextDisplayOrder++);
            } catch (Exception e) {
                log.error("Failed to process MonitoringParamPB with code: {}", paramPB.getCode(), e);
            }
        }
    }

    private void insertMonitoringParam(MonitoringParamPB paramPB, int displayOrder) {
        String encodedValueMeta = ProtoUtils.encodeValueMeta(paramPB.getValueMeta());

        MonitoringParam param = MonitoringParam.builder()
            .code(paramPB.getCode())
            .name(paramPB.getName())
            .typePb(encodedValueMeta)
            .balanceType(paramPB.getBalanceType())
            .uiModalCode(paramPB.getUiModalCode())
            .displayOrder(displayOrder)
            .build();

        monitoringParamRepository.save(param);
        log.info("Inserted MonitoringParam: {}", paramPB.getCode());
    }

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
        final String code = paramPb.getCode();
        final int balanceType = paramPb.getBalanceType();
        if (balanceType == BALANCE_IN_ID || balanceType == BALANCE_OUT_ID) {
            ValueMetaPB valueMeta = paramPb.getValueMeta();
            TypeEnumPB valueType = valueMeta.getValueType();

            // 平衡量参数不能是多选
            if (valueMeta.getIsMultipleSelection()) {
                log.error("Balance group's MonitoringParamPB with code: {} is multiple_selection.", code);
                return StatusCode.MONITORING_PARAM_MULTI_SELECTION_NOT_ALLOWED;
            }

            // 出入量参数的合法类型
            if (!BALANCE_GROUP_PARAM_TYPES.contains(valueType)) {
                log.error("Balance group's param code {} type is not valid.", code);
                return StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH;
            }
        }
        return StatusCode.OK;
    }

    @Transactional
    public List<BgaParam> getBgaParamList(String deptId) {
        List<BgaParam> params = bgaParamRepository.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
        if (params.isEmpty()) {
            List<BgaParam> defaultParams = new ArrayList<>();
            Integer displayOrder = 1;
            for (String bgaParamCode : protoService.getConfig().getBga().getBgaParamCodeList()) {
                BgaParam param = BgaParam.builder()
                    .deptId(deptId)
                    .monitoringParamCode(bgaParamCode)
                    .displayOrder(displayOrder++)
                    .enabled(false)
                    .isDeleted(false)
                    .modifiedBy("system")
                    .modifiedAt(TimeUtils.getNowUtc())
                    .build();
                defaultParams.add(param);
            }
            bgaParamRepository.saveAll(defaultParams);
            params = bgaParamRepository.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
        }
        return params;
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
        for (int i = 0; i < MAX_RETRIES; i++) {
            try {
                // getpatientmonitoringgroups和getpatientmonitoringrecords会并发执行，同时调用本函数：
                // - 当pid没有初始化时，两次调用会同时初始化，第二次调用的会导致插入失败；
                // - 通过捕获该异常，重新调用，发现pid已经初始化，则第二次调用的会成功
                initDeptMonitoringParams(deptId);
                break; // 成功初始化后跳出循环
            } catch (Exception e) {
                log.error("Failed to initialize dept monitoring params for deptId: {}, attempt: {}", deptId, i + 1, e);
                if (i == MAX_RETRIES - 1) {
                    throw e; // 最后一次尝试仍然失败则抛出异常
                }
            }
        }

        Map<String, MonitoringParam> paramMap = buildMonitoringParamMap(deptId);

        // 获取病人所在的部门观察项分组，以及分组对应的参数
        Pair<List<DeptMonitoringGroup>, Map<Integer, List<MonitoringGroupParamPB>>> pair = null;
        for (int i = 0; i < MAX_RETRIES; i++) {
            // getpatientmonitoringgroups和getpatientmonitoringrecords会并发执行，同时调用本函数：
            // - 当pid没有初始化时，两次调用会同时初始化，第二次调用的会导致插入失败；
            // - 通过捕获该异常，重新调用，发现pid已经初始化，则第二次调用的会成功
            try {
                pair = getGroupsAndParams(pid, deptId, groupType, accountId);
                break; // 成功获取后跳出循环
            } catch (Exception e) {
                log.error("Failed to get groups and params for pid: {}, deptId: {}, groupType: {}, attempt: {}", pid, deptId, groupType, i + 1, e);
                if (i == MAX_RETRIES - 1) {
                    throw e; // 最后一次尝试仍然失败则抛出异常
                }
            }
        }
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
            param.setTypePb(deptParam.getTypePb());
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

    @Transactional(isolation = Isolation.SERIALIZABLE)
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
        List<PatientMonitoringGroup> patientGroups = patientMonitoringGroupRepository
            .findByPidAndIsDeletedFalse(pid);
        if (patientGroups.isEmpty()) {
            // 初始化病人分组
            List<Integer> deptGroupIds = new ArrayList<>();
            LocalDateTime now = TimeUtils.getNowUtc();
            for (DeptMonitoringGroup deptGroup : deptMonitoringGroupRepository.findByDeptIdAndIsDeletedFalse(deptId)) {
                patientGroups.add(PatientMonitoringGroup.builder()
                    .deptMonitoringGroupId(deptGroup.getId())
                    .pid(pid)
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedAt(now)
                    .build());
                if (deptGroup.getGroupType() == groupType) {
                    deptGroups.add(deptGroup);  // **
                    deptGroupIds.add(deptGroup.getId());
                }
            }
            patientGroups = patientMonitoringGroupRepository.saveAll(patientGroups);

            // 获取部门分组参数 origDeptGroupParams
            origDeptGroupParams = getDeptGroupParams(deptGroupIds);  // **

            return new Pair<>(deptGroups, origDeptGroupParams);
        }

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

            if (isBalanceOutGroup && balanceOutTubeParamCodes != null) {
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
                    for (MonitoringParamPB tubeParam : tubeParamList) {
                        MonitoringParamPB newTubeParam = tubeParam.toBuilder()
                            .setCode(code + "_" + tubeParam.getCode())
                            .build();
                        paramPbList.add(newTubeParam);
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

    @Transactional
    public List<DeptMonitoringGroup> resetDeptMonitoringGroups(String deptId, String accountId) {
        List<DeptMonitoringGroup> existingGroups = deptMonitoringGroupRepository.findByDeptIdAndIsDeletedFalse(deptId);
        if (existingGroups.isEmpty()) {
            log.info("No existing monitoring groups found for department {}, initializing...", deptId);
            initDeptMonitoringParams(deptId);
            return existingGroups;
        }

        // 检查分组是否有患者在使用：如果没有患者在使用，则不需要重置；如果有患者在使用，由于患者在使用的分组不允许修改，因此需要重置部门分组。
        List<Integer> deptGroupIds = existingGroups.stream().map(DeptMonitoringGroup::getId).toList();
        List<PatientMonitoringGroup> patientGroups = patientMonitoringGroupRepository
            .findByDeptMonitoringGroupIdInAndIsDeletedFalse(deptGroupIds);
        if (patientGroups.isEmpty()) return existingGroups;

        // 复制现有的部门监控组
        List<DeptMonitoringGroup> copiedGroups = new ArrayList<>();
        LocalDateTime now = TimeUtils.getNowUtc();
        for (DeptMonitoringGroup existingGroup : existingGroups) {
            DeptMonitoringGroup copiedGroup = existingGroup
                .toBuilder()
                .id(null)
                .modifiedBy(accountId)
                .modifiedAt(now)
                .build();
            copiedGroups.add(copiedGroup);
        }

        // 复制现有的部门监控组参数
        List<DeptMonitoringGroupParam> existingGroupParams = deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdInAndIsDeletedFalse(deptGroupIds);

        // 删除现有的部门监控组
        for (DeptMonitoringGroup existingGroup : existingGroups) {
            existingGroup.setIsDeleted(true);
            existingGroup.setDeletedBy(accountId);
            existingGroup.setDeletedAt(now);
        }
        deptMonitoringGroupRepository.saveAll(existingGroups);
        entityManager.flush(); // 确保删除操作生效
        Map<String, Integer> existingGroupIdMap = existingGroups.stream()
            .collect(Collectors.toMap(DeptMonitoringGroup::getName, DeptMonitoringGroup::getId));

        // 保存复制的检查部门分组；生成旧部门监控组id到新部门监控组id的映射
        copiedGroups = deptMonitoringGroupRepository.saveAll(copiedGroups);
        entityManager.flush(); // 确保保存操作生效
        Map<Integer, Integer> oldNewGroupIdMap = new HashMap<>();
        for (DeptMonitoringGroup copiedGroup : copiedGroups) {
            String groupName = copiedGroup.getName();
            Integer existingGroupId = existingGroupIdMap.get(groupName);
            if (existingGroupId == null) {
                log.error("No existing group found for name: {}", groupName);
                continue;
            }
            Integer newGroupId = copiedGroup.getId();
            oldNewGroupIdMap.put(existingGroupId, newGroupId);
        }

        // 更新部门监控组参数
        List<DeptMonitoringGroupParam> copiedGroupParams = new ArrayList<>();
        for (DeptMonitoringGroupParam existingParam : existingGroupParams) {
            Integer newDeptGroupId = oldNewGroupIdMap.get(existingParam.getDeptMonitoringGroupId());
            if (newDeptGroupId == null) {
                log.error("No new group found for old group id: {}", existingParam.getDeptMonitoringGroupId());
                continue;
            }
            DeptMonitoringGroupParam copiedParam = existingParam
                .toBuilder()
                .id(null)
                .deptMonitoringGroupId(newDeptGroupId)
                .modifiedBy(accountId)
                .modifiedAt(now)
                .build();
            copiedGroupParams.add(copiedParam);
        }
        deptMonitoringGroupParamRepository.saveAll(copiedGroupParams);

        return copiedGroups;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void initDeptMonitoringParams(String deptId) {
        List<DeptMonitoringGroup> existingGroups = deptMonitoringGroupRepository.findByDeptIdAndIsDeletedFalse(deptId);
        if (!existingGroups.isEmpty()) return;

        initDeptMonitoringParamsImpl(deptId);
    }

    public void initDeptMonitoringParamsImpl(String deptId) {
        List<MonitoringGroupPB> balanceGroups = monitoringPb.getBalanceGroupList();
        List<MonitoringGroupPB> monitoringGroups = monitoringPb.getMonitoringGroupList();
        insertDeptGroups(deptId, balanceGroups, GROUP_TYPE_BALANCE_ID);
        insertDeptGroups(deptId, monitoringGroups, GROUP_TYPE_MONITORING_ID);

        log.info("Department {} monitoring groups initialization completed.", deptId);
    }

    private void insertDeptGroups(String deptId, List<MonitoringGroupPB> groupPBs, int groupType) {
        LocalDateTime now = TimeUtils.getNowUtc();
        for (int i = 0; i < groupPBs.size(); i++) {
            MonitoringGroupPB groupPB = groupPBs.get(i);

            DeptMonitoringGroup group = DeptMonitoringGroup.builder()
                .deptId(deptId)
                .name(groupPB.getName())
                .groupType(groupType)
                .displayOrder(i)
                .sumTypePb(ProtoUtils.encodeValueMeta(groupPB.getSumValueMeta()))
                .isDeleted(false)
                .modifiedBy("system")
                .modifiedAt(now)
                .build();

            DeptMonitoringGroup savedGroup = deptMonitoringGroupRepository.save(group);
            log.info("Initialized dept_monitoring_group: deptId={}, groupName={}, groupType={}",
                deptId, groupPB.getName(), groupType);

            insertDeptGroupParams(savedGroup.getId(), groupPB.getMonitoringParamList(), now);
        }
    }

    private void insertDeptGroupParams(Integer groupId, List<MonitoringGroupParamPB> paramPBs, LocalDateTime now) {
        for (int index = 0; index < paramPBs.size(); index++) {
            MonitoringGroupParamPB paramPB = paramPBs.get(index);

            DeptMonitoringGroupParam param = DeptMonitoringGroupParam.builder()
                .deptMonitoringGroupId(groupId)
                .monitoringParamCode(paramPB.getCode())
                .displayOrder(index + 1)  // 1-based
                .isDeleted(false)
                .modifiedBy("system")
                .modifiedAt(now)
                .build();

            deptMonitoringGroupParamRepository.save(param);

            log.info("Initialized dept_monitoring_group_param: groupId={}, paramCode={}, displayOrder={}",
                groupId, paramPB.getCode(), index + 1);
        }
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

    private static Integer MAX_RETRIES = 6;

    private final Integer BALANCE_IN_ID;
    private final Integer BALANCE_OUT_ID;
    private final Integer BALANCE_NET_ID;
    private final Integer GROUP_TYPE_BALANCE_ID;
    private final Integer GROUP_TYPE_MONITORING_ID;
    private final Set<TypeEnumPB> BALANCE_GROUP_PARAM_TYPES;
    private final String OUT_GROUP_NAME;
    private final String DRAINAGE_TUBE_PREFIX;
    private final List<MonitoringParamPB> tubeParamList;

    private final Map<String, MonitoringParamModalOptionsPB> paramModalMap;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final ConfigShiftUtils configShiftUtils;
    private final MonitoringPB monitoringPb;
    private final MonitoringParamRepository monitoringParamRepository;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final DeptMonitoringGroupRepository deptMonitoringGroupRepository;
    private final DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository;
    private final PatientMonitoringGroupRepository patientMonitoringGroupRepository;
    private final PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository;
    private final RbacDepartmentRepository departmentRepository;
    private final BgaParamRepository bgaParamRepository;
    private final DeptSystemSettingsRepository deptSettingRepository;
    private final EntityManager entityManager;
}
