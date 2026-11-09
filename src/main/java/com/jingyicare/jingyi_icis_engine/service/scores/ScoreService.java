package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PatientNursingReportInvalidationService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ScoreService {
    public ScoreService(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired ScoreMetaService scoreMetaService,
        @Autowired ScoreCalculator scoreCalculator,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired MonitoringRecordUtils monitoringRecordUtils,
        @Autowired PatientNursingReportInvalidationService pnrUtils,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Autowired MonitoringParamRepository monitoringParamRepo,
        @Autowired PatientMonitoringRecordRepository pmrRepo,
        @Autowired AccountRepository accountRepo
    ) {
        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.ZONE_ID = protoService.getZoneId();

        this.scorePb = protoService.getConfig().getScore();
        this.SCORE_ITEM_TYPE_SINGLE_SELECTION = scorePb.getEnums().getScoreItemTypeSingleSelection().getId();
        this.SCORE_ITEM_TYPE_MULTI_SELECTION = scorePb.getEnums().getScoreItemTypeMultiSelection().getId();
        this.SCORE_ITEM_AGGREGATOR_SUM = scorePb.getEnums().getScoreItemAggregatorSum().getId();
        this.SCORE_ITEM_AGGREGATOR_MAX = scorePb.getEnums().getScoreItemAggregatorMax().getId();
        this.SCORE_ITEM_AGGREGATOR_FIRST = scorePb.getEnums().getScoreItemAggregatorFirst().getId();
        this.SCORE_GROUP_AGGREGATOR_SUM = scorePb.getEnums().getScoreGroupAggregatorSum().getId();
        this.SCORE_GROUP_AGGREGATOR_FIRST = scorePb.getEnums().getScoreGroupAggregatorFirst().getId();
        this.SCORE_GROUP_PRINTER_DEFAULT = scorePb.getEnums().getScoreGroupPrinterDefault().getId();

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

        this.scoreMetaService = scoreMetaService;
        this.scoreCalculator = scoreCalculator;
        this.userService = userService;
        this.patientService = patientService;
        this.monitoringRecordUtils = monitoringRecordUtils;
        this.pnrUtils = pnrUtils;
        this.patientScoreRepo = patientScoreRepo;
        this.monitoringParamRepo = monitoringParamRepo;
        this.pmrRepo = pmrRepo;
        this.accountRepo = accountRepo;
    }

    @Transactional
    public GetPatientScoresResp getPatientScores(String getPatientScoresReqJson) {
        final GetPatientScoresReq req;

        try {
            req = ProtoUtils.parseJsonToProto(getPatientScoresReqJson, GetPatientScoresReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPatientScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<PatientScore> patientScores = patientScoreRepo.findPatientScores(
            req.getPid(),
            req.getScoreGroupCode(),
            TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC"),
            TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC")
        );

        List<ScoreGroupPB> scoreRecords = patientScores.stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime).reversed())
            .map(record -> ScoreMapper.toProto(record, ZONE_ID))
            .filter(Objects::nonNull)
            .toList();

        return GetPatientScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(scoreRecords)
            .build();
    }

    @Transactional
    public GetPatientScoreResp getPatientScore(String getPatientScoreReqJson) {
        final GetPatientScoreReq req;
        // 解析 JSON 请求
        try {
            req = ProtoUtils.parseJsonToProto(getPatientScoreReqJson, GetPatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(req.getEffectiveTimeIso8601(), "UTC");

        // 查找患者信息
        final Long pid = req.getPid();
        final PatientRecord patientRec = patientService.getPatientRecord(pid);
        if (patientRec == null) {
            log.error("Patient not found for pid: {}", pid);
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRec.getDeptId();

        // 查找部门评分组id: dept_score_groups.id
        String paramCode = req.getMonitoringParamCode();
        MonitoringParam monitoringParam = monitoringParamRepo.findByCode(paramCode).orElse(null);
        if (monitoringParam == null || StrUtils.isBlank(monitoringParam.getUiModalCode())) {
            log.error("Department score group code not found for param code: {}", req.getMonitoringParamCode());
            return GetPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }
        String deptScoreGroupCode = "";
        MonitoringParamModalOptionsPB options = paramCodeModalMap.get(paramCode);
        if (options != null) {
            deptScoreGroupCode = options.getOptionList()
                .stream()
                .filter(option -> Objects.equals(option.getUiModalCode(), monitoringParam.getUiModalCode()))
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
        PatientScore patientScore = patientScoreRepo.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            pid, deptScoreGroupCode, effectiveTime
        ).orElse(null);

        // 构建响应
        GetPatientScoreResp.Builder respBuilder = GetPatientScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setFound(patientScore != null)
            .setScoreGroupMeta(scoreGroupMeta);
        if (patientScore != null) {
            ScoreGroupPB recordPb = ScoreMapper.toProto(patientScore, ZONE_ID);
            if (recordPb != null) respBuilder.setRecord(recordPb);
        }

        return respBuilder.build();
    }

    @Transactional
    public AddPatientScoreResp addPatientScore(String addPatientScoreReqJson) {
        final AddPatientScoreReq req;

        try {
            req = ProtoUtils.parseJsonToProto(addPatientScoreReqJson, AddPatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 提取元数据
        final Long pid = req.getPid();
        final ScoreGroupPB scoreRecordPb = req.getRecord();
        final String scoreGroupCode = scoreRecordPb.getCode();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            scoreRecordPb.getRecordedAtIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: {}", scoreRecordPb.getRecordedAtIso8601());
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_SCORE_RECORD_TIME))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRec = patientService.getPatientRecord(pid);
        if (patientRec == null) {
            log.error("Patient not found for pid: {}, accountId {}", pid, accountId);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRec.getDeptId();

        final ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for scoreGroupCode: {}", scoreGroupCode);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }

        if (patientRec.getAdmissionTime() != null && effectiveTime.isBefore(patientRec.getAdmissionTime())) {
            log.error("Effective time is before admission time: {}, {}", effectiveTime, patientRec.getAdmissionTime());
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_RECORD_TIME_IS_BEFORE_ADMISSION_TIME))
                .build();
        }
        if (patientRec.getDischargeTime() != null && effectiveTime.isAfter(patientRec.getDischargeTime())) {
            log.error("Effective time is after discharge time: {}, {}", effectiveTime, patientRec.getDischargeTime());
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_RECORD_TIME_IS_AFTER_DISCHARGE_TIME))
                .build();
        }

        // Check if the patient score already exists
        PatientScore patientScore = patientScoreRepo.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            pid, scoreGroupCode, effectiveTime).orElse(null);
        if (patientScore != null) {
            log.error("Patient score already exists for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
                pid, scoreGroupCode, effectiveTime);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SCORE_ALREADY_EXISTS))
                .build();
        }

        // 校验总评分和评分项是否一致
        StatusCode statusCode = scoreCalculator.validateScoreGroup(scoreGroupMeta, scoreRecordPb);
        if (statusCode != StatusCode.OK) {
            log.error("Group score validation failed for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
                pid, scoreGroupCode, effectiveTime);
            return AddPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // add a patient score.
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        Account account = accountRepo.findByAccountIdAndIsDeletedFalse(scoreRecordPb.getRecordedBy()).orElse(null);
        String modifiedBy = account != null ? account.getId().toString() : "";
        String modifiedByAccountName = account != null ? account.getName() : "";
        if (!StrUtils.isBlank(modifiedBy)) {
            modifiedByAccountName = userService.getNameByAutoId(modifiedBy);
        }
        patientScore = PatientScore.builder()
            .pid(pid)
            .scoreGroupCode(scoreGroupCode)
            .score(ProtoUtils.encodeScoreGroupPB(scoreRecordPb))
            .scoreStr(scoreRecordPb.getGroupScoreText())
            .effectiveTime(effectiveTime)
            .note(scoreRecordPb.getNote())
            .isDeleted(false)
            .modifiedBy(modifiedBy)
            .modifiedByAccountName(modifiedByAccountName)
            .modifiedAt(nowUtc)
            .build();
        patientScore = patientScoreRepo.save(patientScore);
        log.info("Added patient score for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
            pid, scoreGroupCode, effectiveTime);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        return AddPatientScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(patientScore.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientScore(String updatePatientScoreReqJson) {
        final UpdatePatientScoreReq req;

        try {
            req = ProtoUtils.parseJsonToProto(updatePatientScoreReqJson, UpdatePatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 提取关键信息
        ScoreGroupPB scoreGroup = req.getRecord();
        String scoreGroupCode = scoreGroup.getCode();
        ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for group code: {}", scoreGroupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }
        StatusCode statusCode = scoreCalculator.validateScoreGroup(scoreGroupMeta, scoreGroup);
        if (statusCode != StatusCode.OK) {
            log.error("Group score validation failed for id: {}, group code: {}, status code: {}",
                scoreGroup.getId(), scoreGroupCode, statusCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // 更新患者评分
        PatientScore patientScore = patientScoreRepo.findById(scoreGroup.getId()).orElse(null);
        if (patientScore == null) {
            log.error("Patient score not found for id: {}, accountId {}",
                scoreGroup.getId(), accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SCORE_NOT_FOUND))
                .build();
        }

        patientScore.setScore(ProtoUtils.encodeScoreGroupPB(scoreGroup));
        patientScore.setScoreStr(scoreGroup.getGroupScoreText());
        patientScore.setNote(scoreGroup.getNote());

        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            scoreGroup.getRecordedAtIso8601(), "UTC");
        if (effectiveTime != null) {
            patientScore.setEffectiveTime(effectiveTime);
        } else {
            effectiveTime = patientScore.getEffectiveTime();
        }

        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        String modifiedBy = scoreGroup.getRecordedBy();
        String modifiedByAccountName = null;
        if (!StrUtils.isBlank(modifiedBy)) {
            modifiedByAccountName = userService.getNameByAutoId(modifiedBy);
        }
        patientScore.setModifiedBy(modifiedBy);
        patientScore.setModifiedByAccountName(modifiedByAccountName);
        patientScore.setModifiedAt(nowUtc);
        patientScoreRepo.save(patientScore);
        log.info("Updated patient score for id: {}, accountId: {}", scoreGroup.getId(), accountId);

        // 获取患者信息
        final Long pid = patientScore.getPid();
        final PatientRecord patientRec = patientService.getPatientRecord(pid);
        if (patientRec == null) {
            log.error("Patient not found for pid: {}, accountId {}", pid, accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRec.getDeptId();
        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public SavePatientScoreResp savePatientScore(String savePatientScoreReqJson) {
        final SavePatientScoreReq req;
        // 解析 JSON 请求
        try {
            req = ProtoUtils.parseJsonToProto(savePatientScoreReqJson, SavePatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前账号
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        final String accountName = accountWithAutoId.getSecond();

        // 提取关键参数
        final Long pid = req.getPid();
        final PatientRecord patientRec = patientService.getPatientRecord(pid);
        if (patientRec == null) {
            log.error("Patient not found for pid: {}, accountId {}", pid, accountId);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRec.getDeptId();
        final ScoreGroupPB scoreGroup = req.getRecord();
        final String scoreGroupCode = scoreGroup.getCode();
        String monitoringParamCode = scoreCodeParamCodeMap.get(scoreGroupCode);
        if (monitoringParamCode != null) {
            MonitoringParam monitoringParam = monitoringParamRepo.findByCode(monitoringParamCode).orElse(null);
            if (monitoringParam == null) log.error("Monitoring param not found for code: {}", monitoringParamCode);
            else if (StrUtils.isBlank(monitoringParam.getUiModalCode())) monitoringParamCode = null;
        }
        final ScoreGroupMetaPB scoreGroupMeta = scoreMetaService
            .getConfiguredScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for scoreGroupCode: {}", scoreGroupCode);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }
        ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();
        final GenericValuePB groupScore = scoreGroup.getGroupScore();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            scoreGroup.getRecordedAtIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: {}", scoreGroup.getRecordedAtIso8601());
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_SCORE_RECORD_TIME))
                .build();
        }
        if (effectiveTime.isBefore(patientRec.getAdmissionTime())) {
            log.error("Effective time is before admission time: {}, {}", effectiveTime, patientRec.getAdmissionTime());
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_RECORD_TIME_IS_BEFORE_ADMISSION_TIME))
                .build();
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 检查患者评分组是否一致
        StatusCode statusCode = scoreCalculator.validateScoreGroup(scoreGroupMeta, scoreGroup);
        if (statusCode != StatusCode.OK) {
            log.error("Group score validation failed for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
                pid, scoreGroupCode, effectiveTime);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // 计算结果数据
        StringBuilder groupScoreTextBuilder = new StringBuilder();
        statusCode = scoreCalculator.printGroupScore(scoreGroupMeta, groupScore, groupScoreTextBuilder);
        if (statusCode != StatusCode.OK) {
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        String groupScoreText = groupScoreTextBuilder.toString();

        // 查找患者评分
        PatientScore patientScore = patientScoreRepo.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            pid, scoreGroupCode, effectiveTime).orElse(null);
        if (patientScore != null) {  // 更新
            patientScore.setScore(ProtoUtils.encodeScoreGroupPB(scoreGroup));
            patientScore.setScoreStr(groupScoreText);
            patientScore.setNote(scoreGroup.getNote());
            patientScore.setModifiedBy(accountId);
            patientScore.setModifiedByAccountName(accountName);
            patientScore.setModifiedAt(nowUtc);
            log.info("Updated patient score for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
                pid, scoreGroupCode, effectiveTime);
        } else {  // 新增
            patientScore = PatientScore.builder()
                .pid(pid)
                .scoreGroupCode(scoreGroupCode)
                .score(ProtoUtils.encodeScoreGroupPB(scoreGroup))
                .scoreStr(groupScoreText)
                .effectiveTime(effectiveTime)
                .note(scoreGroup.getNote())
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedByAccountName(accountName)
                .modifiedAt(nowUtc)
                .build();
            log.info("Added patient score for pid: {}, scoreGroupCode: {}, effectiveTime: {}",
                pid, scoreGroupCode, effectiveTime);
        }
        patientScoreRepo.save(patientScore);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        // 更新观察项
        if (monitoringParamCode != null) {
            // 更新观察项
            PatientMonitoringRecord monitoringRecord = PatientMonitoringRecord.builder()
                .pid(pid)
                .deptId(deptId)
                .monitoringParamCode(monitoringParamCode)
                .effectiveTime(effectiveTime)
                .paramValue(ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                    .setValue(GenericValuePB.newBuilder().setStrVal(groupScoreText).build())
                    .build()
                ))
                .paramValueStr(groupScoreText)
                .source("")
                .note(scoreGroup.getNote())
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(nowUtc)
                .build();
            monitoringRecordUtils.saveRecordJdbc(monitoringRecord);
        }

        return SavePatientScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setGroupScoreText(groupScoreText)
            .build();
    }

    @Transactional
    public GenericResp deletePatientScore(String deletePatientScoreReqJson) {
        final DeletePatientScoreReq req;

        try {
            req = ProtoUtils.parseJsonToProto(deletePatientScoreReqJson, DeletePatientScoreReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        PatientScore patientScore = patientScoreRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (patientScore == null) {
            log.error("Patient score not found for id: {}, accountId {}", req.getId(), accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        patientScore.setIsDeleted(true);
        patientScore.setDeletedBy(accountId);
        patientScore.setDeletedAt(TimeUtils.getNowUtc());
        patientScoreRepo.save(patientScore);
        log.info("Deleted patient score for id: {}, accountId: {}", req.getId(), accountId);

        // 获取患者信息
        final Long pid = patientScore.getPid();
        final LocalDateTime effectiveTime = patientScore.getEffectiveTime();
        final PatientRecord patientRec = patientService.getPatientRecord(pid);
        if (patientRec == null) {
            log.error("Patient not found for pid: {}, accountId {}", pid, accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRec.getDeptId();

        // 处理观察项
        if (req.getByMonitoring()) {
            String paramCode = scoreCodeParamCodeMap.get(patientScore.getScoreGroupCode());
            PatientMonitoringRecord pmrToDel = pmrRepo
                .findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(pid, paramCode, effectiveTime)
                .orElse(null);
            if (pmrToDel != null) {
                pmrToDel.setIsDeleted(true);
                pmrToDel.setDeletedBy(accountId);
                pmrToDel.setDeletedAt(TimeUtils.getNowUtc());
                pmrRepo.save(pmrToDel);
                log.info("delete monitoring param: paramCode {}, effectiveTime {}, pid {}", paramCode, effectiveTime, pid);
            }
        }

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, TimeUtils.getNowUtc());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public CalcPatientScoreResp calcPatientScore(String calcPatientScoreReqJson) {
        final CalcPatientScoreReq req;

        try {
            req = ProtoUtils.parseJsonToProto(calcPatientScoreReqJson, CalcPatientScoreReq.newBuilder());
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
        statusCode = scoreCalculator.calcGroupScore(scoreGroupMeta, scoreItemValues, groupScoreBuilder);
        if (statusCode != StatusCode.OK) {
            return CalcPatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        GenericValuePB groupScore = groupScoreBuilder.build();

        StringBuilder groupScoreTextBuilder = new StringBuilder();
        statusCode = scoreCalculator.printGroupScore(scoreGroupMeta, groupScore, groupScoreTextBuilder);
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

    @Transactional
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
            ScoreGroupPB scoreGroupPb = ScoreMapper.toProto(record, ZONE_ID);
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
    private final Integer SCORE_ITEM_TYPE_SINGLE_SELECTION;
    private final Integer SCORE_ITEM_TYPE_MULTI_SELECTION;
    private final Integer SCORE_ITEM_AGGREGATOR_SUM;
    private final Integer SCORE_ITEM_AGGREGATOR_MAX;
    private final Integer SCORE_ITEM_AGGREGATOR_FIRST;
    private final Integer SCORE_GROUP_AGGREGATOR_SUM;
    private final Integer SCORE_GROUP_AGGREGATOR_FIRST;
    private final Integer SCORE_GROUP_PRINTER_DEFAULT;
    private final Map<String, String> scoreCodeParamCodeMap;
    private final Map<String, MonitoringParamModalOptionsPB> paramCodeModalMap;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final ScorePB scorePb;
    private final ScoreMetaService scoreMetaService;
    private final ScoreCalculator scoreCalculator;
    private final UserService userService;
    private final PatientService patientService;
    private final MonitoringRecordUtils monitoringRecordUtils;
    private final PatientNursingReportInvalidationService pnrUtils;
    private final PatientScoreRepository patientScoreRepo;
    private final MonitoringParamRepository monitoringParamRepo;
    private final PatientMonitoringRecordRepository pmrRepo;
    private final AccountRepository accountRepo;
}
