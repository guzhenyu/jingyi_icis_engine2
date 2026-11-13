package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.consts.PatientConsts;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotModule;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotService;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan;
import com.jingyicare.jingyi_icis_engine.service.snapshots.SnapshotChangePlan.Change;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ScoreService {
    public ScoreService(
        ConfigProtoService protoService, ScoreMetaService scoreMetaService,
        ScoreCalculator scoreCalculator, UserQuery userQuery, PatientService patientService,
        PatientNursingReportInvalidationService pnrUtils, PatientScoreRepository patientScoreRepo,
        MonitoringParamRepository monitoringParamRepo, PatientMonitoringRecordRepository pmrRepo,
        ScoreReader scoreReader, SnapshotService snapshotService, LockExecutor lockExecutor,
        JdbcTemplate jdbcTemplate
    ) {
        this.protoService = protoService;
        this.ZONE_ID = protoService.getZoneId();
        this.scoreMetaService = scoreMetaService;
        this.scoreCalculator = scoreCalculator;
        this.userQuery = userQuery;
        this.patientService = patientService;
        this.pnrUtils = pnrUtils;
        this.patientScoreRepo = patientScoreRepo;
        this.monitoringParamRepo = monitoringParamRepo;
        this.pmrRepo = pmrRepo;
        this.scoreReader = scoreReader;
        this.snapshotService = snapshotService;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.scoreCodeParamCodeMap = new HashMap<>();
        this.paramCodeModalMap = new HashMap<>();
        for (MonitoringParamModalOptionsPB options :
            protoService.getConfig().getMonitoring().getParamModalOptionList()
        ) {
            String paramCode = options.getParamCode();
            for (MonitoringModalOptionPB option : options.getOptionList()) {
                scoreCodeParamCodeMap.put(option.getScoreGroupCode(), paramCode);
            }
            paramCodeModalMap.put(paramCode, options);
        }
    }

    public GetPatientScoresResp getPatientScores(String getPatientScoresReqJson) {
        final GetPatientScoresReq req;

        try {
            req = ProtoUtils.parseJsonToProto(
                getPatientScoresReqJson, GetPatientScoresReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPatientScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<ScoreGroupPB> scoreRecords = scoreReader.getRecords(
            req.getPid(), req.getScoreGroupCode(),
            TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC"),
            TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC"));

        return GetPatientScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(scoreRecords)
            .build();
    }

    public GetPatientScoreResp getPatientScore(String getPatientScoreReqJson) {
        final GetPatientScoreReq req;
        // 解析 JSON 请求
        try {
            req = ProtoUtils.parseJsonToProto(
                getPatientScoreReqJson, GetPatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        LocalDateTime effectiveTime = formatTime(TimeUtils.fromIso8601String(
            req.getEffectiveTimeIso8601(), "UTC"));

        // 查找部门评分组id: dept_score_groups.id
        String paramCode = req.getMonitoringParamCode();
        MonitoringParam monitoringParam = monitoringParamRepo.findByCode(paramCode).orElse(null);
        if (monitoringParam == null || StrUtils.isBlank(monitoringParam.getUiModalCode())) {
            log.error("Department score group code not found for param code: {}",
                req.getMonitoringParamCode());
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }
        String deptScoreGroupCode = "";
        MonitoringParamModalOptionsPB options = paramCodeModalMap.get(paramCode);
        if (options != null) {
            deptScoreGroupCode = options.getOptionList()
                .stream()
                .filter(option -> Objects.equals(
                    option.getUiModalCode(), monitoringParam.getUiModalCode()))
                .map(MonitoringModalOptionPB::getScoreGroupCode)
                .findFirst()
                .orElse("");
        }

        // 查找评分元数据
        ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(deptScoreGroupCode);
        if (scoreGroupMeta == null) {
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 查找患者评分
        PatientScore patientScore = patientScoreRepo
            .findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            req.getPid(), deptScoreGroupCode, effectiveTime
        ).orElse(null);

        // 构建响应
        GetPatientScoreResp.Builder respBuilder = GetPatientScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setFound(patientScore != null)
            .setScoreGroupMeta(scoreGroupMeta);
        if (patientScore != null) {
            ScoreGroupPB recordPb = ScoreMapper.toScoreGroupPB(patientScore, ZONE_ID);
            if (recordPb != null) respBuilder.setRecord(recordPb);
        }

        return respBuilder.build();
    }

    public AddPatientScoreResp addPatientScore(String requestJson) {
        final AddPatientScoreReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, AddPatientScoreReq.newBuilder());
        } catch (Exception e) {
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }

        ScoreGroupPB scoreGroup = req.getRecord();
        Pair<StatusCode, Supplier<ScoreLockPlan>> lockPlanPair =
            getScoreLockPlanSupplier(scoreGroup);
        if (lockPlanPair.getFirst() != StatusCode.OK) {
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(lockPlanPair.getFirst())).build();
        }
        try {
            return lockExecutor.executePlanned(
                lockPlanPair.getSecond(),
                ScoreLockPlan::resourceKeys,
                lockPlan -> isScoreLockPlanCurrent(lockPlan, scoreGroup.getId()),
                lockPlan -> {
                    LocalDateTime now = TimeUtils.getNowUtc();
                    PatientScore existing = patientScoreRepo
                        .findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
                            lockPlan.pid(), lockPlan.code(), lockPlan.targetTime()).orElse(null);
                    if (existing != null) {
                        return AddPatientScoreResp.newBuilder()
                            .setRt(protoService.getReturnCode(
                                StatusCode.PATIENT_SCORE_ALREADY_EXISTS)).build();
                    }

                    // 更新 patient_scores patient_monitoring_records patient_snapshots
                    ScoreGroupPB record = scoreGroup.toBuilder()
                        .setGroupScoreText(lockPlan.patientScoreStr()).build();
                    PatientScore added = patientScoreRepo.save(ScoreMapper.toPatientScoreEntity(
                        record, lockPlan.accountId(), lockPlan.accountName(), now));
                    if (!StrUtils.isBlank(lockPlan.paramCode())) {
                        saveMonitoringRecord(record, lockPlan.deptId(), lockPlan.paramCode(),
                            lockPlan.targetTime(), lockPlan.accountId(), now);
                    }
                    snapshotService.markModified(
                        SnapshotModule.Kind.SCORE_GROUP, lockPlan.snapshotPlan(),
                        lockPlan.snapshotPlan().changes(), now);

                    // Todo(guzhenyu): patient snapshot 全量后，删除
                    invalidateReports(lockPlan.pid(), lockPlan.deptId(), null,
                        lockPlan.targetTime(), now);

                    return AddPatientScoreResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.OK))
                        .setId(added.getId()).build();
                });
        } catch (RuntimeException e) {
            // 错误响应在锁执行器完成事务回滚后构建，避免提交部分联写结果。
            log.error("Failed to add patient score, pid={}, code={}",
                scoreGroup.getPid(), scoreGroup.getCode(), e);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION)).build();
        }
    }

    public GenericResp updatePatientScore(String requestJson) {
        final UpdatePatientScoreReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, UpdatePatientScoreReq.newBuilder());
        } catch (Exception e) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }
        ScoreGroupPB scoreGroup = req.getRecord();
        if (scoreGroup.getId() <= 0) {
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(StatusCode.INVALID_PARAM_VALUE));
        }
        Pair<StatusCode, Supplier<ScoreLockPlan>> lockPlanPair =
            getScoreLockPlanSupplier(scoreGroup);
        if (lockPlanPair.getFirst() != StatusCode.OK) {
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(lockPlanPair.getFirst()));
        }
        try {
            return lockExecutor.executePlanned(
                lockPlanPair.getSecond(),
                ScoreLockPlan::resourceKeys,
                lockPlan -> isScoreLockPlanCurrent(lockPlan, scoreGroup.getId()),
                lockPlan -> {
                    // 取得锁后读取当前内容，避免用预读内容误判为无需更新。
                    PatientScore current = patientScoreRepo
                        .findByIdAndIsDeletedFalse(scoreGroup.getId()).orElse(null);
                    if (current == null) {
                        return ReturnCodeUtils.genericResponse(
                            protoService.getReturnCode(StatusCode.PATIENT_SCORE_NOT_FOUND));
                    }
                    if (!matchesIdentity(scoreGroup, current)) {
                        return ReturnCodeUtils.genericResponse(
                            protoService.getReturnCode(StatusCode.PATIENT_SCORE_ID_NOT_MATCH));
                    }
                    PatientScore target = patientScoreRepo
                        .findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
                            lockPlan.pid(), lockPlan.code(), lockPlan.targetTime()).orElse(null);
                    if (target != null && !target.getId().equals(current.getId())) {
                        return ReturnCodeUtils.genericResponse(
                            protoService.getReturnCode(StatusCode.PATIENT_SCORE_ALREADY_EXISTS));
                    }

                    LocalDateTime previousTime = current.getEffectiveTime();
                    LocalDateTime now = TimeUtils.getNowUtc();
                    ScoreGroupPB record = scoreGroup.toBuilder()
                        .setGroupScoreText(lockPlan.patientScoreStr()).build();
                    PatientScore stored = ScoreMapper.toPatientScoreEntity(
                        record, lockPlan.accountId(), lockPlan.accountName(), now);
                    stored.setId(current.getId());
                    boolean scoreChanged = !sameScore(current, stored);
                    if (scoreChanged) patientScoreRepo.save(stored);

                    boolean monitoringChanged = false;
                    if (!StrUtils.isBlank(lockPlan.paramCode())) {
                        if (!previousTime.equals(lockPlan.targetTime())) {
                            monitoringChanged = deleteMonitoringRecord(lockPlan.pid(),
                                lockPlan.paramCode(), previousTime,
                                lockPlan.accountId(), now);
                        }
                        monitoringChanged |= saveMonitoringRecord(record, lockPlan.deptId(),
                            lockPlan.paramCode(), lockPlan.targetTime(), lockPlan.accountId(), now);
                    }

                    if (scoreChanged) {
                        snapshotService.markModified(SnapshotModule.Kind.SCORE_GROUP,
                            lockPlan.snapshotPlan(), lockPlan.snapshotPlan().changes(), now);
                    }

                    // Todo(guzhenyu): patient snapshot 全量后，删除
                    if (scoreChanged || monitoringChanged) {
                        invalidateReports(lockPlan.pid(), lockPlan.deptId(), previousTime,
                            lockPlan.targetTime(), now);
                    }

                    return ReturnCodeUtils.genericResponse(
                        protoService.getReturnCode(StatusCode.OK));
                });
        } catch (RuntimeException e) {
            // 错误响应必须在统一事务回滚结束后构建。
            log.error("Failed to update patient score, id={}", scoreGroup.getId(), e);
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION));
        }
    }

    public SavePatientScoreResp savePatientScore(String requestJson) {
        final SavePatientScoreReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, SavePatientScoreReq.newBuilder());
        } catch (Exception e) {
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }
        ScoreGroupPB scoreGroup = req.getRecord().toBuilder().clearId().build();
        Pair<StatusCode, Supplier<ScoreLockPlan>> lockPlanPair =
            getScoreLockPlanSupplier(scoreGroup);
        if (lockPlanPair.getFirst() != StatusCode.OK) {
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(lockPlanPair.getFirst())).build();
        }
        try {
            return lockExecutor.executePlanned(
                lockPlanPair.getSecond(),
                ScoreLockPlan::resourceKeys,
                lockPlan -> isScoreLockPlanCurrent(lockPlan, scoreGroup.getId()),
                lockPlan -> {
                    PatientScore existing = patientScoreRepo
                        .findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
                            lockPlan.pid(), lockPlan.code(), lockPlan.targetTime()).orElse(null);
                    LocalDateTime now = TimeUtils.getNowUtc();
                    ScoreGroupPB record = scoreGroup.toBuilder()
                        .setGroupScoreText(lockPlan.patientScoreStr()).build();
                    PatientScore stored = ScoreMapper.toPatientScoreEntity(
                        record, lockPlan.accountId(), lockPlan.accountName(), now);
                    if (existing != null) stored.setId(existing.getId());
                    boolean scoreChanged = existing == null || !sameScore(existing, stored);
                    if (scoreChanged) patientScoreRepo.save(stored);

                    boolean monitoringChanged = false;
                    if (!StrUtils.isBlank(lockPlan.paramCode())) {
                        monitoringChanged = saveMonitoringRecord(record, lockPlan.deptId(),
                            lockPlan.paramCode(), lockPlan.targetTime(), lockPlan.accountId(), now);
                    }

                    if (scoreChanged) {
                        snapshotService.markModified(SnapshotModule.Kind.SCORE_GROUP,
                            lockPlan.snapshotPlan(), lockPlan.snapshotPlan().changes(), now);
                    }

                    // Todo(guzhenyu): patient snapshot 全量后，删除
                    if (scoreChanged || monitoringChanged) {
                        invalidateReports(lockPlan.pid(), lockPlan.deptId(), null,
                            lockPlan.targetTime(), now);
                    }

                    return SavePatientScoreResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.OK))
                        .setGroupScoreText(record.getGroupScoreText()).build();
                });
        } catch (RuntimeException e) {
            // 错误响应必须在统一事务回滚结束后构建，禁止提交部分联写结果。
            log.error("Failed to save patient score, pid={}, code={}",
                scoreGroup.getPid(), scoreGroup.getCode(), e);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION)).build();
        }
    }

    public GenericResp deletePatientScore(String requestJson) {
        final DeletePatientScoreReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, DeletePatientScoreReq.newBuilder());
        } catch (Exception e) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED)).build();
        }
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND));
        }
        PatientScore initial = PatientScoreJdbc.findActiveRecord(jdbcTemplate, req.getId());
        if (initial == null) {
            return ReturnCodeUtils.genericResponse(protoService.getReturnCode(StatusCode.OK));
        }

        Long pid = initial.getPid();
        PatientRecord pr = patientService.getPatientRecord(pid);
        if (pr == null) {
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND));
        }

        try {
            // 重试时记录可能已被并发删除，仍用原评分锁保证幂等返回成功。
            List<String> fallbackKeys = List.of(LockKeys.patientScore(
                pid, initial.getScoreGroupCode(), formatTime(initial.getEffectiveTime())));
            return lockExecutor.executePlanned(
                () -> {
                    PatientScore record = PatientScoreJdbc.findActiveRecord(
                        jdbcTemplate, req.getId());
                    Long plannedPid = record == null ? pid : record.getPid();
                    String code = record == null ? initial.getScoreGroupCode()
                        : record.getScoreGroupCode();
                    List<Change> changes = record == null ? List.of()
                        : List.of(new Change(plannedPid, record.getEffectiveTime()));
                    SnapshotChangePlan snapshotPlan = snapshotService.planChanges(changes);
                    // 保留 byMonitoring 约定：从观察界面删除时才联删观察记录。
                    String paramCode = req.getByMonitoring()
                        ? scoreCodeParamCodeMap.get(code) : null;
                    return ScoreLockPlan.create(record, plannedPid,
                        snapshotPlan.departments().getOrDefault(plannedPid, pr.getDeptId()),
                        code, null, "", paramCode, snapshotPlan,
                        account.autoAcctId(), account.acctName());
                },
                lockPlan -> lockPlan.resourceKeys().isEmpty()
                    ? fallbackKeys : lockPlan.resourceKeys(),
                lockPlan -> isScoreLockPlanCurrent(lockPlan, req.getId()),
                lockPlan -> {
                    PatientScore current = patientScoreRepo
                        .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
                    if (current == null) {
                        return ReturnCodeUtils.genericResponse(
                            protoService.getReturnCode(StatusCode.OK));
                    }
                    PatientRecord patient = patientService.getPatientRecord(current.getPid());
                    if (patient == null) {
                        return ReturnCodeUtils.genericResponse(
                            protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND));
                    }
                    LocalDateTime now = TimeUtils.getNowUtc();
                    current.setIsDeleted(true);
                    current.setDeletedBy(lockPlan.accountId());
                    current.setDeletedAt(now);
                    patientScoreRepo.save(current);
                    if (!StrUtils.isBlank(lockPlan.paramCode())) {
                        deleteMonitoringRecord(current.getPid(), lockPlan.paramCode(),
                            current.getEffectiveTime(), lockPlan.accountId(), now);
                    }

                    snapshotService.markModified(SnapshotModule.Kind.SCORE_GROUP,
                        lockPlan.snapshotPlan(), lockPlan.snapshotPlan().changes(), now);

                    // Todo(guzhenyu): patient snapshot 全量后，删除
                    invalidateReports(current.getPid(), lockPlan.deptId(), null,
                        current.getEffectiveTime(), now);

                    return ReturnCodeUtils.genericResponse(
                        protoService.getReturnCode(StatusCode.OK));
                });
        } catch (RuntimeException e) {
            log.error("Failed to delete patient score, id={}", req.getId(), e);
            return ReturnCodeUtils.genericResponse(
                protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION));
        }
    }

    private Pair<StatusCode, Supplier<ScoreLockPlan>> getScoreLockPlanSupplier(ScoreGroupPB sgPb) {
        // 检查当前用户的账号信息
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("Account ID is empty.");
            return new Pair<>(StatusCode.ACCOUNT_NOT_FOUND, null);
        }

        final Long pid = sgPb.getPid();
        if (pid <= 0) return new Pair<>(StatusCode.PATIENT_NOT_FOUND, null);
        final String scoreGroupCode = sgPb.getCode();
        if (StrUtils.isBlank(scoreGroupCode)) {
            return new Pair<>(StatusCode.SCORE_GROUP_NOT_FOUND, null);
        }
        // 带 ID 的请求先核对患者、评分类型；保存入口清除 ID，始终按业务键定位。
        final long entityId = sgPb.getId();
        if (entityId > 0) {
            PatientScore entity = PatientScoreJdbc.findActiveRecord(jdbcTemplate, entityId);
            if (entity == null) return new Pair<>(StatusCode.PATIENT_SCORE_NOT_FOUND, null);
            if (!matchesIdentity(sgPb, entity)) {
                return new Pair<>(StatusCode.PATIENT_SCORE_ID_NOT_MATCH, null);
            }
        }

        // 验证患者信息
        final PatientRecord pr = patientService.getPatientRecord(pid);
        if (pr == null) {
            log.error("Patient not found for pid: {}, accountId {}", pid, account.autoAcctId());
            return new Pair<>(StatusCode.PATIENT_NOT_FOUND, null);
        }
        final String deptId = pr.getDeptId();
        final Integer admissionStatus = pr.getAdmissionStatus();

        // 验证评分有效时间
        LocalDateTime effectiveTime = formatTime(
            TimeUtils.fromIso8601String(sgPb.getRecordedAtIso8601(), "UTC"));
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: {}", sgPb.getRecordedAtIso8601());
            return new Pair<>(StatusCode.INVALID_SCORE_RECORD_TIME, null);
        }
        if ((Objects.equals(admissionStatus, PatientConsts.ADMISSION_STATUS_PRE_ADMISSION) ||
             Objects.equals(admissionStatus, PatientConsts.ADMISSION_STATUS_ADMITTED)) &&
            pr.getAdmissionTime() != null && effectiveTime.isBefore(pr.getAdmissionTime())
        ) {
            log.error("Effective time is before admission time: {}, {}",
                effectiveTime, pr.getAdmissionTime());
            return new Pair<>(StatusCode.SCORE_RECORD_TIME_IS_BEFORE_ADMISSION_TIME, null);
        }
        if ((Objects.equals(admissionStatus, PatientConsts.ADMISSION_STATUS_PRE_DISCHARGE) ||
            Objects.equals(admissionStatus, PatientConsts.ADMISSION_STATUS_DISCHARGED)) &&
            pr.getDischargeTime() != null && effectiveTime.isAfter(pr.getDischargeTime())
        ) {
            log.error("Effective time is after discharge time: {}, {}",
                effectiveTime, pr.getDischargeTime());
            return new Pair<>(StatusCode.SCORE_RECORD_TIME_IS_AFTER_DISCHARGE_TIME, null);
        }

        // 验证评分组信息
        final ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for scoreGroupCode: {}", scoreGroupCode);
            return new Pair<>(StatusCode.SCORE_GROUP_NOT_FOUND, null);
        }
        StatusCode validationSC = scoreCalculator.validateScoreGroup(scoreGroupMeta, sgPb);
        if (validationSC != StatusCode.OK) return new Pair<>(validationSC, null);
        StringBuilder patientScoreStr = new StringBuilder();
        validationSC = scoreCalculator.printGroupScore(
            scoreGroupMeta, sgPb.getGroupScore(), patientScoreStr);
        if (validationSC != StatusCode.OK) return new Pair<>(validationSC, null);

        // 获取对应的观察项参数
        String paramCode = scoreCodeParamCodeMap.get(scoreGroupCode);

        return new Pair<>(StatusCode.OK, () -> {
            // 每轮读取旧记录并生成旧、新锁；不重复患者、时间边界及评分校验。
            PatientScore plannedRecord = entityId > 0
                ? PatientScoreJdbc.findActiveRecord(jdbcTemplate, entityId) : null;
            List<Change> changes = new ArrayList<>();
            if (plannedRecord != null) {
                changes.add(new Change(plannedRecord.getPid(), plannedRecord.getEffectiveTime()));
            }
            changes.add(new Change(pid, effectiveTime));
            SnapshotChangePlan snapshotPlan = snapshotService.planChanges(changes);
            return ScoreLockPlan.create(
                plannedRecord, pid, snapshotPlan.departments().getOrDefault(pid, deptId),
                scoreGroupCode, effectiveTime, patientScoreStr.toString(), paramCode,
                snapshotPlan, account.autoAcctId(), account.acctName());
        });
    }

    private boolean isScoreLockPlanCurrent(ScoreLockPlan plan, long recordId) {
        return (recordId <= 0 || plan.matches(
            PatientScoreJdbc.findActiveRecord(jdbcTemplate, recordId)))
            && snapshotService.isPlanCurrent(plan.snapshotPlan());
    }

    private boolean deleteMonitoringRecord(
        Long pid, String paramCode, LocalDateTime time, String accountId, LocalDateTime now
    ) {
        PatientMonitoringRecord record = pmrRepo
            .findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(pid, paramCode, time)
            .orElse(null);
        if (record == null) return false;
        record.setIsDeleted(true);
        record.setDeletedBy(accountId);
        record.setDeletedAt(now);
        pmrRepo.save(record);
        return true;
    }

    private boolean saveMonitoringRecord(
        ScoreGroupPB scoreGroup, String deptId, String paramCode,
        LocalDateTime time, String accountId, LocalDateTime now
    ) {
        Long pid = scoreGroup.getPid();
        String text = scoreGroup.getGroupScoreText();
        String value = ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
            .setValue(GenericValuePB.newBuilder().setStrVal(text)).build());
        PatientMonitoringRecord existing = pmrRepo
            .findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(pid, paramCode, time)
            .orElse(null);
        if (existing != null) {
            log.info("Soft deleting existing monitoring record for pid={}, paramCode={}, time={}",
                pid, paramCode, time);
            existing.setIsDeleted(true);
            existing.setDeleteReason("replaced by new score record");
            existing.setDeletedBy(accountId);
            existing.setDeletedAt(now);
            // 先执行旧行软删除，再 INSERT，满足 PostgreSQL 活动记录的部分唯一索引。
            pmrRepo.saveAndFlush(existing);
        }
        pmrRepo.save(PatientMonitoringRecord.builder()
            .pid(pid).deptId(deptId).monitoringParamCode(paramCode).effectiveTime(time)
            .paramValue(value).paramValueStr(text).source("").note(scoreGroup.getNote())
            .modifiedBy(accountId).modifiedAt(now).isDeleted(false).build());
        return true;
    }

    private void invalidateReports(
        Long pid, String deptId, LocalDateTime previousTime, LocalDateTime time, LocalDateTime now
    ) {
        pnrUtils.updateLatestDataTime(pid, deptId, time, time, now);
        if (previousTime != null && !previousTime.equals(time)) {
            pnrUtils.updateLatestDataTime(pid, deptId, previousTime, previousTime, now);
        }
    }

    private boolean matchesIdentity(ScoreGroupPB request, PatientScore record) {
        return request.getPid() == record.getPid()
            && request.getCode().equals(record.getScoreGroupCode());
    }

    private boolean sameScore(PatientScore first, PatientScore second) {
        return Objects.equals(first.getScore(), second.getScore())
            && Objects.equals(first.getScoreStr(), second.getScoreStr())
            && Objects.equals(first.getEffectiveTime(), second.getEffectiveTime())
            && Objects.equals(first.getNote(), second.getNote())
            && Objects.equals(first.getModifiedBy(), second.getModifiedBy())
            && Objects.equals(first.getRawModifiedByAccountName(),
                second.getRawModifiedByAccountName());
    }

    private static LocalDateTime formatTime(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    public CalcPatientScoreResp calcPatientScore(String calcPatientScoreReqJson) {
        final CalcPatientScoreReq req;

        try {
            req = ProtoUtils.parseJsonToProto(
                calcPatientScoreReqJson, CalcPatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(req.getScoreGroupCode());
        if (scoreGroupMeta == null) {
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 获取各项的分值
        Pair<StatusCode, List<GenericValuePB>> itemScoresPair = scoreCalculator.getScoreItemValues(
            scoreGroupMeta, req.getItemList()
        );
        StatusCode statusCode = itemScoresPair.getFirst();
        if (statusCode != StatusCode.OK) {
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        List<GenericValuePB> scoreItemValues = itemScoresPair.getSecond();

        // 计算分组分数
        GenericValuePB.Builder groupScoreBuilder = GenericValuePB.newBuilder();
        statusCode = scoreCalculator.calcGroupScore(
            scoreGroupMeta, scoreItemValues, groupScoreBuilder);
        if (statusCode != StatusCode.OK) {
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        GenericValuePB groupScore = groupScoreBuilder.build();

        StringBuilder groupScoreTextBuilder = new StringBuilder();
        statusCode = scoreCalculator.printGroupScore(
            scoreGroupMeta, groupScore, groupScoreTextBuilder);
        if (statusCode != StatusCode.OK) {
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        return CalcPatientScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setGroupScore(groupScore)
            .setGroupScoreText(groupScoreTextBuilder.toString())
            .build();
    }

    public Pair<ValueMetaPB, List<TimedGenericValuePB>> fetchScoreGroup(
        Long pid, String scoreGroupCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // Fetch score item metadata
        ScoreGroupMetaPB scoreGroupMetaPb = scoreMetaService
            .getConfiguredScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMetaPb == null) {
            log.error("No score item meta found for code: {}: {}", scoreGroupCode);
            return null;
        }
        ValueMetaPB valueMeta = scoreGroupMetaPb.getValueMeta();

        List<TimedGenericValuePB> origValues = new ArrayList<>();
        for (PatientScore record : patientScoreRepo
            .findByPidAndScoreGroupCodeAndEffectiveTimeBetweenAndIsDeletedFalse(
                pid, scoreGroupCode, queryStartUtc, queryEndUtc
            ).stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime))
            .toList()
        ) {
            ScoreGroupPB scoreGroupPb = ScoreMapper.toScoreGroupPB(record, ZONE_ID);
            if (scoreGroupPb == null) continue;
            GenericValuePB scoreGenericValue = scoreGroupPb.getGroupScore();

            origValues.add(TimedGenericValuePB.newBuilder()
                .setValue(scoreGenericValue)
                .setValueStr(ValueMetaUtils.getValueStrWithUnit(scoreGenericValue, valueMeta))
                .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .build());
        }

        return new Pair<>(valueMeta, origValues);
    }

    private final String ZONE_ID;
    private final Map<String, String> scoreCodeParamCodeMap;
    private final Map<String, MonitoringParamModalOptionsPB> paramCodeModalMap;
    private final ConfigProtoService protoService;
    private final ScoreMetaService scoreMetaService;
    private final ScoreCalculator scoreCalculator;
    private final UserQuery userQuery;
    private final PatientService patientService;
    private final PatientNursingReportInvalidationService pnrUtils;
    private final PatientScoreRepository patientScoreRepo;
    private final MonitoringParamRepository monitoringParamRepo;
    private final PatientMonitoringRecordRepository pmrRepo;
    private final ScoreReader scoreReader;
    private final SnapshotService snapshotService;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
}
