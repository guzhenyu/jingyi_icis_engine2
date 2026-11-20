package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MonitoringService {
    public MonitoringService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserQuery userQuery,
        @Autowired PatientService patientService,
        @Autowired PatientReader patientReader,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringMetaService monitoringMetaService,
        @Autowired PatientMonitoringMetaService patientMonitoringMetaService,
        @Autowired LockExecutor lockExecutor,
        @Autowired JdbcTemplate jdbcTemplate,
        @Autowired MonitoringParamRepository monitoringParamRepository,
        @Autowired DeptMonitoringParamRepository deptMonitoringParamRepository,
        @Autowired MonitoringParamHistoryRepository monitoringParamHistoryRepository,
        @Autowired DeptMonitoringGroupRepository deptMonitoringGroupRepository,
        @Autowired DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository,
        @Autowired PatientMonitoringGroupRepository patientMonitoringGroupRepository,
        @Autowired PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.protoService = protoService;

        this.userQuery = userQuery;
        this.patientService = patientService;
        this.patientReader = patientReader;
        this.patientTubeImpl = patientTubeImpl;
        this.monitoringConfig = monitoringConfig;
        this.monitoringMetaService = monitoringMetaService;
        this.patientMonitoringMetaService = patientMonitoringMetaService;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;

        this.monitoringParamRepository = monitoringParamRepository;
        this.deptMonitoringParamRepository = deptMonitoringParamRepository;
        this.monitoringParamHistoryRepository = monitoringParamHistoryRepository;
        this.deptMonitoringGroupRepository = deptMonitoringGroupRepository;
        this.deptMonitoringGroupParamRepository = deptMonitoringGroupParamRepository;
        this.patientMonitoringGroupRepository = patientMonitoringGroupRepository;
        this.patientMonitoringGroupParamRepository = patientMonitoringGroupParamRepository;
    }

    public GetMonitoringParamsResp getMonitoringParams(String getMonitoringParamsReqJson) {
        final GetMonitoringParamsReq req;

        // Step 1: Parse the JSON string into the GetMonitoringParamsReq proto
        try {
            req = ProtoUtils.parseJsonToProto(getMonitoringParamsReqJson, GetMonitoringParamsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetMonitoringParamsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Step 2: Fetch params from monitoring_params
        List<MonitoringParam> paramsSet1 = monitoringParamRepository.findAll();

        String departmentId = req.getDepartmentId();

        // Step 3: Handle empty department_id
        if (departmentId == null || departmentId.isBlank()) {
            log.info("No department_id provided. Returning global monitoring_params.");
            List<MonitoringParamPB> responseParams = paramsSet1.stream()
                .sorted(Comparator.comparing(MonitoringParam::getDisplayOrder))
                .map(param -> MonitoringParamPB.newBuilder()
                    .setCode(param.getCode())
                    .setName(param.getName())
                    .setValueMeta(ProtoUtils.decodeValueMeta(param.getTypePb()))
                    .setBalanceType(param.getBalanceType())
                    .build())
                .toList();
            return GetMonitoringParamsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .addAllParams(responseParams)
                .build();
        }

        // Step 4: Fetch params from dept_monitoring_params for the specified department
        List<DeptMonitoringParam> paramsSet2 = deptMonitoringParamRepository.findByIdDeptId(departmentId);
        Map<String, String> deptTypePbMap = paramsSet2.stream().collect(
            Collectors.toMap(
                param -> param.getId().getCode(), // 获取嵌套主键中的 code
                DeptMonitoringParam::getTypePb // 获取 typePb
            )
        );

        // Step 5: Construct the response params
        List<MonitoringParamPB> responseParams = paramsSet1.stream()
            .map(param -> {
                String code = param.getCode();
                ValueMetaPB valueMetaPb = ProtoUtils.decodeValueMeta(
                    deptTypePbMap.getOrDefault(code, param.getTypePb())
                );
                return MonitoringConfig.fromMonitoringParam(param, null, valueMetaPb, null);
            })
            .toList();

        // Step 6: Return the constructed response
        return GetMonitoringParamsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllParams(responseParams)
            .build();
    }

    public GetMonitoringParamResp getMonitoringParam(String getMonitoringParamReqJson) {
        final GetMonitoringParamReq req;

        // 解析 JSON 字符串
        try {
            req = ProtoUtils.parseJsonToProto(getMonitoringParamReqJson, GetMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetMonitoringParamResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取原始参数信息
        String code = req.getParamCode();
        MonitoringParam monitoringParam = monitoringParamRepository.findByCode(code).orElse(null);
        if (monitoringParam == null) {
            log.warn("MonitoringParam with code '{}' not found.", code);
            return GetMonitoringParamResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST))
                .build();
        }

        // 查找部门对该参数的特殊化信息
        final String uiModalCode = monitoringParam.getUiModalCode() == null ? "" : monitoringParam.getUiModalCode();
        List<DeptMonitoringParamPB> deptMonitoringParamPBs = deptMonitoringParamRepository
            .findByIdCode(code).stream().map(param -> DeptMonitoringParamPB.newBuilder()
                .setDeptId(param.getId().getDeptId())
                .setParam(MonitoringParamPB.newBuilder()
                    .setCode(param.getId().getCode())
                    .setName(monitoringParam.getName())
                    .setValueMeta(ProtoUtils.decodeValueMeta(param.getTypePb()))
                    .setBalanceType(monitoringParam.getBalanceType())
                    .setCategory(monitoringParam.getCategory())
                    .setUiModalCode(uiModalCode)
                    .setChartSign(monitoringParam.getChartSign())
                    .build())
                .build())
            .toList();

        // 查找模态对话框选项
        MonitoringParamModalOptionsPB paramModalOptions = monitoringConfig.getParamModalOptions(code);
        List<MonitoringModalOptionPB> modalOptions = paramModalOptions != null ?
            paramModalOptions.getOptionList() : new ArrayList<>();

        // 构建返回结果
        return GetMonitoringParamResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setParam(MonitoringParamPB.newBuilder()
                .setCode(monitoringParam.getCode())
                .setName(monitoringParam.getName())
                .setNameEn(monitoringParam.getNameEn())
                .setValueMeta(ProtoUtils.decodeValueMeta(monitoringParam.getTypePb()))
                .setBalanceType(monitoringParam.getBalanceType())
                .setCategory(monitoringParam.getCategory())
                .setUiModalCode(uiModalCode)
                .setChartSign(monitoringParam.getChartSign())
                .build())
            .addAllDeptParam(deptMonitoringParamPBs)
            .addAllModalOption(modalOptions)
            .build();
    }

    public GenericResp updateDeptMonitoringParam(String updateDeptMonitoringParamReqJson) {
        final UpdateDeptMonitoringParamReq req;

        try {
            req = ProtoUtils.parseJsonToProto(
                updateDeptMonitoringParamReqJson, UpdateDeptMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String departmentId = req.getDepartmentId();
        final String code = req.getCode();
        final ValueMetaPB valueMeta = req.getValueMeta();

        if (departmentId.isBlank()) {
            log.warn("Invalid request: departmentId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY))
                .build();
        }

        if (code.isBlank()) {
            log.warn("Invalid request: code is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MONITORING_PARAM_CODE_EMPTY))
                .build();
        }

        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        String newTypePb = ProtoUtils.encodeValueMeta(valueMeta);
        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroups(departmentId)), () -> {
            // 共享锁只保护当前全局参数行的校验依据，各科室可同时读取同一参数。
            MonitoringParam monitoringParam = monitoringParamRepository
                .findForShareByCode(code).orElse(null);
            if (monitoringParam == null) {
                log.warn("MonitoringParam with code: {} does not exist. User: {}",
                    code, account.autoAcctId());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST))
                    .build();
            }

            MonitoringParamPB monitoringParamPb = MonitoringParamPB.newBuilder()
                .setCode(code)
                .setName(monitoringParam.getName())
                .setValueMeta(valueMeta)
                .setBalanceType(monitoringParam.getBalanceType())
                .build();
            StatusCode statusCode = monitoringConfig.isValidParamPb(monitoringParamPb);
            if (statusCode != StatusCode.OK) {
                log.warn("Invalid MonitoringParamPB {} in request. User: {}",
                    monitoringParamPb, account.autoAcctId());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(statusCode))
                    .build();
            }

            DeptMonitoringParam existingParam = deptMonitoringParamRepository
                .findByIdDeptIdAndIdCode(departmentId, code).orElse(null);
            if (existingParam == null) {
                DeptMonitoringParam newParam = DeptMonitoringParam.builder()
                    .id(new DeptMonitoringParamId(code, departmentId))
                    .typePb(newTypePb)
                    .build();
                deptMonitoringParamRepository.save(newParam);
                log.info("Inserted DeptMonitoringParam for code: {}, departmentId: {}. User: {}",
                    code, departmentId, account.autoAcctId());
            } else if (!existingParam.getTypePb().equals(newTypePb)) {
                saveDeptParamToHistory(
                    existingParam, "Updated by user request", account.autoAcctId(), false);
                existingParam.setTypePb(newTypePb);
                deptMonitoringParamRepository.save(existingParam);
                log.info("Updated DeptMonitoringParam for code: {}, departmentId: {}. User: {}",
                    code, departmentId, account.autoAcctId());
            } else {
                log.info("No update needed for DeptMonitoringParam for code: {}, departmentId: {}. "
                    + "User: {}",
                    code, departmentId, account.autoAcctId());
            }

            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        });
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public GetDeptMonitoringGroupsResp getDeptMonitoringGroups(String getDeptMonitoringGroupsReqJson) {
        final GetDeptMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getDeptMonitoringGroupsReqJson, GetDeptMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse GetDeptMonitoringGroupsReq JSON: {}", e.getMessage());
            return GetDeptMonitoringGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while adding dept monitoring group.");
            return GetDeptMonitoringGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }

        return GetDeptMonitoringGroupsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMonitoringGroup(monitoringConfig.getMonitoringGroups(
                null, req.getDepartmentId(), req.getGroupType(), null, account.autoAcctId()
            ))
            .build();
    }

    public AddDeptMonitoringGroupResp addDeptMonitoringGroup(String addDeptMonitoringGroupReqJson) {
        final AddDeptMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addDeptMonitoringGroupReqJson, AddDeptMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse AddDeptMonitoringGroupReq JSON: {}", e.getMessage());
            return AddDeptMonitoringGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户 ID
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while adding dept monitoring group.");
            return AddDeptMonitoringGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        log.info("User [{}] is adding a department monitoring group: {}",
            account.autoAcctId(), req);

        // 提取关键参数
        String deptId = req.getDepartmentId();
        if (deptId.isBlank()) {
            return AddDeptMonitoringGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY)).build();
        }
        String groupName = req.getName();
        Integer groupType = req.getGroupType();

        // 检查 group_type 是否为不可编辑的平衡分组
        if (groupType == MonEnums.GROUP_TYPE_BALANCE) {
            log.error("Group type 'balance' is not editable. User: [{}], Request: {}",
                account.autoAcctId(), req);
            return AddDeptMonitoringGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE))
                    .build();
        }

        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroups(deptId)), () -> {
            // 如有必要，初始化/重置部门分组
            monitoringMetaService.resetDeptMonitoringGroups(deptId, account.autoAcctId());

            // 新增分组
            Pair<StatusCode, Integer> result = addDeptMonitoringGroupImpl(
                deptId, groupName, groupType, req.getSumValueMeta(), account.autoAcctId()
            );
            StatusCode statusCode = result.getFirst();

            AddDeptMonitoringGroupResp.Builder resp = AddDeptMonitoringGroupResp.newBuilder();
            resp.setRt(protoService.getReturnCode(statusCode));
            if (statusCode == StatusCode.OK) resp.setId(result.getSecond());
            return resp.build();
        });
    }

    private Pair<StatusCode, Integer> addDeptMonitoringGroupImpl(
        String deptId, String groupName, Integer groupType, ValueMetaPB sumMeta, String accountId
    ) {
        // 检查是否已存在相同的 deptId 和 name 的监控分组
        boolean groupExists = deptMonitoringGroupRepository
            .findByDeptIdAndNameAndIsDeletedFalse(deptId, groupName).isPresent();
        if (groupExists) {
            log.error("Monitoring group already exists for deptId: {}, name: {}. User: [{}]",
                deptId, groupName, accountId);
            return new Pair<>(StatusCode.MONITORING_GROUP_EXIST, null);
        }

        // 获取当前最大的 displayOrder
        Integer displayOrder = 1;
        for (DeptMonitoringGroup group : deptMonitoringGroupRepository
            .findByDeptIdAndIsDeletedFalse(deptId)) {
            if (group.getDisplayOrder() >= displayOrder) {
                displayOrder = group.getDisplayOrder() + 1;
            }
        }

        // 构建新的 DeptMonitoringGroup
        DeptMonitoringGroup newGroup = DeptMonitoringGroup.builder()
                .deptId(deptId)
                .name(groupName)
                .groupType(groupType)
                .displayOrder(displayOrder)
                .sumTypePb(ProtoUtils.encodeValueMeta(sumMeta))
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(TimeUtils.getNowUtc())
                .build();

        // 保存到数据库
        newGroup = deptMonitoringGroupRepository.save(newGroup);

        log.info("User [{}] successfully added department monitoring group: {}, ID: {}",
            accountId, groupName, newGroup.getId());
        return new Pair<>(StatusCode.OK, newGroup.getId());
    }

    public GenericResp updateDeptMonGroupName(String updateDeptMonGroupNameReqJson) {
        final UpdateDeptMonGroupNameReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                updateDeptMonGroupNameReqJson, UpdateDeptMonGroupNameReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户 ID
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while updating monitoring group name.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查请求参数
        if (req.getName().isBlank()) {
            log.error("Monitoring group name is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_MONITORING_GROUP_NAME_EMPTY))
                    .build();
        }

        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, req.getId()),
            deptId -> List.of(LockKeys.deptMonitoringGroups(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, req.getId())),
            deptId -> {
                DeptMonitoringGroup existingGroup = deptMonitoringGroupRepository
                    .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
                if (existingGroup == null) {
                    log.error("Monitoring group not found for ID: {}", req.getId());
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(
                            StatusCode.DEPT_MONITORING_GROUP_NOT_EXIST))
                        .build();
                }
                if (Objects.equals(existingGroup.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(
                            StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE))
                        .build();
                }

                DeptMonitoringGroup dupDeptMonGroup = deptMonitoringGroupRepository
                    .findByDeptIdAndNameAndIsDeletedFalse(existingGroup.getDeptId(), req.getName())
                    .orElse(null);
                if (dupDeptMonGroup != null && !dupDeptMonGroup.getId().equals(req.getId())) {
                    log.warn("Duplicate monitoring group name '{}' found in department ID: {}",
                        req.getName(), req.getId());
                    return GenericResp.newBuilder()
                            .setRt(protoService.getReturnCode(
                                StatusCode.DEPT_MONITORING_GROUP_NAME_DUPLICATE))
                            .build();
                }

                // 更新监控分组名称
                log.info("User [{}] is updating monitoring group name to '{}' for ID: {}",
                    account.autoAcctId(), req.getName(), req.getId());
                existingGroup.setName(req.getName());
                existingGroup.setModifiedBy(account.autoAcctId());
                existingGroup.setModifiedAt(TimeUtils.getNowUtc());
                deptMonitoringGroupRepository.save(existingGroup);

                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
            }, GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_MONITORING_GROUP_NOT_EXIST))
                .build());
    }

    public GenericResp deleteDeptMonitoringGroup(String deleteDeptMonitoringGroupReqJson) {
        final DeleteDeptMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                deleteDeptMonitoringGroupReqJson, DeleteDeptMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse DeleteDeptMonitoringGroupReq JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户 ID
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while deleting department monitoring group.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        log.info("User [{}] is deleting department monitoring group: {}",
            account.autoAcctId(), req);

        // 提取关键参数
        Integer groupId = req.getId();

        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId),
            deptId -> List.of(LockKeys.deptMonitoringGroups(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId)),
            deptId -> {
                // 检查分组是否可删除
                Pair<StatusCode, DeptMonitoringGroup> deletableCheck =
                    deptGroupDeletable(groupId, account.autoAcctId());
                StatusCode statusCode = deletableCheck.getFirst();
                if (statusCode != StatusCode.OK) {
                    log.error("Group deletion check failed: {}", statusCode);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(statusCode))
                        .build();
                }
                if (deletableCheck.getSecond() == null) {
                    log.error("Group with ID {} is not found. User: [{}]",
                        groupId, account.autoAcctId());
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(statusCode))
                        .build();
                }
                DeptMonitoringGroup groupToDel = deletableCheck.getSecond();
                String groupNameToDel = groupToDel.getName();

                // 必要时重置部门分组
                monitoringMetaService.resetDeptMonitoringGroups(deptId, account.autoAcctId());

                // 执行删除操作
                deleteDeptMonitoringGroupImpl(deptId, groupNameToDel, account.autoAcctId());

                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
            }, GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK)).build());
    }

    private Pair<StatusCode, DeptMonitoringGroup> deptGroupDeletable(
        Integer groupId, String accountId
    ) {
        // 检查 group_type 是否为不可编辑的平衡分组
        DeptMonitoringGroup group = deptMonitoringGroupRepository
            .findByIdAndIsDeletedFalse(groupId).orElse(null);
        if (group == null) {
            log.info("No active department monitoring group found for id: {}. User: [{}]",
                groupId, accountId);
            return new Pair<>(StatusCode.OK, null);
        }

        if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
            log.error("Group type 'balance' is not editable. Group ID: {}, User: [{}]",
                groupId, accountId);
            return new Pair<>(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE, null);
        }

        return new Pair<>(StatusCode.OK, group);
    }

    private void deleteDeptMonitoringGroupImpl(
        String deptId, String groupNameToDel, String accountId
    ) {
        DeptMonitoringGroup group = deptMonitoringGroupRepository
            .findByDeptIdAndNameAndIsDeletedFalse(deptId, groupNameToDel).orElse(null);
        if (group == null) {
            log.info("未找到有效科室分组，科室 {}，分组 {}，用户 {}",
                deptId, groupNameToDel, accountId);
            return;
        }

        // 设置 is_deleted, deleted_by, deleted_at
        group.setIsDeleted(true);
        group.setDeletedBy(accountId);
        group.setDeletedAt(TimeUtils.getNowUtc());
        deptMonitoringGroupRepository.save(group);
        log.info("用户 [{}] 已删除科室分组，科室 {}，分组 {}",
            accountId, deptId, groupNameToDel);
    }

    public GenericResp reorderDeptMonitoringGroup(String reorderDeptMonitoringGroupReqJson) {
        final ReorderDeptMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reorderDeptMonitoringGroupReqJson, ReorderDeptMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse ReorderDeptMonitoringGroupReq JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户 ID
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while reordering department monitoring groups.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        log.info("User [{}] is reordering department monitoring groups: {}",
            account.autoAcctId(), req);

        // 校验请求参数
        if (req.getDepartmentId().isBlank()) {
            log.error("Department ID is empty. User: [{}]", account.autoAcctId());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY))
                    .build();
        }
        if (req.getGroupOrderList().isEmpty()) {
            log.error("Group order list is empty. User: [{}]", account.autoAcctId());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.MONITORING_GROUP_LIST_EMPTY))
                    .build();
        }

        try {
            return lockExecutor.execute(
                List.of(LockKeys.deptMonitoringGroups(req.getDepartmentId())), () -> {
                // 检查 department_id 是否存在
                boolean deptExists = deptMonitoringGroupRepository
                    .existsByDeptIdAndIsDeletedFalse(req.getDepartmentId());
                if (!deptExists) {
                    log.error("Invalid department ID [{}]. User: [{}]",
                        req.getDepartmentId(), account.autoAcctId());
                    return GenericResp.newBuilder()
                            .setRt(protoService.getReturnCode(
                                StatusCode.MONITORING_GROUP_INVALID_DEPARTMENT))
                            .build();
                }

                // 获取数据库中现有的分组
                List<DeptMonitoringGroup> groups = deptMonitoringGroupRepository
                    .findByDeptIdAndIsDeletedFalse(req.getDepartmentId());

                Map<Integer, DeptMonitoringGroup> groupMap = groups.stream()
                        .collect(Collectors.toMap(DeptMonitoringGroup::getId, group -> group));

                // 校验传入的分组 ID 是否全部有效
                for (MonitoringGroupOrder order : req.getGroupOrderList()) {
                    if (!groupMap.containsKey(order.getMonitoringGroupId())) {
                        log.error("Invalid monitoring group ID [{}] in reorder request. User: [{}]",
                            order.getMonitoringGroupId(), account.autoAcctId());
                        return GenericResp.newBuilder()
                                .setRt(protoService.getReturnCode(
                                    StatusCode.MONITORING_GROUP_NOT_EXIST))
                                .build();
                    }

                    DeptMonitoringGroup group = groupMap.get(order.getMonitoringGroupId());
                    if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
                        log.error("Group type 'balance' is not editable. Group ID: {}, User: [{}]",
                            group.getId(), account.autoAcctId());
                        return GenericResp.newBuilder()
                                .setRt(protoService.getReturnCode(
                                    StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE))
                                .build();
                    }
                }

                // 更新显示顺序，仅更新有变化的分组
                List<DeptMonitoringGroup> updatedGroups = new ArrayList<>();
                for (MonitoringGroupOrder order : req.getGroupOrderList()) {
                    DeptMonitoringGroup group = groupMap.get(order.getMonitoringGroupId());
                    if (group.getDisplayOrder() != order.getDisplayOrder()) {
                        group.setDisplayOrder(order.getDisplayOrder());
                        group.setModifiedBy(account.autoAcctId());
                        group.setModifiedAt(TimeUtils.getNowUtc());
                        updatedGroups.add(group);
                    }
                }

                // 批量保存更新后的分组
                if (!updatedGroups.isEmpty()) {
                    deptMonitoringGroupRepository.saveAll(updatedGroups);
                    log.info("Updated display orders for {} groups in department: {}. User: [{}]",
                        updatedGroups.size(), req.getDepartmentId(), account.autoAcctId());
                } else {
                    log.info("No changes in display order for department: {}. User: [{}]",
                        req.getDepartmentId(), account.autoAcctId());
                }

                return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.OK))
                        .build();
            });
        } catch (Exception e) {
            log.error("Failed to reorder department monitoring groups. User: [{}], Request: {}, "
                + "Error: {}",
                account.autoAcctId(), req, e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION))
                    .build();
        }
    }

    public AddDeptMonitoringGroupParamResp addDeptMonitoringGroupParam(
        String addDeptMonitoringGroupParamReqJson
    ) {
        // 解析请求
        AddDeptMonitoringGroupParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addDeptMonitoringGroupParamReqJson, AddDeptMonitoringGroupParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return AddDeptMonitoringGroupParamResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 查找用户信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("AccountId is empty.");
            return AddDeptMonitoringGroupParamResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }

        // 提取关键参数
        String deptId = req.getDeptId();
        if (deptId.isBlank()) {
            return AddDeptMonitoringGroupParamResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY)).build();
        }
        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroups(deptId)), () -> {
            Integer groupId = req.getDeptMonitoringGroupId();
            String code = req.getCode();

            // 检查分组和参数是否可以添加
            Pair<StatusCode, DeptMonitoringGroup> groupCheck =
                isDeptMonitoringGroupParamAddable(deptId, groupId, code);
            StatusCode statusCode = groupCheck.getFirst();
            if (statusCode != StatusCode.OK) {
                log.error("Failed to add monitoring group parameter: {}", statusCode);
                return AddDeptMonitoringGroupParamResp.newBuilder()
                    .setRt(protoService.getReturnCode(statusCode))
                    .build();
            }
            String groupName = groupCheck.getSecond().getName();

            // 如有必要, 初始化/重置部门分组
            List<DeptMonitoringGroup> latestGroups =
                monitoringMetaService.resetDeptMonitoringGroups(deptId, account.autoAcctId());
            for (DeptMonitoringGroup group : latestGroups) {
                if (groupName.equals(group.getName())) {
                    groupId = group.getId();
                    break;
                }
            }

            // 添加分组参数
            Pair<StatusCode, Integer/*paramId*/> addResult =
                addDeptMonitoringGroupParamImpl(deptId, groupId, code, account.autoAcctId());
            statusCode = addResult.getFirst();

            AddDeptMonitoringGroupParamResp.Builder resp =
                AddDeptMonitoringGroupParamResp.newBuilder()
                    .setRt(protoService.getReturnCode(statusCode));
            if (statusCode == StatusCode.OK) resp.setId(addResult.getSecond());
            return resp.build();
        });
    }

    private Pair<StatusCode, DeptMonitoringGroup> isDeptMonitoringGroupParamAddable(
        String deptId, Integer groupId, String code
    ) {
        // 检查对应分组是否存在
        DeptMonitoringGroup deptGroup = deptMonitoringGroupRepository
            .findByIdAndDeptId(groupId, deptId).orElse(null);
        if (deptGroup == null) {
            log.error("Monitoring group not found for ID: {}", groupId);
            return new Pair<>(StatusCode.MONITORING_GROUP_NOT_EXIST, null);
        }

        // 检查对应的参数是否存在
        MonitoringParam param = monitoringParamRepository.findByCode(code).orElse(null);
        if (param == null) {
            log.error("Monitoring parameter not found for code: {}", code);
            return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);
        }

        // 检查分组内是否有对应的参数
        boolean paramExists = deptMonitoringGroupParamRepository
            .existsByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(groupId, code);
        if (paramExists) {
            log.error("Monitoring group parameter already exists for group ID: {}, code: {}",
                groupId, code);
            return new Pair<>(StatusCode.MONITORING_GROUP_PARAM_EXIST, deptGroup);
        }

        // 检查参数的出入量类型是否与出入量分组类型相匹配
        if (Objects.equals(deptGroup.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
            Integer role = deptGroup.getBalanceRole();
            boolean typeMismatch =
                (Objects.equals(role, MonEnums.BALANCE_TYPE_IN)
                    && Objects.equals(param.getBalanceType(), MonEnums.BALANCE_TYPE_OUT))
                || (Objects.equals(role, MonEnums.BALANCE_TYPE_OUT)
                    && Objects.equals(param.getBalanceType(), MonEnums.BALANCE_TYPE_IN));
            if (typeMismatch) {
                log.error("出入量类型不匹配，分组 ID：{}，参数：{}", groupId, code);
                return new Pair<>(StatusCode.GROUP_PARAM_BALANCE_NOT_MATCH, deptGroup);
            }
        }

        return new Pair<>(StatusCode.OK, deptGroup);
    }

    private Pair<StatusCode, Integer/*paramId*/> addDeptMonitoringGroupParamImpl(
        String deptId, Integer groupId, String code, String accountId
    ) {
        Pair<StatusCode, DeptMonitoringGroup> groupCheck =
            isDeptMonitoringGroupParamAddable(deptId, groupId, code);
        StatusCode statusCode = groupCheck.getFirst();
        if (statusCode != StatusCode.OK) return new Pair<>(statusCode, null);

        Integer displayOrder = 1;
        for (DeptMonitoringGroupParam param : deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdAndIsDeletedFalse(groupId)
        ) {
            if (param.getDisplayOrder() >= displayOrder) {
                displayOrder = param.getDisplayOrder() + 1;
            }
        }

        // 创建新的部门观察项参数
        DeptMonitoringGroupParam newParam = DeptMonitoringGroupParam.builder()
                .deptMonitoringGroupId(groupId)
                .monitoringParamCode(code)
                .displayOrder(displayOrder)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(TimeUtils.getNowUtc())
                .build();
        newParam = deptMonitoringGroupParamRepository.save(newParam);
        log.info("Successfully added monitoring group parameter. " +
            "Dept ID: {}, Group ID: {}, Code: {}, Display Order: {}, User: {}",
            deptId, groupId, code, displayOrder, accountId);
        return new Pair<>(StatusCode.OK, newParam.getId());
    }

    public GenericResp deleteDeptMonitoringGroupParam(
        String deleteDeptMonitoringGroupParamReqJson
    ) {
        DeleteDeptMonitoringGroupParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                deleteDeptMonitoringGroupParamReqJson,
                DeleteDeptMonitoringGroupParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 解析用户信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("AccountId is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }

        // 提取关键参数
        Integer paramId = req.getId();

        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findGroupParamDeptId(jdbcTemplate, paramId),
            deptId -> List.of(LockKeys.deptMonitoringGroups(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findGroupParamDeptId(jdbcTemplate, paramId)),
            deptId -> {
                // 如果必要，更新部门分组
                Pair<DeptMonitoringGroupParam, DeptMonitoringGroup> deletablePair =
                    isDeptMonitoringGroupParamDeletable(paramId);
                if (deletablePair == null) {
                    return GenericResp.newBuilder().setRt(protoService.getReturnCode(
                        StatusCode.OK)).build();
                }
                DeptMonitoringGroupParam deptMonitoringGroupParam = deletablePair.getFirst();
                String paramCode = deptMonitoringGroupParam.getMonitoringParamCode();
                DeptMonitoringGroup deptMonitoringGroup = deletablePair.getSecond();
                String groupName = deptMonitoringGroup.getName();
                monitoringMetaService.resetDeptMonitoringGroups(deptId, account.autoAcctId());

                // 执行删除操作
                deleteDeptMonitoringGroupParamImpl(
                    deptId, groupName, paramCode, account.autoAcctId());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
            }, GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK)).build());
    }

    private Pair<DeptMonitoringGroupParam, DeptMonitoringGroup> isDeptMonitoringGroupParamDeletable(
        Integer paramId
    ) {
        // 检查参数是否存在
        DeptMonitoringGroupParam param = deptMonitoringGroupParamRepository
            .findByIdAndIsDeletedFalse(paramId).orElse(null);
        if (param == null) {
            log.error("Monitoring group parameter not found for ID: {}", paramId);
            return null;
        }

        // 查找对应的分组
        DeptMonitoringGroup group = deptMonitoringGroupRepository
            .findById(param.getDeptMonitoringGroupId()).orElse(null);
        if (group == null) {
            log.error("Monitoring group not found for ID: {}", param.getDeptMonitoringGroupId());
            return null;
        }

        return new Pair<>(param, group);
    }

    private void deleteDeptMonitoringGroupParamImpl(
        String deptId, String groupName, String paramCode, String accountId
    ) {
        // 查找对应的部门监控分组
        DeptMonitoringGroup deptGroup = deptMonitoringGroupRepository
            .findByDeptIdAndNameAndIsDeletedFalse(deptId, groupName).orElse(null);
        if (deptGroup == null) {
            log.info("未找到有效科室分组，科室 {}，分组 {}，用户 {}",
                deptId, groupName, accountId);
            return;
        }
        Integer groupId = deptGroup.getId();

        // 查找对应的部门监控分组参数
        DeptMonitoringGroupParam param = deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(groupId, paramCode)
            .orElse(null);
        if (param == null) {
            log.info("未找到有效的科室分组参数，分组 {}，参数 {}，用户 {}",
                groupId, paramCode, accountId);
            return;
        }

        // 软删除
        param.setIsDeleted(true);
        param.setDeletedBy(accountId);
        param.setDeletedAt(TimeUtils.getNowUtc());
        deptMonitoringGroupParamRepository.save(param);

        log.info("已删除科室分组参数，科室 {}，分组 {}，参数 {}，用户 {}",
            deptId, groupName, paramCode, accountId);
        return;
    }

    public GenericResp reorderDeptMonitoringGroupParam(
        String reorderDeptMonitoringGroupParamReqJson
    ) {
        // 解析请求
        ReorderDeptMonitoringGroupParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reorderDeptMonitoringGroupParamReqJson,
                ReorderDeptMonitoringGroupParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取当前用户
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }

        // 校验科室 ID
        final String deptId = req.getDeptId();
        if (deptId == null || deptId.isBlank()) {
            log.error("Department ID is empty. User: {}", account.autoAcctId());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_IS_EMPTY))
                    .build();
        }

        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroups(deptId)), () -> {
            boolean deptExists = deptMonitoringGroupRepository
                .existsByDeptIdAndIsDeletedFalse(deptId);
            if (!deptExists) {
                log.error("Department ID does not exist: {}. User: {}",
                    deptId, account.autoAcctId());
                return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(
                            StatusCode.MONITORING_GROUP_INVALID_DEPARTMENT))
                        .build();
            }

            // 获取科室分组 ID
            List<Integer> groupIds = deptMonitoringGroupRepository
                .findByDeptIdAndIsDeletedFalse(deptId).stream()
                    .map(DeptMonitoringGroup::getId)
                    .toList();

            if (groupIds.isEmpty()) {
                log.info("No monitoring groups found for department: {}. User: {}",
                    deptId, account.autoAcctId());
                return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.OK))
                        .build();
            }

            // 获取组内参数
            List<DeptMonitoringGroupParam> params = deptMonitoringGroupParamRepository
                .findByDeptMonitoringGroupIdInAndIsDeletedFalse(groupIds);
            Map<Integer, DeptMonitoringGroupParam> existingParams = params.stream()
                    .collect(Collectors.toMap(DeptMonitoringGroupParam::getId, param -> param));

            // 读取新的参数顺序
            Map<Integer, Integer> newOrderMap = req.getParamOrderList().stream()
                    .collect(
                        Collectors.toMap(MonitoringGroupParamOrder::getId,
                            MonitoringGroupParamOrder::getDisplayOrder));

            // 更新前校验所有参数 ID
            for (Integer id : newOrderMap.keySet()) {
                if (!existingParams.containsKey(id)) {
                    log.error("Monitoring group param not found for ID: {}. User: {}",
                        id, account.autoAcctId());
                    return GenericResp.newBuilder()
                            .setRt(protoService.getReturnCode(
                                StatusCode.MONITORING_GROUP_PARAM_NOT_EXIST))
                            .build();
                }
            }

            // 所有参数有效后更新顺序
            for (Map.Entry<Integer, Integer> entry : newOrderMap.entrySet()) {
                int id = entry.getKey();
                int newOrder = entry.getValue();

                DeptMonitoringGroupParam param = existingParams.get(id);

                // 仅更新顺序发生变化的参数
                if (param.getDisplayOrder() != newOrder) {
                    param.setDisplayOrder(newOrder);
                    param.setModifiedBy(account.autoAcctId()); // 记录修改人
                    param.setModifiedAt(TimeUtils.getNowUtc()); // 记录修改时间
                    deptMonitoringGroupParamRepository.save(param);
                    log.info("Updated display order for param ID: {} to {}, modified by: {}",
                        id, newOrder, account.autoAcctId());
                }
            }

            // 记录并返回结果
            log.info("科室分组参数排序已更新，科室 {}，修改"
                + "by: {}",
                deptId, account.autoAcctId());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
        });
    }


    public GetPatientMonitoringGroupsResp getPatientMonitoringGroups(String getPatientMonitoringGroupsReqJson) {
        GetPatientMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientMonitoringGroupsReqJson, GetPatientMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetPatientMonitoringGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取当前用户信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty while adding dept monitoring group.");
            return GetPatientMonitoringGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }

        // 提取参数
        final Long pid = req.getPid();
        final String deptId = req.getDeptId();
        final int groupType = req.getGroupType();
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        // 获取当前患者信息
        final PatientRecord patientRecord = patientReader.findById(pid).orElse(null);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientMonitoringGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        if (!patientRecord.getDeptId().equals(deptId)) {
            log.error("Patient's department ID does not match request. Patient Dept ID: {}, Request Dept ID: {}", 
                patientRecord.getDeptId(), deptId);
            return GetPatientMonitoringGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        // 获取引流管参数
        List<String> balanceOutTubeParamCodes = new ArrayList<>();
        if (groupType == MonEnums.GROUP_TYPE_BALANCE) {
            balanceOutTubeParamCodes = patientTubeImpl.getMonitoringParamCodes(pid, queryStartUtc, queryEndUtc);
        }

        return GetPatientMonitoringGroupsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMonitoringGroup(monitoringConfig.getMonitoringGroups(
                pid, deptId, groupType, balanceOutTubeParamCodes, account.autoAcctId()
            ))
            .build();
    }

    public GenericResp updatePatientMonitoringGroup(String updatePatientMonitoringGroupReqJson) {
        // Parse JSON to Proto
        UpdatePatientMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientMonitoringGroupReqJson, UpdatePatientMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取当前用户信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        long pid = req.getPid();
        if (pid <= 0) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        return lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)),
            () -> updatePatientMonitoringGroupLocked(req, account.autoAcctId()));
    }

    private GenericResp updatePatientMonitoringGroupLocked(
        UpdatePatientMonitoringGroupReq req, String accountId
    ) {
        final LocalDateTime now = TimeUtils.getNowUtc();

        // 提取关键参数
        final Long pid = req.getPid();
        final Integer deptGroupId = req.getMonitoringGroupId();
        final List<MonitoringGroupParamPB> newParamList = req.getParamsList();
        final Map<String, MonitoringGroupParamPB> newParamMap = newParamList.stream()
            .collect(Collectors.toMap(MonitoringGroupParamPB::getCode, param -> param));

        PatientRecord patient = patientReader.findById(pid).orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        DeptMonitoringGroup deptGroup = deptMonitoringGroupRepository.findById(deptGroupId)
            .orElse(null);
        if (deptGroup == null) {
            log.error("DeptMonitoringGroup not found for ID: {}", deptGroupId);
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.MONITORING_GROUP_NOT_EXIST))
                    .build();
        }
        if (!Objects.equals(patient.getDeptId(), deptGroup.getDeptId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_DEPT_MISMATCH))
                .build();
        }

        // 已绑定的历史组仍可编辑。未绑定的已删除分组不触发初始化。
        PatientMonitoringGroup patientGroup = patientMonitoringGroupRepository
            .findByDeptMonitoringGroupIdAndPidAndIsDeletedFalse(deptGroupId, pid)
            .orElse(null);
        if (patientGroup == null && !deptGroup.getIsDeleted()) {
            patientGroup = patientMonitoringMetaService
                .findOrInitGroupsForLockedPatient(patient, accountId).stream()
                .filter(group -> deptGroupId.equals(group.getDeptMonitoringGroupId()))
                .findFirst().orElse(null);
        }
        if (patientGroup == null) {
            log.info("No PatientMonitoringGroup found for pid: {}, group_id: {}", pid, deptGroupId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_MONITORING_GROUP_NOT_FOUND))
                .build();
        }
        List<DeptMonitoringGroupParam> deptGroupParams = deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdAndIsDeletedFalse(deptGroupId);
        Map<String, Integer> deptCodeOrderMap = deptGroupParams.stream().collect(Collectors.toMap(
            DeptMonitoringGroupParam::getMonitoringParamCode,
            DeptMonitoringGroupParam::getDisplayOrder));
        List<PatientMonitoringGroupParam> patientGroupParams = patientMonitoringGroupParamRepository
            .findByPatientMonitoringGroupIdAndIsDeletedFalse(patientGroup.getId());
        Map<String, PatientMonitoringGroupParam> patientGroupParamMap = patientGroupParams.stream()
            .collect(Collectors.toMap(PatientMonitoringGroupParam::getMonitoringParamCode, param -> param));

        // 比较部门观察项参数和请求参数：如果相同，软删除病人观察项分组参数（保留分组）；如果不同，继续比较病人观察项参数和请求参数
        if (newParamList.size() == deptCodeOrderMap.size()) {
            boolean deptPatientParamsMatch = true;
            for (MonitoringGroupParamPB reqParam : newParamList) {
                Integer deptOrder = deptCodeOrderMap.get(reqParam.getCode());
                if (deptOrder == null || deptOrder != reqParam.getDisplayOrder()) {
                    deptPatientParamsMatch = false;
                    break;
                }
            }
            if (deptPatientParamsMatch) {
                for (PatientMonitoringGroupParam param : patientGroupParams) {
                    param.setIsDeleted(true);
                    param.setDeletedBy(accountId);
                    param.setDeletedAt(now);
                }
                patientMonitoringGroupParamRepository.saveAll(patientGroupParams);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .build();
            }
        }

        // 比较新老参数，增/改/删
        List<PatientMonitoringGroupParam> paramsToUpdate = new ArrayList<>();
        for (MonitoringGroupParamPB reqParam : newParamList) {
            if (reqParam.getCode().startsWith(monitoringConfig.getDrainageTubeParamPrefix())) {
                log.info("Skipping drainage tube parameter: {}", reqParam.getCode());
                continue;
            }

            PatientMonitoringGroupParam existingParam = patientGroupParamMap.get(reqParam.getCode());
            if (existingParam == null) {
                // 新增参数
                PatientMonitoringGroupParam newParam = PatientMonitoringGroupParam.builder()
                    .patientMonitoringGroupId(patientGroup.getId())
                    .monitoringParamCode(reqParam.getCode())
                    .displayOrder(reqParam.getDisplayOrder())
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedAt(now)
                    .build();
                paramsToUpdate.add(newParam);
                log.info("Added new PatientMonitoringGroupParam: {}, code: {}", patientGroup.getId(), reqParam.getCode());
            } else {
                // 更新已存在的参数
                if (!existingParam.getDisplayOrder().equals(reqParam.getDisplayOrder())) {
                    existingParam.setDisplayOrder(reqParam.getDisplayOrder());
                    existingParam.setModifiedBy(accountId);
                    existingParam.setModifiedAt(now);
                    paramsToUpdate.add(existingParam);
                    log.info("Updated PatientMonitoringGroupParam: {}, new order: {}", existingParam.getId(), reqParam.getDisplayOrder());
                }
            }
        }
        for (PatientMonitoringGroupParam param : patientGroupParams) {
            if (!newParamMap.containsKey(param.getMonitoringParamCode())) {
                // 标记为删除
                param.setIsDeleted(true);
                param.setDeletedBy(accountId);
                param.setDeletedAt(now);
                paramsToUpdate.add(param);
                log.info("Marked PatientMonitoringGroupParam as deleted: {}, user: {}",
                    param.getId(), accountId);
            }
        }
        // 保存所有更新的参数
        if (!paramsToUpdate.isEmpty()) patientMonitoringGroupParamRepository.saveAll(paramsToUpdate);

        return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
    }

    public GenericResp syncPatientMonitoringGroupsFromDept(String syncPatientMonitoringGroupsFromDeptReqJson) {
        final SyncPatientMonitoringGroupsFromDeptReq req;

        // 解析json到proto
        try {
            req = ProtoUtils.parseJsonToProto(syncPatientMonitoringGroupsFromDeptReqJson, SyncPatientMonitoringGroupsFromDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 提取关键参数
        final String deptId = req.getDeptId();
        final List<Long> pids = patientService.getAllPatientsInIcu(deptId).stream()
            .map(PatientRecord::getId).toList();

        for (Long pid : pids) {
            patientMonitoringMetaService.syncFromDepartment(pid, deptId, account.autoAcctId());
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private void saveDeptParamToHistory(DeptMonitoringParam deptParam, String reason, String accountId, Boolean isDeleted) {
        monitoringParamHistoryRepository.save(
            MonitoringMapper.toMonitoringParamHistoryEntity(deptParam, accountId, isDeleted));
        log.info("Saved DeptMonitoringParam to history for code: {}, reason: {}, user: {}", deptParam.getId().getCode(), reason, accountId);
    }

    final String ZONE_ID;

    private final ConfigProtoService protoService;
    private final UserQuery userQuery;
    private final PatientService patientService;
    private final PatientReader patientReader;
    private final PatientTubeImpl patientTubeImpl;

    private final MonitoringConfig monitoringConfig;
    private final MonitoringMetaService monitoringMetaService;
    private final PatientMonitoringMetaService patientMonitoringMetaService;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;

    private final MonitoringParamRepository monitoringParamRepository;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final MonitoringParamHistoryRepository monitoringParamHistoryRepository;
    private final DeptMonitoringGroupRepository deptMonitoringGroupRepository;
    private final DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository;
    private final PatientMonitoringGroupRepository patientMonitoringGroupRepository;
    private final PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository;
}
