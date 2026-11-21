package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaService;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class MonitoringMetaService {
    public MonitoringMetaService(
        ConfigProtoService protoService,
        UserQuery userQuery,
        MonMetaService monMetaService,
        DeptMonMetaReader deptMonMetaReader,
        DeptMonitoringParamRepository deptMonitoringParamRepository,
        DeptMonitoringGroupRepository deptMonitoringGroupRepository,
        DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository,
        PatientMonitoringGroupRepository patientMonitoringGroupRepository,
        LockExecutor lockExecutor
    ) {
        this.protoService = protoService;
        this.userQuery = userQuery;
        this.monMetaService = monMetaService;
        this.deptMonMetaReader = deptMonMetaReader;
        this.deptMonitoringParamRepository = deptMonitoringParamRepository;
        this.deptMonitoringGroupRepository = deptMonitoringGroupRepository;
        this.deptMonitoringGroupParamRepository = deptMonitoringGroupParamRepository;
        this.patientMonitoringGroupRepository = patientMonitoringGroupRepository;
        this.lockExecutor = lockExecutor;
        this.monitoringPb = protoService.getConfig().getMonitoring();
    }

    // 调用方统一持有 LockKeys.deptMonitoringGroups(deptId) 并提供事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public void initDept(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        // 已有分组代表科室已配置，不覆盖自定义内容或恢复主动删除的分组。
        if (deptMonitoringGroupRepository.existsByDeptIdAndIsDeletedFalse(deptId)) return;

        insertDeptGroups(deptId, monitoringPb.getBalanceGroupList(),
            MonEnums.GROUP_TYPE_BALANCE);
        insertDeptGroups(deptId, monitoringPb.getMonitoringGroupList(),
            MonEnums.GROUP_TYPE_MONITORING);
        log.info("Department {} monitoring groups initialization completed.", deptId);
    }

    private void insertDeptGroups(
        String deptId, List<MonitoringGroupPB> groupPbs, int groupType
    ) {
        LocalDateTime now = TimeUtils.getNowUtc();
        List<DeptMonitoringGroupParam> params = new ArrayList<>();
        boolean isBalanceGroup = groupType == MonEnums.GROUP_TYPE_BALANCE;
        for (int i = 0; i < groupPbs.size(); i++) {
            MonitoringGroupPB groupPb = groupPbs.get(i);
            Integer balanceRole =
                isBalanceGroup && groupPb.hasBalanceRole() ? groupPb.getBalanceRole() : null;
            DeptMonitoringGroup group = deptMonitoringGroupRepository.save(
                DeptMonitoringGroup.builder()
                    .deptId(deptId)
                    .name(groupPb.getName())
                    .groupType(groupType)
                    .balanceRole(balanceRole)
                    .displayOrder(i)
                    .sumTypePb(ProtoUtils.encodeValueMeta(groupPb.getSumValueMeta()))
                    .isDeleted(false)
                    .modifiedBy(UserQuery.SYSTEM_ACCOUNT_ID)
                    .modifiedAt(now)
                    .build());
            for (int index = 0; index < groupPb.getMonitoringParamCount(); index++) {
                MonitoringGroupParamPB paramPb = groupPb.getMonitoringParam(index);
                params.add(DeptMonitoringGroupParam.builder()
                    .deptMonitoringGroupId(group.getId())
                    .monitoringParamCode(paramPb.getCode())
                    .displayOrder(index + 1)
                    .isDeleted(false)
                    .modifiedBy(UserQuery.SYSTEM_ACCOUNT_ID)
                    .modifiedAt(now)
                    .build());
            }
        }
        if (!params.isEmpty()) deptMonitoringGroupParamRepository.saveAll(params);
    }

    // 与科室分组和组内参数的变更共用一把锁、同一个事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public List<DeptMonitoringGroup> resetDeptMonitoringGroups(String deptId, String accountId) {
        List<DeptMonitoringGroup> existingGroups =
            deptMonitoringGroupRepository.findByDeptIdAndIsDeletedFalse(deptId);
        if (existingGroups.isEmpty()) {
            initDept(deptId);
            return deptMonitoringGroupRepository.findByDeptIdAndIsDeletedFalse(deptId);
        }

        // 已被患者引用的科室分组保留历史版本，编辑时复制一套新的分组和参数。
        List<Integer> deptGroupIds = existingGroups.stream().map(DeptMonitoringGroup::getId)
            .toList();
        List<PatientMonitoringGroup> patientGroups = patientMonitoringGroupRepository
            .findByDeptMonitoringGroupIdInAndIsDeletedFalse(deptGroupIds);
        if (patientGroups.isEmpty()) return existingGroups;

        List<DeptMonitoringGroup> copiedGroups = new ArrayList<>();
        LocalDateTime now = TimeUtils.getNowUtc();
        for (DeptMonitoringGroup existingGroup : existingGroups) {
            copiedGroups.add(existingGroup.toBuilder().id(null)
                .modifiedBy(accountId).modifiedAt(now).build());
        }
        List<DeptMonitoringGroupParam> existingGroupParams = deptMonitoringGroupParamRepository
            .findByDeptMonitoringGroupIdInAndIsDeletedFalse(deptGroupIds);

        for (DeptMonitoringGroup existingGroup : existingGroups) {
            existingGroup.setIsDeleted(true);
            existingGroup.setDeletedBy(accountId);
            existingGroup.setDeletedAt(now);
        }
        // 先将旧组标记为已删除，释放有效组名的唯一约束，再插入新版本。
        deptMonitoringGroupRepository.saveAllAndFlush(existingGroups);
        Map<String, Integer> existingGroupIdMap = existingGroups.stream()
            .collect(Collectors.toMap(DeptMonitoringGroup::getName, DeptMonitoringGroup::getId));

        copiedGroups = deptMonitoringGroupRepository.saveAll(copiedGroups);
        Map<Integer, Integer> oldNewGroupIdMap = new HashMap<>();
        for (DeptMonitoringGroup copiedGroup : copiedGroups) {
            Integer existingGroupId = existingGroupIdMap.get(copiedGroup.getName());
            if (existingGroupId == null) {
                log.error("No existing group found for name: {}", copiedGroup.getName());
                continue;
            }
            oldNewGroupIdMap.put(existingGroupId, copiedGroup.getId());
        }

        List<DeptMonitoringGroupParam> copiedGroupParams = new ArrayList<>();
        for (DeptMonitoringGroupParam existingParam : existingGroupParams) {
            Integer newGroupId = oldNewGroupIdMap.get(existingParam.getDeptMonitoringGroupId());
            if (newGroupId == null) {
                log.error("No new group found for old group id: {}",
                    existingParam.getDeptMonitoringGroupId());
                continue;
            }
            copiedGroupParams.add(existingParam.toBuilder().id(null)
                .deptMonitoringGroupId(newGroupId)
                .modifiedBy(accountId).modifiedAt(now).build());
        }
        deptMonitoringGroupParamRepository.saveAll(copiedGroupParams);
        return copiedGroups;
    }

    public GenericResp deleteMonitoringParam(String deleteMonitoringParamReqJson) {
        final DeleteMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                deleteMonitoringParamReqJson, DeleteMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }
        final String departmentId = req.getDepartmentId();
        final String code = req.getCode();
        if (code.isBlank()) {
            log.warn("Invalid request: code is empty.");
            return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);
        }
        if (departmentId.isEmpty()) {
            return response(monMetaService.deleteMonitoringParam(code));
        }

        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return response(StatusCode.ACCOUNT_NOT_FOUND);
        }

        try {
            List<String> lockKeys = List.of(
                LockKeys.deptMonitoringGroups(departmentId), LockKeys.monitoringParam());
            return lockExecutor.execute(lockKeys, () -> {
                Optional<DeptMonitoringParam> deptParam =
                    deptMonMetaReader.findParamOverride(departmentId, code);
                if (deptParam.isEmpty()) {
                    log.info("DeptMonitoringParam not found: {}, dept: {}. User: {}",
                        code, departmentId, account.autoAcctId());
                    return response(StatusCode.OK);
                }
                deptMonitoringParamRepository.delete(deptParam.get());
                log.info("Deleted DeptMonitoringParam with code: {} for dept: {}. User: {}",
                    code, departmentId, account.autoAcctId());
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            // 删除和历史记录必须在同一事务中提交或回滚。
            log.error("Failed to delete monitoring parameter for code: {} and dept: {}. User: {}",
                code, departmentId, account.autoAcctId(), e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    private GenericResp response(StatusCode statusCode) {
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(statusCode)).build();
    }

    private final ConfigProtoService protoService;
    private final UserQuery userQuery;
    private final MonMetaService monMetaService;
    private final DeptMonMetaReader deptMonMetaReader;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final DeptMonitoringGroupRepository deptMonitoringGroupRepository;
    private final DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepository;
    private final PatientMonitoringGroupRepository patientMonitoringGroupRepository;
    private final LockExecutor lockExecutor;
    private final MonitoringPB monitoringPb;
}
