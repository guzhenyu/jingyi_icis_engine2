package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptMonitoringGroupParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteMonitoringParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptMonitoringGroupsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeptMonitoringGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.MonitoringGroupOrder;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.MonitoringGroupParamOrder;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderDeptMonitoringGroupParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ReorderDeptMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateDeptMonGroupNameReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateDeptMonitoringParamReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IdReq;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IdResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringGroupJdbc;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonMapper;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class DeptMonMetaService {
    public DeptMonMetaService(
        Config config,
        DeptMonMetaReader deptMonMetaReader,
        PatientMonMetaReader patientMonMetaReader,
        MonMetaReader monMetaReader,
        MonMetaService monMetaService,
        DeptMonitoringParamRepository deptParamRepository,
        DeptMonitoringGroupRepository deptGroupRepository,
        DeptMonitoringGroupParamRepository deptGroupParamRepository,
        LockExecutor lockExecutor,
        JdbcTemplate jdbcTemplate,
        UserQuery userQuery
    ) {
        this.deptMonMetaReader = deptMonMetaReader;
        this.patientMonMetaReader = patientMonMetaReader;
        this.monMetaReader = monMetaReader;
        this.monMetaService = monMetaService;
        this.deptParamRepository = deptParamRepository;
        this.deptGroupRepository = deptGroupRepository;
        this.deptGroupParamRepository = deptGroupParamRepository;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.userQuery = userQuery;
        this.monitoringPb = config.getMonitoring();
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();
    }

    public GenericResp deleteMonitoringParam(String reqJson) {
        DeleteMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, DeleteMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("解析删除观察项参数请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        String deptId = req.getDepartmentId();
        String code = req.getCode();
        if (code.isBlank()) return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);
        if (deptId.isEmpty()) return response(monMetaService.deleteMonitoringParam(code));

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        try {
            return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
                Optional<DeptMonitoringParam> deptParam =
                    deptMonMetaReader.findParamOverride(deptId, code);
                if (deptParam.isPresent()) {
                    deptParamRepository.delete(deptParam.get());
                    log.info("用户 {} 删除科室 {} 的观察项参数覆盖 {}",
                        account.autoAcctId(), deptId, code);
                }
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            log.error("删除科室 {} 的观察项参数覆盖 {} 失败", deptId, code, e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public GenericResp updateDeptMonitoringParam(String reqJson) {
        UpdateDeptMonitoringParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateDeptMonitoringParamReq.newBuilder());
        } catch (Exception e) {
            log.error("解析更新科室观察项参数请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        String deptId = req.getDepartmentId();
        String code = req.getCode();
        if (deptId.isBlank()) return response(StatusCode.DEPT_IS_EMPTY);
        if (code.isBlank()) return response(StatusCode.MONITORING_PARAM_CODE_EMPTY);

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        String newTypePb = ProtoUtils.encodeValueMeta(req.getValueMeta());
        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
            if (userQuery.getDepartmentId(deptId) == null) {
                return response(StatusCode.DEPARTMENT_NOT_FOUND);
            }
            MonitoringParam globalParam = monMetaReader.findGlobalParam(code).orElse(null);
            if (globalParam == null) {
                return response(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST);
            }

            StatusCode codeStatus = monMetaService.validateParamValueMeta(
                globalParam.getBalanceType(), req.getValueMeta());
            if (codeStatus != StatusCode.OK) return response(codeStatus);

            DeptMonitoringParam deptParam = deptMonMetaReader
                .findParamOverride(deptId, code).orElse(null);
            if (deptParam == null) {
                deptParamRepository.save(DeptMonitoringParam.builder()
                    .id(new DeptMonitoringParamId(code, deptId))
                    .typePb(newTypePb)
                    .build());
                log.info("用户 {} 新增科室 {} 的观察项参数覆盖 {}",
                    account.autoAcctId(), deptId, code);
            } else if (!Objects.equals(deptParam.getTypePb(), newTypePb)) {
                deptParam.setTypePb(newTypePb);
                deptParamRepository.save(deptParam);
                log.info("用户 {} 更新科室 {} 的观察项参数覆盖 {}",
                    account.autoAcctId(), deptId, code);
            }
            return response(StatusCode.OK);
        });
    }

    @Transactional(readOnly = true, isolation = Isolation.SERIALIZABLE)
    public GetDeptMonitoringGroupsResp getDeptMonitoringGroups(String reqJson) {
        GetDeptMonitoringGroupsResp.Builder resp = GetDeptMonitoringGroupsResp.newBuilder();
        GetDeptMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetDeptMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("解析查询科室观察项分组请求失败", e);
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }

        if (userQuery.getCurrentAccount() == null) {
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND)).build();
        }

        String deptId = req.getDepartmentId();
        List<DeptMonitoringGroup> groups = deptMonMetaReader.listActiveGroups(
            deptId, req.getGroupType()).stream()
            .sorted(Comparator.comparingInt(DeptMonitoringGroup::getDisplayOrder)
                .thenComparingInt(DeptMonitoringGroup::getId))
            .toList();
        List<Integer> groupIds = groups.stream().map(DeptMonitoringGroup::getId).toList();
        Map<Integer, List<DeptMonitoringGroupParam>> membersByGroup =
            deptMonMetaReader.listActiveGroupParamsByGroupIds(groupIds);
        Set<String> codes = membersByGroup.values().stream()
            .flatMap(List::stream)
            .map(DeptMonitoringGroupParam::getMonitoringParamCode)
            .collect(Collectors.toSet());
        Map<String, MonitoringParam> globalParams = monMetaReader.findGlobalParams(codes);
        Map<String, DeptMonitoringParam> deptParams =
            deptMonMetaReader.findParamOverrides(deptId, codes);

        resp.setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK));
        for (DeptMonitoringGroup group : groups) {
            List<DeptMonitoringGroupParam> members =
                membersByGroup.getOrDefault(group.getId(), List.of()).stream()
                    .sorted(Comparator.comparingInt(DeptMonitoringGroupParam::getDisplayOrder)
                        .thenComparingInt(DeptMonitoringGroupParam::getId))
                    .toList();
            List<MonitoringParamPB> params = new ArrayList<>();
            for (DeptMonitoringGroupParam member : members) {
                String code = member.getMonitoringParamCode();
                MonitoringParam global = globalParams.get(code);
                if (global == null) {
                    log.error("找不到科室 {} 的观察项参数 {}", deptId, code);
                    continue;
                }
                DeptMonitoringParam override = deptParams.get(code);
                String typePb = override == null ? global.getTypePb() : override.getTypePb();
                ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(typePb);
                if (valueMeta == null) {
                    log.error("无法读取科室 {} 的参数类型配置 {}", deptId, code);
                    continue;
                }
                params.add(MonMapper.toMonitoringParamPB(global, valueMeta).toBuilder()
                    .setId(member.getId()).setDisplayOrder(member.getDisplayOrder()).build());
            }
            MonitoringGroupBetaPB groupPb = MonMapper.toMonitoringGroupBetaPB(group, params);
            resp.addMonitoringGroup(groupPb);
        }
        return resp.build();
    }

    public IdResp addDeptMonitoringGroup(String reqJson) {
        AddDeptMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddDeptMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("解析新增科室观察项分组请求失败", e);
            return idResponse(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return idResponse(StatusCode.ACCOUNT_NOT_FOUND);

        String deptId = req.getDepartmentId();
        String groupName = req.getName();
        int groupType = req.getGroupType();
        if (deptId.isBlank()) return idResponse(StatusCode.DEPT_IS_EMPTY);
        if (groupType == MonEnums.GROUP_TYPE_BALANCE) {
            return idResponse(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE);
        }

        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
            List<DeptMonitoringGroup> groups = listOrInitDeptGroups(deptId);
            if (groups.stream().anyMatch(group -> groupName.equals(group.getName()))) {
                return idResponse(StatusCode.MONITORING_GROUP_EXIST);
            }

            groups = copyDeptGroupsIfBoundToPatients(groups, account.autoAcctId());
            int maxOrder = groups.stream().mapToInt(DeptMonitoringGroup::getDisplayOrder)
                .max().orElse(0);
            DeptMonitoringGroup group = deptGroupRepository.save(DeptMonitoringGroup.builder()
                .deptId(deptId)
                .name(groupName)
                .groupType(groupType)
                .displayOrder(Math.max(0, maxOrder) + 1)
                .sumTypePb(ProtoUtils.encodeValueMeta(req.getSumValueMeta()))
                .isDeleted(false)
                .modifiedBy(account.autoAcctId())
                .modifiedAt(TimeUtils.getNowUtc())
                .build());
            log.info("用户 {} 新增科室 {} 的观察项分组 {}",
                account.autoAcctId(), deptId, group.getId());
            return idResponse(StatusCode.OK, group.getId());
        });
    }

    public GenericResp updateDeptMonitoringGroupName(String reqJson) {
        UpdateDeptMonGroupNameReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, UpdateDeptMonGroupNameReq.newBuilder());
        } catch (Exception e) {
            log.error("解析更新科室观察项分组名称请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);
        if (req.getName().isBlank()) return response(StatusCode.DEPT_MONITORING_GROUP_NAME_EMPTY);

        int groupId = req.getId();
        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId),
            deptId -> List.of(LockKeys.deptMonitoringGroup(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId)),
            deptId -> {
                DeptMonitoringGroup group = deptMonMetaReader
                    .findActiveGroup(deptId, groupId).orElse(null);
                if (group == null) return response(StatusCode.DEPT_MONITORING_GROUP_NOT_EXIST);
                if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
                    return response(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE);
                }
                Optional<DeptMonitoringGroup> duplicate = deptGroupRepository
                    .findByDeptIdAndNameAndIsDeletedFalse(deptId, req.getName());
                if (duplicate.isPresent() && !Objects.equals(duplicate.get().getId(), groupId)) {
                    return response(StatusCode.DEPT_MONITORING_GROUP_NAME_DUPLICATE);
                }
                group.setName(req.getName());
                group.setModifiedBy(account.autoAcctId());
                group.setModifiedAt(TimeUtils.getNowUtc());
                deptGroupRepository.save(group);
                log.info("用户 {} 更新科室 {} 的观察项分组 {} 名称",
                    account.autoAcctId(), deptId, groupId);
                return response(StatusCode.OK);
            }, response(StatusCode.DEPT_MONITORING_GROUP_NOT_EXIST));
    }

    public GenericResp deleteDeptMonitoringGroup(String reqJson) {
        IdReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, IdReq.newBuilder());
        } catch (Exception e) {
            log.error("解析删除科室观察项分组请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        int groupId = req.getId();
        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId),
            deptId -> List.of(LockKeys.deptMonitoringGroup(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findActiveGroupDeptId(jdbcTemplate, groupId)),
            deptId -> {
                DeptMonitoringGroup group = deptMonMetaReader
                    .findActiveGroup(deptId, groupId).orElse(null);
                if (group == null) return response(StatusCode.OK);
                if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
                    return response(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE);
                }

                List<DeptMonitoringGroup> groups =
                    copyDeptGroupsIfBoundToPatients(deptId, account.autoAcctId());
                DeptMonitoringGroup target = groups.stream()
                    .filter(current -> group.getName().equals(current.getName()))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                        "未找到科室 " + deptId + " 的当前有效分组 " + group.getName()));

                target.setIsDeleted(true);
                target.setDeletedBy(account.autoAcctId());
                target.setDeletedAt(TimeUtils.getNowUtc());
                deptGroupRepository.save(target);
                log.info("用户 {} 删除科室 {} 的观察项分组 {}",
                    account.autoAcctId(), deptId, target.getId());
                return response(StatusCode.OK);
            }, response(StatusCode.OK));
    }

    public GenericResp reorderDeptMonitoringGroup(String reqJson) {
        ReorderDeptMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, ReorderDeptMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("解析科室观察项分组排序请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        String deptId = req.getDepartmentId();
        if (deptId.isBlank()) return response(StatusCode.DEPT_IS_EMPTY);
        if (req.getGroupOrderList().isEmpty()) {
            return response(StatusCode.MONITORING_GROUP_LIST_EMPTY);
        }

        try {
            return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
                List<DeptMonitoringGroup> groups = deptMonMetaReader.listActiveGroups(deptId);
                if (groups.isEmpty()) {
                    return response(StatusCode.MONITORING_GROUP_INVALID_DEPARTMENT);
                }
                Map<Integer, DeptMonitoringGroup> groupMap = new HashMap<>();
                Map<Integer, List<DeptMonitoringGroup>> groupsByType = new HashMap<>();
                for (DeptMonitoringGroup group : groups) {
                    groupMap.put(group.getId(), group);
                    groupsByType.computeIfAbsent(group.getGroupType(), type -> new ArrayList<>())
                        .add(group);
                }

                Map<Integer, Integer> requested = new HashMap<>();
                Set<Integer> affectedTypes = new HashSet<>();
                for (MonitoringGroupOrder order : req.getGroupOrderList()) {
                    DeptMonitoringGroup group = groupMap.get(order.getMonitoringGroupId());
                    if (group == null) return response(StatusCode.MONITORING_GROUP_NOT_EXIST);
                    if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
                        return response(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE);
                    }
                    requested.put(group.getId(), order.getDisplayOrder());
                    affectedTypes.add(group.getGroupType());
                }

                Comparator<DeptMonitoringGroup> byRequestedOrder = Comparator
                    .comparingInt((DeptMonitoringGroup group) -> requested.getOrDefault(
                        group.getId(), group.getDisplayOrder()))
                    .thenComparingInt(DeptMonitoringGroup::getId);
                LocalDateTime now = TimeUtils.getNowUtc();
                List<DeptMonitoringGroup> updatedGroups = new ArrayList<>();
                for (Integer type : affectedTypes) {
                    List<DeptMonitoringGroup> sameTypeGroups = groupsByType.get(type);
                    sameTypeGroups.sort(byRequestedOrder);
                    for (int i = 0; i < sameTypeGroups.size(); i++) {
                        DeptMonitoringGroup group = sameTypeGroups.get(i);
                        int displayOrder = i + 1;
                        if (group.getDisplayOrder() != displayOrder) {
                            group.setDisplayOrder(displayOrder);
                            group.setModifiedBy(account.autoAcctId());
                            group.setModifiedAt(now);
                            updatedGroups.add(group);
                        }
                    }
                }
                if (!updatedGroups.isEmpty()) deptGroupRepository.saveAll(updatedGroups);
                return response(StatusCode.OK);
            });
        } catch (Exception e) {
            log.error("科室 {} 的观察项分组排序失败", deptId, e);
            return response(StatusCode.INTERNAL_EXCEPTION);
        }
    }

    public IdResp addDeptMonitoringGroupParam(String reqJson) {
        AddDeptMonitoringGroupParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, AddDeptMonitoringGroupParamReq.newBuilder());
        } catch (Exception e) {
            log.error("解析新增科室观察项分组参数请求失败", e);
            return idResponse(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return idResponse(StatusCode.ACCOUNT_NOT_FOUND);

        String deptId = req.getDeptId();
        if (deptId.isBlank()) return idResponse(StatusCode.DEPT_IS_EMPTY);

        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
            String code = req.getCode();
            Pair<StatusCode, DeptMonitoringGroup> check =
                checkDeptGroupParamAddable(deptId, req.getDeptMonitoringGroupId(), code);
            if (check.getFirst() != StatusCode.OK) return idResponse(check.getFirst());

            String groupName = check.getSecond().getName();
            List<DeptMonitoringGroup> groups =
                copyDeptGroupsIfBoundToPatients(deptId, account.autoAcctId());
            DeptMonitoringGroup group = groups.stream()
                .filter(current -> groupName.equals(current.getName()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "未找到科室 " + deptId + " 的当前有效分组 " + groupName));

            int maxOrder = deptMonMetaReader.listActiveGroupParams(group.getId()).stream()
                .mapToInt(DeptMonitoringGroupParam::getDisplayOrder).max().orElse(0);
            DeptMonitoringGroupParam member = deptGroupParamRepository.save(
                DeptMonitoringGroupParam.builder()
                    .deptMonitoringGroupId(group.getId())
                    .monitoringParamCode(code)
                    .displayOrder(Math.max(0, maxOrder) + 1)
                    .isDeleted(false)
                    .modifiedBy(account.autoAcctId())
                    .modifiedAt(TimeUtils.getNowUtc())
                    .build());
            log.info("用户 {} 为科室 {} 的观察项分组 {} 新增参数 {}",
                account.autoAcctId(), deptId, group.getId(), code);
            return idResponse(StatusCode.OK, member.getId());
        });
    }

    private Pair<StatusCode, DeptMonitoringGroup> checkDeptGroupParamAddable(
        String deptId, int groupId, String paramCode
    ) {
        DeptMonitoringGroup group = deptMonMetaReader.findGroupByIdIncludingDeleted(groupId)
            .filter(requested -> deptId.equals(requested.getDeptId())).orElse(null);
        if (group != null && group.getIsDeleted()) {
            // 旧组 ID 只用于定位同科室的同名有效组。
            group = deptGroupRepository
                .findByDeptIdAndNameAndIsDeletedFalse(deptId, group.getName())
                .orElse(null);
        }
        if (group == null) return new Pair<>(StatusCode.MONITORING_GROUP_NOT_EXIST, null);

        MonitoringParam param = monMetaReader.findGlobalParam(paramCode).orElse(null);
        if (param == null) return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);

        boolean exists = deptGroupParamRepository
            .existsByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(
                group.getId(), paramCode);
        if (exists) return new Pair<>(StatusCode.MONITORING_GROUP_PARAM_EXIST, group);

        if (Objects.equals(group.getGroupType(), MonEnums.GROUP_TYPE_BALANCE)) {
            Integer role = group.getBalanceRole();
            Integer balanceType = param.getBalanceType();
            boolean mismatch =
                (Objects.equals(role, MonEnums.BALANCE_TYPE_IN)
                    && Objects.equals(balanceType, MonEnums.BALANCE_TYPE_OUT))
                || (Objects.equals(role, MonEnums.BALANCE_TYPE_OUT)
                    && Objects.equals(balanceType, MonEnums.BALANCE_TYPE_IN));
            if (mismatch) return new Pair<>(StatusCode.GROUP_PARAM_BALANCE_NOT_MATCH, group);
        }
        return new Pair<>(StatusCode.OK, group);
    }

    public GenericResp deleteDeptMonitoringGroupParam(String reqJson) {
        IdReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, IdReq.newBuilder());
        } catch (Exception e) {
            log.error("解析删除科室观察项分组参数请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        int paramId = req.getId();
        return lockExecutor.executePlannedIfPresent(
            () -> MonitoringGroupJdbc.findGroupParamDeptId(jdbcTemplate, paramId),
            deptId -> List.of(LockKeys.deptMonitoringGroup(deptId)),
            deptId -> Objects.equals(deptId,
                MonitoringGroupJdbc.findGroupParamDeptId(jdbcTemplate, paramId)),
            deptId -> {
                DeptMonitoringGroupParam member = deptGroupParamRepository
                    .findByIdAndIsDeletedFalse(paramId).orElse(null);
                if (member == null) return response(StatusCode.OK);

                DeptMonitoringGroup group = deptMonMetaReader
                    .findGroupByIdIncludingDeleted(member.getDeptMonitoringGroupId()).orElse(null);
                if (group == null) return response(StatusCode.OK);

                List<DeptMonitoringGroup> groups =
                    copyDeptGroupsIfBoundToPatients(deptId, account.autoAcctId());
                DeptMonitoringGroup target = groups.stream()
                    .filter(current -> group.getName().equals(current.getName()))
                    .findFirst().orElse(null);
                if (target == null) return response(StatusCode.OK);

                DeptMonitoringGroupParam targetMember = deptGroupParamRepository
                    .findByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(
                        target.getId(), member.getMonitoringParamCode())
                    .orElse(null);
                if (targetMember != null) {
                    targetMember.setIsDeleted(true);
                    targetMember.setDeletedBy(account.autoAcctId());
                    targetMember.setDeletedAt(TimeUtils.getNowUtc());
                    deptGroupParamRepository.save(targetMember);
                    log.info("用户 {} 删除科室 {} 的观察项组内参数 {}",
                        account.autoAcctId(), deptId, targetMember.getId());
                }
                return response(StatusCode.OK);
            }, response(StatusCode.OK));
    }

    public GenericResp reorderDeptMonitoringGroupParam(String reqJson) {
        ReorderDeptMonitoringGroupParamReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reqJson, ReorderDeptMonitoringGroupParamReq.newBuilder());
        } catch (Exception e) {
            log.error("解析科室观察项组内参数排序请求失败", e);
            return response(StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return response(StatusCode.ACCOUNT_NOT_FOUND);

        String deptId = req.getDeptId();
        if (deptId.isBlank()) return response(StatusCode.DEPT_IS_EMPTY);

        return lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(deptId)), () -> {
            List<Integer> groupIds = deptMonMetaReader.listActiveGroups(deptId).stream()
                .map(DeptMonitoringGroup::getId).toList();
            if (groupIds.isEmpty()) return response(StatusCode.MONITORING_GROUP_INVALID_DEPARTMENT);

            Map<Integer, List<DeptMonitoringGroupParam>> paramsByGroup =
                deptMonMetaReader.listActiveGroupParamsByGroupIds(groupIds);
            Map<Integer, DeptMonitoringGroupParam> existing = paramsByGroup.values().stream()
                .flatMap(List::stream)
                .collect(Collectors.toMap(DeptMonitoringGroupParam::getId, Function.identity()));
            Map<Integer, Integer> requested = req.getParamOrderList().stream()
                .collect(Collectors.toMap(MonitoringGroupParamOrder::getId,
                    MonitoringGroupParamOrder::getDisplayOrder));
            if (!existing.keySet().containsAll(requested.keySet())) {
                return response(StatusCode.MONITORING_GROUP_PARAM_NOT_EXIST);
            }

            Set<Integer> affectedGroupIds = requested.keySet().stream()
                .map(id -> existing.get(id).getDeptMonitoringGroupId()).collect(Collectors.toSet());
            Comparator<DeptMonitoringGroupParam> byRequestedOrder = Comparator
                .comparingInt((DeptMonitoringGroupParam param) -> requested.getOrDefault(
                    param.getId(), param.getDisplayOrder()))
                .thenComparingInt(DeptMonitoringGroupParam::getId);
            LocalDateTime now = TimeUtils.getNowUtc();
            List<DeptMonitoringGroupParam> updated = new ArrayList<>();
            for (Integer groupId : affectedGroupIds) {
                List<DeptMonitoringGroupParam> groupParams = paramsByGroup.get(groupId);
                groupParams.sort(byRequestedOrder);
                for (int i = 0; i < groupParams.size(); i++) {
                    DeptMonitoringGroupParam param = groupParams.get(i);
                    int displayOrder = i + 1;
                    if (param.getDisplayOrder() != displayOrder) {
                        param.setDisplayOrder(displayOrder);
                        param.setModifiedBy(account.autoAcctId());
                        param.setModifiedAt(now);
                        updated.add(param);
                    }
                }
            }
            if (!updated.isEmpty()) deptGroupParamRepository.saveAll(updated);
            return response(StatusCode.OK);
        });
    }

    // 调用方统一持有科室锁并提供事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public void initDept(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        if (deptGroupRepository.existsByDeptIdAndIsDeletedFalse(deptId)) return;

        insertDeptGroups(deptId, monitoringPb.getBalanceGroupList(), MonEnums.GROUP_TYPE_BALANCE);
        insertDeptGroups(
            deptId, monitoringPb.getMonitoringGroupList(), MonEnums.GROUP_TYPE_MONITORING);
        log.info("科室 {} 的默认观察项分组初始化完成", deptId);
    }

    private void insertDeptGroups(String deptId, List<MonitoringGroupPB> groupPbs, int groupType) {
        LocalDateTime now = TimeUtils.getNowUtc();
        List<DeptMonitoringGroupParam> members = new ArrayList<>();
        for (int i = 0; i < groupPbs.size(); i++) {
            MonitoringGroupPB groupPb = groupPbs.get(i);
            Integer balanceRole =
                groupType == MonEnums.GROUP_TYPE_BALANCE && groupPb.hasBalanceRole()
                    ? groupPb.getBalanceRole() : null;
            DeptMonitoringGroup group = deptGroupRepository.save(DeptMonitoringGroup.builder()
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
                MonitoringGroupParamPB member = groupPb.getMonitoringParam(index);
                members.add(DeptMonitoringGroupParam.builder()
                    .deptMonitoringGroupId(group.getId())
                    .monitoringParamCode(member.getCode())
                    .displayOrder(index + 1)
                    .isDeleted(false)
                    .modifiedBy(UserQuery.SYSTEM_ACCOUNT_ID)
                    .modifiedAt(now)
                    .build());
            }
        }
        if (!members.isEmpty()) deptGroupParamRepository.saveAll(members);
    }

    private List<DeptMonitoringGroup> copyDeptGroupsIfBoundToPatients(
        String deptId, String accountId
    ) {
        return copyDeptGroupsIfBoundToPatients(listOrInitDeptGroups(deptId), accountId);
    }

    private List<DeptMonitoringGroup> listOrInitDeptGroups(String deptId) {
        List<DeptMonitoringGroup> groups = deptMonMetaReader.listActiveGroups(deptId);
        if (!groups.isEmpty()) return groups;

        initDept(deptId);
        return deptMonMetaReader.listActiveGroups(deptId);
    }

    private List<DeptMonitoringGroup> copyDeptGroupsIfBoundToPatients(
        List<DeptMonitoringGroup> existing, String accountId
    ) {
        List<Integer> groupIds = existing.stream().map(DeptMonitoringGroup::getId).toList();
        if (!patientMonMetaReader.hasActiveDeptGroupBindings(groupIds)) return existing;

        Map<Integer, List<DeptMonitoringGroupParam>> membersByGroup =
            deptMonMetaReader.listActiveGroupParamsByGroupIds(groupIds);
        LocalDateTime now = TimeUtils.getNowUtc();
        List<DeptMonitoringGroup> copies = new ArrayList<>();
        for (DeptMonitoringGroup group : existing) {
            copies.add(group.toBuilder().id(null).modifiedBy(accountId).modifiedAt(now).build());
            group.setIsDeleted(true);
            group.setDeletedBy(accountId);
            group.setDeletedAt(now);
        }
        // 先刷新旧组的软删除，释放当前有效组名的唯一约束。
        deptGroupRepository.saveAllAndFlush(existing);
        List<DeptMonitoringGroup> saved = deptGroupRepository.saveAll(copies);
        Map<String, DeptMonitoringGroup> groupsByName = saved.stream()
            .collect(Collectors.toMap(DeptMonitoringGroup::getName, Function.identity()));
        List<DeptMonitoringGroupParam> copiedMembers = new ArrayList<>();
        for (DeptMonitoringGroup group : existing) {
            Integer newGroupId = groupsByName.get(group.getName()).getId();
            for (DeptMonitoringGroupParam member :
                membersByGroup.getOrDefault(group.getId(), List.of())) {
                copiedMembers.add(member.toBuilder().id(null)
                    .deptMonitoringGroupId(newGroupId)
                    .modifiedBy(accountId)
                    .modifiedAt(now)
                    .build());
            }
        }
        if (!copiedMembers.isEmpty()) deptGroupParamRepository.saveAll(copiedMembers);
        return saved;
    }

    private GenericResp response(StatusCode status) {
        return ReturnCodeUtils.genericResponse(statusCodeMsgList, status);
    }

    private IdResp idResponse(StatusCode status) {
        return idResponse(status, 0);
    }

    private IdResp idResponse(StatusCode status, int id) {
        return IdResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, status)).setId(id).build();
    }

    private final DeptMonMetaReader deptMonMetaReader;
    private final PatientMonMetaReader patientMonMetaReader;
    private final MonMetaReader monMetaReader;
    private final MonMetaService monMetaService;
    private final DeptMonitoringParamRepository deptParamRepository;
    private final DeptMonitoringGroupRepository deptGroupRepository;
    private final DeptMonitoringGroupParamRepository deptGroupParamRepository;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final UserQuery userQuery;
    private final MonitoringPB monitoringPb;
    private final List<String> statusCodeMsgList;
}
