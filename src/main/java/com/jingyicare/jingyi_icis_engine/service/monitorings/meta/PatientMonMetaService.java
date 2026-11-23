package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncPatientMonitoringGroupsFromDeptReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdatePatientMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class PatientMonMetaService {
    public PatientMonMetaService(
        Config config,
        PatientMonMetaReader patientMonMetaReader,
        DeptMonMetaReader deptMonMetaReader,
        PatientReader patientReader,
        PatientMonitoringGroupRepository patientGroupRepository,
        PatientMonitoringGroupParamRepository patientGroupParamRepository,
        UserQuery userQuery,
        PatientService patientService,
        PatientTubeImpl patientTubeImpl,
        MonEnums monEnums,
        LockExecutor lockExecutor,
        PlatformTransactionManager transactionManager,
        @Value("${monitoring.enable_drainage:true}") boolean enableDrainage,
        @Value("${monitoring.enable_drainage_params:true}") boolean enableDrainageParams
    ) {
        this.patientMonMetaReader = patientMonMetaReader;
        this.deptMonMetaReader = deptMonMetaReader;
        this.patientReader = patientReader;
        this.patientGroupRepository = patientGroupRepository;
        this.patientGroupParamRepository = patientGroupParamRepository;
        this.userQuery = userQuery;
        this.patientService = patientService;
        this.patientTubeImpl = patientTubeImpl;
        this.monEnums = monEnums;
        this.lockExecutor = lockExecutor;
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();
        this.tubeParamList = config.getTube().getTubeSetting().getParamList();
        this.enableDrainage = enableDrainage;
        this.enableDrainageParams = enableDrainageParams;
        this.suspendTransactionTemplate = new TransactionTemplate(transactionManager);
        this.suspendTransactionTemplate.setPropagationBehavior(
            TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    public GetPatientMonitoringGroupsResp getPatientMonitoringGroups(String reqJson) {
        GetPatientMonitoringGroupsResp.Builder resp = GetPatientMonitoringGroupsResp.newBuilder();
        GetPatientMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetPatientMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("解析患者观察项分组查询请求失败", e);
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND)).build();
        }

        long pid = req.getPid();
        String deptId = req.getDeptId();
        PatientRecord patient = patientReader.findById(pid).orElse(null);
        if (patient == null) {
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND)).build();
        }
        if (!Objects.equals(patient.getDeptId(), deptId)) {
            return resp.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.PATIENT_DEPT_MISMATCH)).build();
        }

        int groupType = req.getGroupType();
        List<String> tubeParamCodes = List.of();
        if (groupType == MonEnums.GROUP_TYPE_BALANCE) {
            LocalDateTime start = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
            LocalDateTime end = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
            tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(pid, start, end);
        }
        return resp.setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllMonitoringGroup(listPatientMonitoringGroups(
                pid, deptId, groupType, tubeParamCodes, account.autoAcctId()))
            .build();
    }

    public List<MonitoringGroupBetaPB> listPatientMonitoringGroups(
        Long pid, String deptId, int groupType, List<String> tubeParamCodes, String accountId
    ) {
        if (pid == null || pid <= 0) throw new IllegalArgumentException("pid must be positive");

        Map<String, MonitoringParam> paramsByCode =
            deptMonMetaReader.listEffectiveMonitoringParams(deptId);
        // 报表等调用方可能已有事务，只在读取患者分组布局时暂挂该事务。
        GroupLayout layout = suspendTransactionTemplate.execute(ignored ->
            lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)), () -> {
                List<PatientMonitoringGroup> patientGroups =
                    patientMonMetaReader.listActiveGroups(pid);
                if (patientGroups.isEmpty()) {
                    PatientRecord patient = patientReader.findById(pid).orElse(null);
                    if (patient == null || !Objects.equals(patient.getDeptId(), deptId)) {
                        log.warn("无法初始化患者观察项分组：pid={}，deptId={}", pid, deptId);
                        return new GroupLayout(List.of(), Map.of());
                    }
                    List<DeptMonitoringGroup> deptGroups =
                        deptMonMetaReader.listActiveGroups(deptId);
                    patientGroups = createPatientGroupBindings(
                        pid, deptGroups, accountId, TimeUtils.getNowUtc());
                }

                Map<Integer, Integer> patientGroupIdsByDeptGroupId = patientGroups.stream()
                    .collect(Collectors.toMap(PatientMonitoringGroup::getDeptMonitoringGroupId,
                        PatientMonitoringGroup::getId));
                List<DeptMonitoringGroup> groups = deptMonMetaReader
                    .listGroupsByIdsAndTypeIncludingDeleted(
                        List.copyOf(patientGroupIdsByDeptGroupId.keySet()), groupType);
                List<Integer> deptGroupIds = groups.stream().map(DeptMonitoringGroup::getId)
                    .toList();
                Map<Integer, List<DeptMonitoringGroupParam>> deptParamsByGroupId =
                    deptMonMetaReader.listActiveGroupParamsByGroupIds(deptGroupIds);
                List<Integer> patientGroupIds = deptGroupIds.stream()
                    .map(patientGroupIdsByDeptGroupId::get).toList();
                Map<Integer, List<PatientMonitoringGroupParam>> patientParamsByGroupId =
                    patientMonMetaReader.listActiveGroupParamsByGroupIds(patientGroupIds);

                Map<Integer, List<MonitoringGroupParamPB>> paramsByDeptGroupId = new HashMap<>();
                for (Integer deptGroupId : deptGroupIds) {
                    int patientGroupId = patientGroupIdsByDeptGroupId.get(deptGroupId);
                    List<PatientMonitoringGroupParam> customParams =
                        patientParamsByGroupId.getOrDefault(patientGroupId, List.of());
                    List<MonitoringGroupParamPB> params = customParams.isEmpty()
                        ? deptParamsByGroupId.getOrDefault(deptGroupId, List.of())
                            .stream().map(MonMapper::toMonitoringGroupParamPB).toList()
                        : customParams.stream().map(MonMapper::toMonitoringGroupParamPB).toList();
                    paramsByDeptGroupId.put(deptGroupId, params);
                }
                return new GroupLayout(groups, paramsByDeptGroupId);
            })
        );

        return MonMapper.toMonitoringGroupBetaPBs(layout.groups(), layout.paramsByDeptGroupId(),
            paramsByCode, enableDrainage && tubeParamCodes != null ? tubeParamCodes : List.of(),
            enableDrainageParams ? tubeParamList : List.of());
    }

    public GenericResp updatePatientMonitoringGroup(String reqJson) {
        UpdatePatientMonitoringGroupReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reqJson, UpdatePatientMonitoringGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("解析患者观察项分组更新请求失败", e);
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }
        long pid = req.getPid();
        if (pid <= 0) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND);
        }

        String accountId = account.autoAcctId();
        StatusCode status = lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)),
            () -> {
                LocalDateTime now = TimeUtils.getNowUtc();
                int deptGroupId = req.getMonitoringGroupId();
                List<MonitoringGroupParamPB> requestedParams = req.getParamsList();
                Map<String, Integer> requestedOrders = requestedParams.stream().collect(
                    Collectors.toMap(
                        MonitoringGroupParamPB::getCode, MonitoringGroupParamPB::getDisplayOrder));

                PatientRecord patient = patientReader.findById(pid).orElse(null);
                if (patient == null) return StatusCode.PATIENT_NOT_FOUND;

                DeptMonitoringGroup deptGroup = deptMonMetaReader
                    .findGroupByIdIncludingDeleted(deptGroupId).orElse(null);
                if (deptGroup == null) return StatusCode.MONITORING_GROUP_NOT_EXIST;
                if (!Objects.equals(patient.getDeptId(), deptGroup.getDeptId())) {
                    return StatusCode.PATIENT_DEPT_MISMATCH;
                }

                // 已绑定的历史组仍可编辑。首次建组仅接受当前有效科室组。
                PatientMonitoringGroup patientGroup = patientMonMetaReader
                    .findActiveGroupByDeptGroupId(pid, deptGroupId).orElse(null);
                if (patientGroup == null && !deptGroup.getIsDeleted()) {
                    List<PatientMonitoringGroup> patientGroups =
                        patientMonMetaReader.listActiveGroups(pid);
                    if (patientGroups.isEmpty()) {
                        List<DeptMonitoringGroup> deptGroups =
                            deptMonMetaReader.listActiveGroups(patient.getDeptId());
                        patientGroups = createPatientGroupBindings(pid, deptGroups, accountId, now);
                    }
                    patientGroup = patientGroups.stream()
                        .filter(group -> group.getDeptMonitoringGroupId() == deptGroupId)
                        .findFirst().orElse(null);
                }
                if (patientGroup == null) return StatusCode.PATIENT_MONITORING_GROUP_NOT_FOUND;

                List<DeptMonitoringGroupParam> deptParams =
                    deptMonMetaReader.listActiveGroupParams(deptGroupId);
                Map<String, Integer> deptOrders = deptParams.stream().collect(Collectors.toMap(
                        DeptMonitoringGroupParam::getMonitoringParamCode,
                        DeptMonitoringGroupParam::getDisplayOrder));
                int patientGroupId = patientGroup.getId();
                List<PatientMonitoringGroupParam> patientParams =
                    patientMonMetaReader.listActiveGroupParams(patientGroupId);

                if (requestedOrders.equals(deptOrders)) {
                    for (PatientMonitoringGroupParam param : patientParams) {
                        param.setIsDeleted(true);
                        param.setDeletedBy(accountId);
                        param.setDeletedAt(now);
                    }
                    patientGroupParamRepository.saveAll(patientParams);
                    return StatusCode.OK;
                }

                Map<String, PatientMonitoringGroupParam> patientParamsByCode =
                    patientParams.stream().collect(Collectors.toMap(
                        PatientMonitoringGroupParam::getMonitoringParamCode, param -> param));
                List<PatientMonitoringGroupParam> changedParams = new ArrayList<>();
                for (MonitoringGroupParamPB requested : requestedParams) {
                    String code = requested.getCode();
                    if (monEnums.isDrainageTubeParam(code)) continue;

                    PatientMonitoringGroupParam existing = patientParamsByCode.get(code);
                    if (existing == null) {
                        changedParams.add(PatientMonitoringGroupParam.builder()
                            .patientMonitoringGroupId(patientGroupId)
                            .monitoringParamCode(code)
                            .displayOrder(requested.getDisplayOrder())
                            .isDeleted(false)
                            .modifiedBy(accountId)
                            .modifiedAt(now)
                            .build());
                    } else if (!existing.getDisplayOrder().equals(requested.getDisplayOrder())) {
                        existing.setDisplayOrder(requested.getDisplayOrder());
                        existing.setModifiedBy(accountId);
                        existing.setModifiedAt(now);
                        changedParams.add(existing);
                    }
                }
                for (PatientMonitoringGroupParam param : patientParams) {
                    if (requestedOrders.containsKey(param.getMonitoringParamCode())) continue;
                    param.setIsDeleted(true);
                    param.setDeletedBy(accountId);
                    param.setDeletedAt(now);
                    changedParams.add(param);
                }
                if (!changedParams.isEmpty()) patientGroupParamRepository.saveAll(changedParams);
                return StatusCode.OK;
            }
        );
        return ReturnCodeUtils.genericResponse(statusCodeMsgList, status);
    }

    public GenericResp syncPatientMonitoringGroupsFromDept(String reqJson) {
        SyncPatientMonitoringGroupsFromDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson,
                SyncPatientMonitoringGroupsFromDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("解析患者观察项分组同步请求失败", e);
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED);
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND);
        }

        String deptId = req.getDeptId();
        List<Long> pids = patientService.getAllPatientsInIcu(deptId).stream()
            .map(PatientRecord::getId).toList();
        for (long pid : pids) {
            syncPatientGroupsFromDept(pid, deptId, account.autoAcctId());
        }
        return ReturnCodeUtils.genericResponse(statusCodeMsgList, StatusCode.OK);
    }

    private void syncPatientGroupsFromDept(long pid, String deptId, String accountId) {
        lockExecutor.execute(List.of(LockKeys.patientMonitoringGroups(pid)), () -> {
            PatientRecord patient = patientReader.findById(pid).orElse(null);
            if (patient == null || !Objects.equals(patient.getDeptId(), deptId)) return null;

            List<DeptMonitoringGroup> deptGroups = deptMonMetaReader.listActiveGroups(deptId);
            List<PatientMonitoringGroup> patientGroups = patientMonMetaReader.listActiveGroups(pid);
            Set<Integer> deptGroupIds = deptGroups.stream()
                .map(DeptMonitoringGroup::getId).collect(Collectors.toSet());
            Set<Integer> boundDeptGroupIds = patientGroups.stream()
                .map(PatientMonitoringGroup::getDeptMonitoringGroupId).collect(Collectors.toSet());
            List<Integer> patientGroupIds = patientGroups.stream()
                .map(PatientMonitoringGroup::getId).toList();
            boolean bindingsMatch = patientGroups.size() == deptGroupIds.size()
                && deptGroupIds.equals(boundDeptGroupIds);
            if (bindingsMatch && patientMonMetaReader
                .listActiveGroupParamsByGroupIds(patientGroupIds).isEmpty()) {
                return null;
            }

            LocalDateTime now = TimeUtils.getNowUtc();
            for (PatientMonitoringGroup group : patientGroups) {
                group.setIsDeleted(true);
                group.setDeletedBy(accountId);
                group.setDeletedAt(now);
            }
            // 先刷新旧绑定的软删除，再插入新绑定，释放有效绑定的唯一约束。
            if (!patientGroups.isEmpty()) patientGroupRepository.saveAllAndFlush(patientGroups);
            createPatientGroupBindings(pid, deptGroups, accountId, now);
            return null;
        });
    }

    private List<PatientMonitoringGroup> createPatientGroupBindings(
        long pid, List<DeptMonitoringGroup> deptGroups, String accountId, LocalDateTime now
    ) {
        if (deptGroups.isEmpty()) return List.of();

        List<PatientMonitoringGroup> patientGroups = deptGroups.stream()
            .map(deptGroup -> PatientMonitoringGroup.builder()
                .deptMonitoringGroupId(deptGroup.getId())
                .pid(pid)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(now)
                .build())
            .toList();
        return patientGroupRepository.saveAll(patientGroups);
    }

    private record GroupLayout(
        List<DeptMonitoringGroup> groups,
        Map<Integer, List<MonitoringGroupParamPB>> paramsByDeptGroupId
    ) {}

    private final PatientMonMetaReader patientMonMetaReader;
    private final DeptMonMetaReader deptMonMetaReader;
    private final PatientReader patientReader;
    private final PatientMonitoringGroupRepository patientGroupRepository;
    private final PatientMonitoringGroupParamRepository patientGroupParamRepository;
    private final UserQuery userQuery;
    private final PatientService patientService;
    private final PatientTubeImpl patientTubeImpl;
    private final MonEnums monEnums;
    private final LockExecutor lockExecutor;
    private final List<String> statusCodeMsgList;
    private final List<MonitoringParamPB> tubeParamList;
    private final boolean enableDrainage;
    private final boolean enableDrainageParams;
    private final TransactionTemplate suspendTransactionTemplate;
}
