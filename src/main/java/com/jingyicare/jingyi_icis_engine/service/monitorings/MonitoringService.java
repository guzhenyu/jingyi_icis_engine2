package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetPatientMonitoringGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncPatientMonitoringGroupsFromDeptReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdatePatientMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

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
        @Autowired PatientMonitoringMetaService patientMonitoringMetaService,
        @Autowired LockExecutor lockExecutor,
        @Autowired DeptMonMetaReader deptMonMetaReader,
        @Autowired PatientMonitoringGroupRepository patientMonitoringGroupRepository,
        @Autowired PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository
    ) {
        this.protoService = protoService;
        this.userQuery = userQuery;
        this.patientService = patientService;
        this.patientReader = patientReader;
        this.patientTubeImpl = patientTubeImpl;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringMetaService = patientMonitoringMetaService;
        this.lockExecutor = lockExecutor;
        this.deptMonMetaReader = deptMonMetaReader;
        this.patientMonitoringGroupRepository = patientMonitoringGroupRepository;
        this.patientMonitoringGroupParamRepository = patientMonitoringGroupParamRepository;
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

        DeptMonitoringGroup deptGroup = deptMonMetaReader
            .findGroupByIdIncludingDeleted(deptGroupId)
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
        List<DeptMonitoringGroupParam> deptGroupParams =
            deptMonMetaReader.listActiveGroupParams(deptGroupId);
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

    private final ConfigProtoService protoService;
    private final UserQuery userQuery;
    private final PatientService patientService;
    private final PatientReader patientReader;
    private final PatientTubeImpl patientTubeImpl;

    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringMetaService patientMonitoringMetaService;
    private final LockExecutor lockExecutor;
    private final DeptMonMetaReader deptMonMetaReader;
    private final PatientMonitoringGroupRepository patientMonitoringGroupRepository;
    private final PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepository;
}
