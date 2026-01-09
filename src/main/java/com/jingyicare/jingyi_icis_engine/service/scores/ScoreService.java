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
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ScoreService {
    public ScoreService(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired ScoreConfig scoreConfig,
        @Autowired ScoreCalculator scoreCalculator,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired MonitoringRecordUtils monitoringRecordUtils,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Autowired MonitoringParamRepository monitoringParamRepo,
        @Autowired AccountRepository accountRepo
    ) {
        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.ZONE_ID = protoService.getConfig().getZoneId();

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

        this.allScoreGroupMeta = new ArrayList<>();
        for (ScoreGroupMetaPB meta : scorePb.getGroupList()) {
            allScoreGroupMeta.add(meta.toBuilder().setOrigName(meta.getName()).build());
        }
        allScoreGroupMeta.add(
            ScoreGroupMetaPB.newBuilder()
                .setId(10000)
                .setCode("vte_caprini")
                .setName("VTE Caprini评分")
                .setOrigName("VTE Caprini评分")
                .setDisplayOrder(9999)
                .build()
        );

        this.scoreConfig = scoreConfig;
        this.scoreCalculator = scoreCalculator;
        this.userService = userService;
        this.patientService = patientService;
        this.monitoringRecordUtils = monitoringRecordUtils;
        this.pnrUtils = pnrUtils;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.patientScoreRepo = patientScoreRepo;
        this.monitoringParamRepo = monitoringParamRepo;
        this.accountRepo = accountRepo;
    }

    @Transactional
    public GetScoreGroupMetaResp getScoreGroupMeta(String getScoreGroupMetaReqJson) {
        final GetScoreGroupMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(getScoreGroupMetaReqJson, GetScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        if (StrUtils.isBlank(req.getDeptId())) {
            return GetScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .addAllScoreGroupMeta(allScoreGroupMeta)
                .build();
        }

        List<ScoreGroupMetaPB> scoreGroupMetaList = new ArrayList<>();
        scoreGroupMetaList.addAll(
            deptScoreGroupRepo
                .findByDeptIdAndIsDeletedFalse(req.getDeptId())
                .stream()
                .sorted(Comparator.comparing(DeptScoreGroup::getDisplayOrder))
                .map(deptScoreGroup -> {
                    ScoreGroupMetaPB scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
                if (scoreGroupMeta == null) {
                    scoreGroupMeta = scoreConfig.getScoreGroupMeta(deptScoreGroup.getScoreGroupCode());
                }
                scoreGroupMeta = scoreGroupMeta.toBuilder()
                    .setId(deptScoreGroup.getId())
                    .setDisplayOrder(deptScoreGroup.getDisplayOrder())
                    .build();
                return scoreGroupMeta;
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ScoreGroupMetaPB::getDisplayOrder)
                .thenComparing(ScoreGroupMetaPB::getId)
            )
            .toList()
        );

        return GetScoreGroupMetaResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllScoreGroupMeta(scoreGroupMetaList)
            .build();
    }

    @Transactional
    public AddDeptScoreGroupResp addDeptScoreGroup(String addDeptScoreGroupReqJson) {
        final AddDeptScoreGroupReq req;

        try {
            req = ProtoUtils.parseJsonToProto(addDeptScoreGroupReqJson, AddDeptScoreGroupReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddDeptScoreGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return AddDeptScoreGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // Get current time
        final LocalDateTime now = TimeUtils.getNowUtc();

        // Check if the department score group already exists
        if (deptScoreGroupRepo.existsByDeptIdAndScoreGroupCodeAndIsDeletedFalse(req.getDeptId(), req.getScoreGroupCode())) {
            log.error("Department score group already exists for deptId: {}, scoreGroupCode: {}", req.getDeptId(), req.getScoreGroupCode());
            return AddDeptScoreGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS))
                .build();
        }

        ScoreGroupMetaPB scoreGroupMeta = allScoreGroupMeta.stream()
            .filter(meta -> meta.getCode().equals(req.getScoreGroupCode()))
            .findFirst()
            .orElse(null);
        if (scoreGroupMeta == null) {
            return AddDeptScoreGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }
        Integer id = saveOrUpdateDeptScoreGroup(null, req.getDeptId(), req.getScoreGroupCode(), scoreGroupMeta, accountId, now);

        return AddDeptScoreGroupResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(id)
            .build();
    }

    @Transactional
    public AddDeptScoreGroupsResp addDeptScoreGroups(String addDeptScoreGroupsReqJson) {
        final AddDeptScoreGroupsReq req;

        try {
            req = ProtoUtils.parseJsonToProto(addDeptScoreGroupsReqJson, AddDeptScoreGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddDeptScoreGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return AddDeptScoreGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // Get current time
        final LocalDateTime now = TimeUtils.getNowUtc();

        // Check if the department score group already exists
        for (DeptScoreGroupPB deptScoreGroup : req.getGroupList()) {
            if (deptScoreGroupRepo.existsByDeptIdAndScoreGroupCodeAndIsDeletedFalse(
                req.getDeptId(), deptScoreGroup.getScoreGroupCode())
            ) {
                log.error("Department score group already exists for deptId: {}, scoreGroupCode: {}",
                    req.getDeptId(), deptScoreGroup.getScoreGroupCode()
                );
                return AddDeptScoreGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS))
                    .build();
            }
        }

        List<Integer> addedIds = new ArrayList<>();
        Map<String/*groupCode*/, ScoreGroupMetaPB> scoreGroupMetaMap = allScoreGroupMeta
            .stream().collect(Collectors.toMap(ScoreGroupMetaPB::getCode, meta -> meta));
        for (DeptScoreGroupPB deptScoreGroupPb : req.getGroupList()) {
            ScoreGroupMetaPB scoreGroupMeta = scoreGroupMetaMap.get(deptScoreGroupPb.getScoreGroupCode());
            if (scoreGroupMeta == null && !deptScoreGroupPb.getScoreGroupCode().equals("vte_caprini")) {
                return AddDeptScoreGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                    .build();
            }
            Integer id = saveOrUpdateDeptScoreGroup(
                null, req.getDeptId(), deptScoreGroupPb.getScoreGroupCode(),
                scoreGroupMeta, accountId, now
            );
            addedIds.add(id);
        }

        return AddDeptScoreGroupsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllId(addedIds)
            .build();
    }

    @Transactional
    public GenericResp deleteDeptScoreGroup(String deleteDeptScoreGroupReqJson) {
        final DeleteDeptScoreGroupReq req;

        try {
            req = ProtoUtils.parseJsonToProto(deleteDeptScoreGroupReqJson, DeleteDeptScoreGroupReq.newBuilder());
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

        // Get current time
        final LocalDateTime now = TimeUtils.getNowUtc();

        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (deptScoreGroup == null) {
            log.error("Department score group not found for id: {}, accountId {}", req.getId(), accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        deptScoreGroup.setIsDeleted(true);
        deptScoreGroup.setDeletedBy(accountId);
        deptScoreGroup.setDeletedAt(now);
        deptScoreGroupRepo.save(deptScoreGroup);
        log.info("Deleted dept_score_group for id: {}, accountId: {}", req.getId(), accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderDeptScoreGroups(String reorderDeptScoreGroupsReqJson) {
        final ReorderDeptScoreGroupsReq req;

        try {
            req = ProtoUtils.parseJsonToProto(reorderDeptScoreGroupsReqJson, ReorderDeptScoreGroupsReq.newBuilder());
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

        // Get current time
        final LocalDateTime now = TimeUtils.getNowUtc();

        // Find display_order modified
        List<DeptScoreGroup> deptScoreGroups = deptScoreGroupRepo.findByDeptIdAndIsDeletedFalse(req.getDeptId());
        Map<Integer, DeptScoreGroup> deptScoreGroupMap = deptScoreGroups.stream()
            .collect(Collectors.toMap(DeptScoreGroup::getId, deptScoreGroup -> deptScoreGroup));

        List<DeptScoreGroup> reorderedDeptScoreGroups = new ArrayList<>();
        for (ScoreGroupOrder order : req.getGroupOrderList()) {
            DeptScoreGroup deptScoreGroup = deptScoreGroupMap.get(order.getId());
            if (deptScoreGroup == null) {
                log.error("Department score group not found for id: {}, accountId {}", order.getId(), accountId);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                    .build();
            }
            if (Objects.equals(deptScoreGroup.getDisplayOrder(), order.getDisplayOrder())) {
                continue;
            }
            deptScoreGroup.setDisplayOrder(order.getDisplayOrder());
            deptScoreGroup.setModifiedBy(accountId);
            deptScoreGroup.setModifiedAt(now);
            reorderedDeptScoreGroups.add(deptScoreGroup);
        }

        for (DeptScoreGroup deptScoreGroup : reorderedDeptScoreGroups) {
            deptScoreGroupRepo.save(deptScoreGroup);
            log.info("Reordered dept_score_group for id: {}, accountId: {}", deptScoreGroup.getId(), accountId);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
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

        List<ScoreRecordPB> scoreRecords = patientScores.stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime).reversed())
            .map(patientScore -> ScoreRecordPB.newBuilder()
                .setId(patientScore.getId())
                .setGroup(ProtoUtils.decodeScoreGroupPB(patientScore.getScore()))
                .setRecordedBy(patientScore.getModifiedBy())
                .setRecordedByAccountName(patientScore.getModifiedByAccountName())
                .setRecordedAtIso8601(TimeUtils.toIso8601String(patientScore.getEffectiveTime(), ZONE_ID))
                .setNote(patientScore.getNote())
                .build()
            )
            .filter(scoreRecordPb -> scoreRecordPb.getGroup() != null)
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
        ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(deptScoreGroupCode);
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
            respBuilder.setRecord(ScoreRecordPB.newBuilder()
                .setId(patientScore.getId())
                .setGroup(ProtoUtils.decodeScoreGroupPB(patientScore.getScore()))
                .setRecordedBy(patientScore.getModifiedBy())
                .setRecordedByAccountName(patientScore.getModifiedByAccountName())
                .setRecordedAtIso8601(TimeUtils.toIso8601String(patientScore.getEffectiveTime(), ZONE_ID))
                .setNote(patientScore.getNote())
                .build());
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
        final String scoreGroupCode = req.getScoreGroupCode();
        final ScoreRecordPB scoreRecordPb = req.getRecord();
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

        final ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(scoreGroupCode);
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
        StatusCode statusCode = scoreCalculator.validateScoreGroup(scoreGroupMeta, scoreRecordPb.getGroup());
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
            .score(ProtoUtils.encodeScoreGroupPB(scoreRecordPb.getGroup()))
            .scoreStr(scoreRecordPb.getGroup().getGroupScoreText())
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
        ScoreGroupPB scoreGroup = req.getGroup();
        String scoreGroupCode = scoreGroup.getCode();
        ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for group code: {}", scoreGroupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }
        StatusCode statusCode = scoreCalculator.validateScoreGroup(scoreGroupMeta, scoreGroup);
        if (statusCode != StatusCode.OK) {
            log.error("Group score validation failed for id: {}, group code: {}, status code: {}",
                req.getId(), scoreGroupCode, statusCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // 更新患者评分
        PatientScore patientScore = patientScoreRepo.findById(req.getId()).orElse(null);
        if (patientScore == null) {
            log.error("Patient score not found for id: {}, accountId {}", req.getId(), accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SCORE_NOT_FOUND))
                .build();
        }

        patientScore.setScore(ProtoUtils.encodeScoreGroupPB(req.getGroup()));
        patientScore.setScoreStr(req.getGroup().getGroupScoreText());
        patientScore.setNote(req.getNote());

        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            req.getRecordedAtIso8601(), "UTC");
        if (effectiveTime != null) {
            patientScore.setEffectiveTime(effectiveTime);
        } else {
            effectiveTime = patientScore.getEffectiveTime();
        }

        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        String modifiedBy = req.getRecordedBy();
        String modifiedByAccountName = null;
        if (!StrUtils.isBlank(modifiedBy)) {
            modifiedByAccountName = userService.getNameByAutoId(modifiedBy);
        }
        patientScore.setModifiedBy(modifiedBy);
        patientScore.setModifiedByAccountName(modifiedByAccountName);
        patientScore.setModifiedAt(nowUtc);
        patientScoreRepo.save(patientScore);
        log.info("Updated patient score for id: {}, accountId: {}", req.getId(), accountId);

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
        final String scoreGroupCode = req.getScoreGroupCode();
        String monitoringParamCode = scoreCodeParamCodeMap.get(scoreGroupCode);
        if (monitoringParamCode != null) {
            MonitoringParam monitoringParam = monitoringParamRepo.findByCode(monitoringParamCode).orElse(null);
            if (monitoringParam == null) log.error("Monitoring param not found for code: {}", monitoringParamCode);
            else if (StrUtils.isBlank(monitoringParam.getUiModalCode())) monitoringParamCode = null;
        }
        final ScoreRecordPB scoreRecordPb = req.getRecord();
        final ScoreGroupPB scoreGroup = scoreRecordPb.getGroup();
        final ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(scoreGroupCode);
        if (scoreGroupMeta == null) {
            log.error("Score group meta not found for scoreGroupCode: {}", scoreGroupCode);
            return SavePatientScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }
        ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();
        final GenericValuePB groupScore = scoreGroup.getGroupScore();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            scoreRecordPb.getRecordedAtIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Failed to parse effective time: {}", scoreRecordPb.getRecordedAtIso8601());
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
            patientScore.setNote(scoreRecordPb.getNote());
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
                .note(scoreRecordPb.getNote())
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
                .note(scoreRecordPb.getNote())
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

        final ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(req.getScoreGroupCode());
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
    public GetOneScoreGroupMetaResp getOneScoreGroupMeta(String getOneScoreGroupMetaReqJson) {
        final GetOneScoreGroupMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(getOneScoreGroupMetaReqJson, GetOneScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetOneScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取关键参数
        String deptId = req.getDeptId();
        String code = req.getCode();

        // 查找对应的元数据
        ScoreGroupMetaPB scoreGroupMeta = null;
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, code)
            .orElse(null);
        if (deptScoreGroup != null) {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(req.getCode());
        }
        if (scoreGroupMeta == null) {
            return GetOneScoreGroupMetaResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_NOT_FOUND))
                .build();
        }

        return GetOneScoreGroupMetaResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setScoreGroupMeta(scoreGroupMeta)
            .build();
    }

    @Transactional
    public GenericResp updateScoreGroupMeta(String updateScoreGroupMetaReqJson) {
        final UpdateScoreGroupMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(updateScoreGroupMetaReqJson, UpdateScoreGroupMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String code = req.getScoreGroupCode();
        String name = req.getName();
        Boolean measureMultiSelection = req.getMeasureMultiSelection();
        String notes = req.getNotes();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, code)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(code);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, code);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 重新设置元数据
        ScoreGroupMetaPB.Builder scoreGroupMetaBuilder = scoreGroupMeta.toBuilder();
        if (req.getUpdateName()) {
            scoreGroupMetaBuilder.setName(name);
        }
        if (req.getUpdateMeasureMultiSelection()) {
            scoreGroupMetaBuilder.setMeasureMultiSelection(measureMultiSelection);
        }
        if (req.getUpdateNotes()) {
            scoreGroupMetaBuilder.setNotes(notes);
        }
        scoreGroupMeta = scoreGroupMetaBuilder.build();

        // 保存元数据
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, code, scoreGroupMeta, accountId, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updateScoreItemName(String updateScoreItemNameReqJson) {
        final UpdateScoreItemNameReq req;

        try {
            req = ProtoUtils.parseJsonToProto(updateScoreItemNameReqJson, UpdateScoreItemNameReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String groupCode = req.getScoreGroupCode();
        String itemCode = req.getScoreItemCode();
        String name = req.getName();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, groupCode)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(groupCode);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, groupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 更新评分项
        List<ScoreItemMetaPB> updatedItems = new ArrayList<>();
        boolean itemFound = false;
        for (ScoreItemMetaPB item : scoreGroupMeta.getItemList()) {
            ScoreItemMetaPB newItem = item;
            if (item.getCode().equals(itemCode)) {
                itemFound = true;
                newItem = item.toBuilder().setName(name).build();
            }
            updatedItems.add(newItem);
        }
        if (!itemFound) {
            log.error("Score item not found for deptId: {}, groupCode: {}, itemCode: {}", deptId, groupCode, itemCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_ITEM_NOT_FOUND))
                .build();
        }
        scoreGroupMeta = scoreGroupMeta.toBuilder().clearItem().addAllItem(updatedItems).build();

        // 保存评分元数据
        scoreGroupMeta = scoreGroupMeta.toBuilder().clearItem().addAllItem(updatedItems).build();
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, groupCode, scoreGroupMeta, accountId, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updateScoreOptionName(String updateScoreOptionNameReqJson) {
        final UpdateScoreOptionNameReq req;

        try {
            req = ProtoUtils.parseJsonToProto(updateScoreOptionNameReqJson, UpdateScoreOptionNameReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String groupCode = req.getScoreGroupCode();
        String itemCode = req.getScoreItemCode();
        String optionCode = req.getScoreOptionCode();
        String name = req.getName();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, groupCode)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(groupCode);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, groupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 查找评分项
        List<ScoreItemMetaPB> updatedItems = new ArrayList<>();
        boolean optionFound = false;
        for (ScoreItemMetaPB item : scoreGroupMeta.getItemList()) {
            ScoreItemMetaPB newItem = item;
            if (item.getCode().equals(itemCode)) {
                // 查找评分选项
                List<ScoreOptionPB> updatedOptions = new ArrayList<>();
                for (ScoreOptionPB option : item.getOptionList()) {
                    if (option.getCode().equals(optionCode)) {
                        updatedOptions.add(option.toBuilder().setName(name).build());
                        optionFound = true;
                    } else {
                        updatedOptions.add(option);
                    }
                }
                newItem = item.toBuilder().clearOption().addAllOption(updatedOptions).build();
            }
            updatedItems.add(newItem);
        }
        if (!optionFound) {
            log.error("Score option not found for deptId: {}, groupCode: {}, itemCode: {}, optionCode: {}",
                deptId, groupCode, itemCode, optionCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_OPTION_NOT_FOUND))
                .build();
        }
        scoreGroupMeta = scoreGroupMeta.toBuilder().clearItem().addAllItem(updatedItems).build();

        // 保存评分元数据
        scoreGroupMeta = scoreGroupMeta.toBuilder().clearItem().addAllItem(updatedItems).build();
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, groupCode, scoreGroupMeta, accountId, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp addScoreGroupMeasureMeta(String addScoreGroupMeasureMetaReqJson) {
        final AddScoreGroupMeasureMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(addScoreGroupMeasureMetaReqJson, AddScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String groupCode = req.getScoreGroupCode();
        ScoreGroupMeasureMetaPB measurePb = req.getMeasure();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, groupCode)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(groupCode);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, groupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 检查措施是否存在
        int id = 1;
        for (ScoreGroupMeasureMetaPB existingMeasure : scoreGroupMeta.getMeasureList()) {
            if (existingMeasure.getName().equals(measurePb.getName())) {
                log.error("Measure already exists for deptId: {}, groupCode: {}, measure: {}",
                    deptId, groupCode, measurePb.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_MEASURE_ALREADY_EXISTS))
                    .build();
            }
            if (existingMeasure.getId() >= id) {
                id = existingMeasure.getId() + 1;  // 确保新措施的ID唯一
            }
        }

        // 新增措施并保存
        scoreGroupMeta = scoreGroupMeta.toBuilder()
            .addMeasure(measurePb.toBuilder().setId(id).build())
            .build();
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, groupCode, scoreGroupMeta, accountId, nowUtc);


        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp updateScoreGroupMeasureMeta(String updateScoreGroupMeasureMetaReqJson) {
        final UpdateScoreGroupMeasureMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(updateScoreGroupMeasureMetaReqJson, UpdateScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String groupCode = req.getScoreGroupCode();
        ScoreGroupMeasureMetaPB measurePb = req.getMeasure();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, groupCode)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(groupCode);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, groupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 检查措施是否存在
        List<ScoreGroupMeasureMetaPB> updatedMeasures = new ArrayList<>();
        boolean found = false;
        for (ScoreGroupMeasureMetaPB existingMeasure : scoreGroupMeta.getMeasureList()) {
            boolean toUpdate = existingMeasure.getId() == measurePb.getId();
            updatedMeasures.add(toUpdate ? measurePb : existingMeasure);
            if (toUpdate) {
                found = true;
            }
        }
        if (!found) {
            log.error("Measure not found for deptId: {}, groupCode: {}, measure: {}",
                deptId, groupCode, measurePb.getName());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.SCORE_GROUP_MEASURE_NOT_FOUND))
                .build();
        }

        // 新增措施并保存
        scoreGroupMeta = scoreGroupMeta.toBuilder()
            .clearMeasure().addAllMeasure(updatedMeasures).build();
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, groupCode, scoreGroupMeta, accountId, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteScoreGroupMeasureMeta(String deleteScoreGroupMeasureMetaReqJson) {
        final DeleteScoreGroupMeasureMetaReq req;

        try {
            req = ProtoUtils.parseJsonToProto(deleteScoreGroupMeasureMetaReqJson, DeleteScoreGroupMeasureMetaReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键参数
        String deptId = req.getDeptId();
        String groupCode = req.getScoreGroupCode();
        String measureName = req.getMeasureName();

        // 查找元数据
        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, groupCode)
            .orElse(null);
        ScoreGroupMetaPB scoreGroupMeta = null;
        if (deptScoreGroup == null || StrUtils.isBlank(deptScoreGroup.getScoreGroupMeta())) {
            scoreGroupMeta = scoreConfig.getScoreGroupMeta(groupCode);
        } else {
            scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        }
        if (scoreGroupMeta == null) {
            log.error("Failed to decode score group meta for deptId: {}, code: {}", deptId, groupCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPT_SCORE_GROUP_NOT_FOUND))
                .build();
        }

        // 检查措施是否存在
        List<ScoreGroupMeasureMetaPB> updatedMeasures = new ArrayList<>();
        boolean found = false;
        for (ScoreGroupMeasureMetaPB existingMeasure : scoreGroupMeta.getMeasureList()) {
            if (existingMeasure.getName().equals(measureName)) {
                found = true;
            } else {
                updatedMeasures.add(existingMeasure);
            }
        }
        if (!found) {
            log.error("Measure not found for deptId: {}, groupCode: {}, measure: {}",
                deptId, groupCode, measureName);
            return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
        }

        // 新增措施并保存
        scoreGroupMeta = scoreGroupMeta.toBuilder()
            .clearMeasure().addAllMeasure(updatedMeasures).build();
        saveOrUpdateDeptScoreGroup(deptScoreGroup, deptId, groupCode, scoreGroupMeta, accountId, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public Pair<ValueMetaPB, List<TimedGenericValuePB>> fetchScoreGroup(
        Long pid, String scoreGroupCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // Fetch score item metadata
        ScoreGroupMetaPB scoreGroupMetaPb = scoreConfig.getScoreGroupMeta(scoreGroupCode);
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
            ScoreGroupPB scoreGroupPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
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

    private Integer saveOrUpdateDeptScoreGroup(
        DeptScoreGroup deptScoreGroup, String deptId, String code,
        ScoreGroupMetaPB scoreGroupMeta, String accountId, LocalDateTime nowUtc
    ) {
        String scoreGroupMetaStr = ProtoUtils.encodeScoreGroupMetaPB(scoreGroupMeta);
        if (deptScoreGroup == null) {
            // 查找displayOrder
            Integer displayOrder = 1;
            List<DeptScoreGroup> existingGroups = deptScoreGroupRepo.findByDeptIdAndIsDeletedFalse(deptId);
            for (DeptScoreGroup group : existingGroups) {
                if (group.getDisplayOrder() >= displayOrder) {
                    displayOrder = group.getDisplayOrder() + 1;
                }
            }

            // 设置displayOrder等
            deptScoreGroup = DeptScoreGroup.builder()
                .deptId(deptId)
                .scoreGroupCode(code)
                .scoreGroupMeta(scoreGroupMetaStr)
                .displayOrder(displayOrder)
                .isDeleted(false)
                .build();
        } else {
            deptScoreGroup.setScoreGroupMeta(scoreGroupMetaStr);
        }
        deptScoreGroup.setModifiedBy(accountId);
        deptScoreGroup.setModifiedAt(nowUtc);
        deptScoreGroup = deptScoreGroupRepo.save(deptScoreGroup);
        return deptScoreGroup.getId();
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
    private final List<ScoreGroupMetaPB> allScoreGroupMeta;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final ScorePB scorePb;
    private final ScoreConfig scoreConfig;
    private final ScoreCalculator scoreCalculator;
    private final UserService userService;
    private final PatientService patientService;
    private final MonitoringRecordUtils monitoringRecordUtils;
    private final PatientNursingReportUtils pnrUtils;
    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final PatientScoreRepository patientScoreRepo;
    private final MonitoringParamRepository monitoringParamRepo;
    private final AccountRepository accountRepo;
}