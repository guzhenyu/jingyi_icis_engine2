package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class DoctorScoreService {
    public DoctorScoreService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired ApacheIIScorer apacheIIScorer,
        @Autowired SofaScorer sofaScorer,
        @Autowired DeptDoctorScoreTypeRepository deptDoctorScoreTypeRepo,
        @Autowired ApacheIIScoreRepository apacheRepo,
        @Autowired SofaScoreRepository sofaRepo,
        @Autowired VTECapriniScoreRepository vteCapriniRepo,
        @Autowired VTEPaduaScoreRepository vtePaduaRepo
    ) {
        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;
        this.apacheIIScorer = apacheIIScorer;
        this.sofaScorer = sofaScorer;

        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.scoreTypeMap = protoService.getConfig().getDoctorScore().getDoctorScoreTypeList()
            .stream().collect(Collectors.toMap(StrKeyValPB::getKey, StrKeyValPB::getVal));
        this.apacheScoreMeta = protoService.getConfig().getDoctorScore().getApacheIiMeta();
        this.apacheScoreRules = protoService.getConfig().getDoctorScore().getApacheIiRules();
        this.sofaScoreMeta = protoService.getConfig().getDoctorScore().getSofaMeta();
        this.sofaScoreRules = protoService.getConfig().getDoctorScore().getSofaRules();
        this.vteCapriniScoreMeta = protoService.getConfig().getDoctorScore().getVteCapriniMeta();
        this.vtePaduaScoreMeta = protoService.getConfig().getDoctorScore().getVtePaduaMeta();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.deptDoctorScoreTypeRepo = deptDoctorScoreTypeRepo;
        this.apacheRepo = apacheRepo;
        this.sofaRepo = sofaRepo;
        this.vteCapriniRepo = vteCapriniRepo;
        this.vtePaduaRepo = vtePaduaRepo;
    }

    @Transactional
    public GetDeptDoctorScoreTypesResp getDeptDoctorScoreTypes(String getDeptDoctorScoreTypesReqJson) {
        final GetDeptDoctorScoreTypesReq req;
        try {
            GetDeptDoctorScoreTypesReq.Builder builder = GetDeptDoctorScoreTypesReq.newBuilder();
            JsonFormat.parser().merge(getDeptDoctorScoreTypesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDeptDoctorScoreTypesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取关键参数
        String deptId = req.getDeptId();

        // 提取数据
        List<DoctorScoreTypePB> deptDoctorScoreTypes = deptDoctorScoreTypeRepo
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId)
            .stream()
            .sorted(Comparator.comparing(DeptDoctorScoreType::getDisplayOrder))
            .map(entity -> DoctorScoreTypePB.newBuilder()
                .setId(entity.getId())
                .setDeptId(entity.getDeptId())
                .setCode(entity.getCode())
                .setName(entity.getName())
                .setDisplayOrder(entity.getDisplayOrder())
                .build()
            ).toList();

        return GetDeptDoctorScoreTypesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllScoreType(deptDoctorScoreTypes)
            .build();
    }

    @Transactional
    public GenericResp addDeptDoctorScoreTypes(String addDeptDoctorScoreTypesReqJson) {
        final AddDeptDoctorScoreTypesReq req;
        try {
            AddDeptDoctorScoreTypesReq.Builder builder = AddDeptDoctorScoreTypesReq.newBuilder();
            JsonFormat.parser().merge(addDeptDoctorScoreTypesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        String deptId = req.getDeptId();
        List<String> scoreTypeCodes = req.getScoreTypeCodeList();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取数据
        Integer nextDisplayOrder = 1;
        Set<String> existingCodes = new HashSet<>();
        for (DeptDoctorScoreType type : deptDoctorScoreTypeRepo
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId)
        ) {
            existingCodes.add(type.getCode());
            nextDisplayOrder = Math.max(nextDisplayOrder, type.getDisplayOrder() + 1);
        }

        // 新增参数
        List<DeptDoctorScoreType> newTypes = new ArrayList<>();
        for (String scoreTypeCode : scoreTypeCodes) {
            if (existingCodes.contains(scoreTypeCode)) {
                log.error("Score type code already exists: {}", scoreTypeCode);
                continue;
            }
            String scoreTypeName = scoreTypeMap.get(scoreTypeCode);
            if (scoreTypeName == null) {
                log.error("Score type code not found in config: {}", scoreTypeCode);
                continue;
            }
            DeptDoctorScoreType newType = new DeptDoctorScoreType();
            newType.setDeptId(deptId);
            newType.setCode(scoreTypeCode);
            newType.setName(scoreTypeName);
            newType.setDisplayOrder(nextDisplayOrder++);
            newType.setIsDeleted(false);
            newType.setModifiedBy(accountId);
            newType.setModifiedAt(nowUtc);

            newTypes.add(newType);
        }
        deptDoctorScoreTypeRepo.saveAll(newTypes);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteDeptDoctorScoreType(String deleteDeptDoctorScoreTypeReqJson) {
        final DeleteDeptDoctorScoreTypeReq req;
        try {
            DeleteDeptDoctorScoreTypeReq.Builder builder = DeleteDeptDoctorScoreTypeReq.newBuilder();
            JsonFormat.parser().merge(deleteDeptDoctorScoreTypeReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        String deptId = req.getDeptId();
        String codeToDel = req.getScoreTypeCode();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取数据
        DeptDoctorScoreType scoreTypeToDel = deptDoctorScoreTypeRepo
            .findByDeptIdAndCodeAndIsDeletedFalse(deptId, codeToDel).orElse(null);
        if (scoreTypeToDel == null) {
            log.error("Score type not found: deptId={}, code={}", deptId, codeToDel);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 逻辑删除
        scoreTypeToDel.setIsDeleted(true);
        scoreTypeToDel.setDeletedBy(accountId);
        scoreTypeToDel.setDeletedAt(nowUtc);
        deptDoctorScoreTypeRepo.save(scoreTypeToDel);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderDeptDoctorScoreTypes(String reorderDeptDoctorScoreTypesReqJson) {
        final ReorderDeptDoctorScoreTypesReq req;
        try {
            ReorderDeptDoctorScoreTypesReq.Builder builder = ReorderDeptDoctorScoreTypesReq.newBuilder();
            JsonFormat.parser().merge(reorderDeptDoctorScoreTypesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        String deptId = req.getDeptId();
        List<String> codesInOrder = req.getScoreTypeCodeList();
        Set<String> newCodes = new HashSet<>(codesInOrder);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        List<DeptDoctorScoreType> existingTypes = deptDoctorScoreTypeRepo
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
        Map<String, DeptDoctorScoreType> existingMap = existingTypes.stream()
            .collect(Collectors.toMap(DeptDoctorScoreType::getCode, Function.identity()));

        // 更新显示顺序
        List<DeptDoctorScoreType> toUpdate = new ArrayList<>();
        Integer displayOrder = 1;
        for (String code : codesInOrder) {
            DeptDoctorScoreType type = existingMap.get(code);
            if (type == null) {
                log.error("Score type not found in existing types: {}", code);
                continue;
            }
            if (type.getDisplayOrder() == displayOrder) {
                // 已经是正确顺序
                displayOrder++;
                continue;
            }
            type.setDisplayOrder(displayOrder++);
            type.setModifiedBy(accountId);
            type.setModifiedAt(nowUtc);
            toUpdate.add(type);
        }

        for (DeptDoctorScoreType type : existingTypes) {
            if (!newCodes.contains(type.getCode())) {
                type.setDisplayOrder(displayOrder++);
                type.setModifiedBy(accountId);
                type.setModifiedAt(nowUtc);
                toUpdate.add(type);
            }
        }
        if (!toUpdate.isEmpty()) deptDoctorScoreTypeRepo.saveAll(toUpdate);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetApacheScoresResp getApacheScores(String getApacheScoresReqJson) {
        final GetApacheScoresReq req;
        try {
            GetApacheScoresReq.Builder builder = GetApacheScoresReq.newBuilder();
            JsonFormat.parser().merge(getApacheScoresReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetApacheScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 验证患者ID
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetApacheScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 获取所有apache评分
        LocalDateTime startScoreTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startScoreTime == null) startScoreTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endScoreTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endScoreTime == null) endScoreTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<ApacheIIScore> apacheScoreList = apacheRepo.findByPidAndScoreTimeBetweenAndIsDeletedFalse(
            pid, startScoreTime, endScoreTime
        ).stream().sorted(Comparator.comparing(ApacheIIScore::getScoreTime)).toList();

        if (req.getLimit() > 0) {
            apacheScoreList = apacheScoreList.stream()
                .skip(Math.max(0, apacheScoreList.size() - req.getLimit()))
                .toList();
        }

        List<ApacheScoreRecordPB> apacheScoreRecordList = new ArrayList<>();
        for (ApacheIIScore apacheScore : apacheScoreList) {
            String scoredAtIso8601 = TimeUtils.toIso8601String(apacheScore.getScoreTime(), ZONE_ID);
            String scoredByAccountName = StrUtils.getStringOrDefault(apacheScore.getScoredByAccountName(), "");
            String evalStartAtIso8601 = TimeUtils.toIso8601String(apacheScore.getEvalStartAt(), ZONE_ID);
            String evalEndAtIso8601 = TimeUtils.toIso8601String(apacheScore.getEvalEndAt(), ZONE_ID);
            String createdAtIso8601 = TimeUtils.toIso8601String(apacheScore.getCreatedAt(), ZONE_ID);
            String modifiedAtIso8601 = TimeUtils.toIso8601String(apacheScore.getModifiedAt(), ZONE_ID);

            Pair<ReturnCode, ApacheIIScorePB> apacheIIScorePBPair = ApacheIIScorer.toProto(
                apacheScoreMeta, apacheScoreRules, apacheScore, statusCodeMsgList
            );
            if (apacheIIScorePBPair.getFirst().getCode() != 0) {
                log.error("Failed to convert Apache score to proto: entity {}, error: {}",
                    apacheScore, apacheIIScorePBPair.getFirst().getMsg()
                );
                return GetApacheScoresResp.newBuilder()
                    .setRt(apacheIIScorePBPair.getFirst())
                    .build();
            }
            ApacheIIScorePB apacheIIScorePB = apacheIIScorePBPair.getSecond();

            ApacheScoreRecordPB apacheScoreRecord = ApacheScoreRecordPB.newBuilder()
                .setId(apacheScore.getId())
                .setPid(apacheScore.getPid())
                .setScoredAtIso8601(scoredAtIso8601)
                .setScoredBy(apacheScore.getScoredBy())
                .setScoredByAccountName(scoredByAccountName)
                .setEvalStartAtIso8601(evalStartAtIso8601)
                .setEvalEndAtIso8601(evalEndAtIso8601)
                .setCreatedAtIso8601(createdAtIso8601)
                .setModifiedAtIso8601(modifiedAtIso8601)
                .setScore(apacheIIScorePB)
                .build();
            apacheScoreRecordList.add(apacheScoreRecord);
        }

        return GetApacheScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(apacheScoreRecordList)
            .setScoreMeta(apacheScoreMeta)
            .build();
    }

    @Transactional
    public GetApacheScoreFactorsResp getApacheScoreFactors(String getApacheScoreFactorsReqJson) {
        final GetApacheScoreFactorsReq req;
        try {
            GetApacheScoreFactorsReq.Builder builder = GetApacheScoreFactorsReq.newBuilder();
            JsonFormat.parser().merge(getApacheScoreFactorsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetApacheScoreFactorsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取患者信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetApacheScoreFactorsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        final Integer age = patientRecord.getIcuDateOfBirth() == null ?
            -1 : TimeUtils.getAge(patientRecord.getIcuDateOfBirth());

        // 校验参数
        LocalDateTime evalStartUtc = TimeUtils.fromIso8601String(req.getEvalStartIso8601(), "UTC");
        LocalDateTime evalEndUtc = TimeUtils.fromIso8601String(req.getEvalEndIso8601(), "UTC");
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("Failed to parse eval time: {}, {}", req.getEvalStartIso8601(), req.getEvalEndIso8601());
            return GetApacheScoreFactorsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_RANGE,
                    req.getEvalStartIso8601() + "-" + req.getEvalEndIso8601()
                ))
                .build();
        }

        ApacheIIScorePB apacheIIScorePb = null;
        if (req.getScore().getApsParamCount() > 0 ||
            req.getScore().getAge() > 0 ||
            req.getScore().getChcParamCount() > 0
        ) {
            // 评分参数不为空，直接使用
            apacheIIScorePb = req.getScore();
        } else {
            // 自动获取apache评分要素
            Pair<ReturnCode, ApacheIIScorePB> scorePair = apacheIIScorer.getApacheIIScoreFactors(
                pid, deptId, evalStartUtc, evalEndUtc
            );
            if (scorePair.getFirst().getCode() != 0) {
                log.error("Failed to get apache score factors: {}, error: {}",
                    scorePair.getSecond(), scorePair.getFirst().getMsg()
                );
                return GetApacheScoreFactorsResp.newBuilder()
                    .setRt(scorePair.getFirst())
                    .build();
            }
            ApacheIIScorePB autoFetchedApachePb = scorePair.getSecond();
            autoFetchedApachePb = autoFetchedApachePb.toBuilder().setAge(age).build();

            // 合并，重新打分
            apacheIIScorePb = ApacheIIScorer.mergeProto(req.getScore(), autoFetchedApachePb);
        }

        Pair<ReturnCode, ApacheIIScorePB> apacheIIScorePBPair = ApacheIIScorer.score(
            apacheScoreMeta, apacheScoreRules, apacheIIScorePb, statusCodeMsgList
        );
        if (apacheIIScorePBPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Apache score to proto: entity {}, error: {}",
                apacheIIScorePb, apacheIIScorePBPair.getFirst().getMsg()
            );
            return GetApacheScoreFactorsResp.newBuilder()
                .setRt(apacheIIScorePBPair.getFirst())
                .build();
        }

        return GetApacheScoreFactorsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setScore(apacheIIScorePBPair.getSecond())
            .build();
    }

    @Transactional
    public AddApacheScoreResp addApacheScore(String addApacheScoreReqJson) {
        final AddApacheScoreReq req;
        try {
            AddApacheScoreReq.Builder builder = AddApacheScoreReq.newBuilder();
            JsonFormat.parser().merge(addApacheScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddApacheScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 根据患者id和时间检查是否存在对应的记录
        LocalDateTime scoreTimeUtc = TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC");
        if (scoreTimeUtc == null) {
            log.error("Failed to parse score time: {}", req.getScoredAtIso8601());
            return AddApacheScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, req.getScoredAtIso8601()
                ))
                .build();
        }
        ApacheIIScore apacheScore = apacheRepo.findByPidAndScoreTimeAndIsDeletedFalse(
            req.getPid(), TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC")
        ).orElse(null);
        if (apacheScore != null) {
            log.error("Apache score already exists.");
            return AddApacheScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.APACHE_SCORE_ALREADY_EXISTS,
                    req.getScoredAtIso8601()
                ))
                .build();
        }

        // 生成评分信息
        Pair<ReturnCode, ApacheIIScore> apacheIIScoreEntityPair = processApacheScore(
            req.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (apacheIIScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Apache score to entity: proto {}, error: {}",
                req.getScore(), apacheIIScoreEntityPair.getFirst().getMsg()
            );
            return AddApacheScoreResp.newBuilder()
                .setRt(apacheIIScoreEntityPair.getFirst())
                .build();
        }

        // 保存评分信息
        ApacheIIScore apacheIIScoreEntity = apacheIIScoreEntityPair.getSecond();
        apacheIIScoreEntity.setCreatedAt(apacheIIScoreEntity.getModifiedAt());
        apacheIIScoreEntity.setCreatedBy(apacheIIScoreEntity.getModifiedBy());
        apacheIIScoreEntity = apacheRepo.save(apacheIIScoreEntity);

        return AddApacheScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(apacheIIScoreEntity.getId())
            .build();
    }

    @Transactional
    public GenericResp updateApacheScore(String updateApacheScoreReqJson) {
        final UpdateApacheScoreReq req;
        try {
            UpdateApacheScoreReq.Builder builder = UpdateApacheScoreReq.newBuilder();
            JsonFormat.parser().merge(updateApacheScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        
        // 查找评分信息
        ApacheIIScore apacheIIScore = apacheRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (apacheIIScore == null) {
            log.error("Apache score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.APACHE_SCORE_NOT_FOUND,
                    String.valueOf(req.getId())
                ))
                .build();
        }

        // 加工评分信息
        Pair<ReturnCode, ApacheIIScore> apacheIIScoreEntityPair = processApacheScore(
            apacheIIScore.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (apacheIIScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Apache score to entity: proto {}, error: {}",
                req.getScore(), apacheIIScoreEntityPair.getFirst().getMsg()
            );
            return GenericResp.newBuilder()
                .setRt(apacheIIScoreEntityPair.getFirst())
                .build();
        }

        // 保存评分信息
        ApacheIIScore apacheIIScoreEntity = apacheIIScoreEntityPair.getSecond();
        apacheIIScoreEntity.setId(apacheIIScore.getId());
        apacheIIScoreEntity.setCreatedAt(apacheIIScore.getCreatedAt());
        apacheIIScoreEntity.setCreatedBy(apacheIIScore.getCreatedBy());
        apacheIIScoreEntity = apacheRepo.save(apacheIIScoreEntity);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteApacheScore(String deleteApacheScoreReqJson) {
        final DeleteApacheScoreReq req;
        try {
            DeleteApacheScoreReq.Builder builder = DeleteApacheScoreReq.newBuilder();
            JsonFormat.parser().merge(deleteApacheScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找评分信息
        ApacheIIScore apacheIIScore = apacheRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (apacheIIScore == null) {
            log.error("Apache score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 删除评分信息
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        apacheIIScore.setIsDeleted(true);
        apacheIIScore.setDeletedAt(nowUtc);
        apacheIIScore.setDeletedBy(accountId);
        apacheIIScore.setDeletedByAccountName(accountName);
        apacheIIScore = apacheRepo.save(apacheIIScore);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetApacheFactorDetailsResp getApacheFactorDetails(String getApacheFactorDetailsReqJson) {
        final GetApacheFactorDetailsReq req;
        try {
            GetApacheFactorDetailsReq.Builder builder = GetApacheFactorDetailsReq.newBuilder();
            JsonFormat.parser().merge(getApacheFactorDetailsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetApacheFactorDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime evalStartUtc = TimeUtils.fromIso8601String(req.getEvalStartIso8601(), "UTC");
        LocalDateTime evalEndUtc = TimeUtils.fromIso8601String(req.getEvalEndIso8601(), "UTC");
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("Failed to parse eval time: {}, {}", req.getEvalStartIso8601(), req.getEvalEndIso8601());
            return GetApacheFactorDetailsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_RANGE,
                    req.getEvalStartIso8601() + "-" + req.getEvalEndIso8601()
                ))
                .build();
        }

        GetApacheFactorDetailsResp.Builder respBuilder = GetApacheFactorDetailsResp.newBuilder();
        Pair<ReturnCode, DiseaseItemDetailsPB> detailsPair = apacheIIScorer.getApacheFactorDetails(
            req.getPid(), req.getDeptId(), req.getFactorCode(), evalStartUtc, evalEndUtc
        );
        if (detailsPair != null && detailsPair.getFirst().getCode() != 0) {
            log.error("Failed to get apache factor details: {}, error: {}",
                detailsPair.getSecond(), detailsPair.getFirst().getMsg()
            );
            return respBuilder.setRt(detailsPair.getFirst()).build();
        }

        respBuilder.setRt(protoService.getReturnCode(StatusCode.OK));
        if (detailsPair != null) {
            respBuilder.setFactorDetails(detailsPair.getSecond());
        }

        return respBuilder.build();
    }

    @Transactional
    public GetSofaScoresResp getSofaScores(String getSofaScoresReqJson) {
        final GetSofaScoresReq req;
        try {
            GetSofaScoresReq.Builder builder = GetSofaScoresReq.newBuilder();
            JsonFormat.parser().merge(getSofaScoresReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetSofaScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 验证患者ID
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetSofaScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 获取所有sofa评分
        LocalDateTime startScoreTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startScoreTime == null) startScoreTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endScoreTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endScoreTime == null) endScoreTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<SofaScore> sofaScoreList = sofaRepo.findByPidAndScoreTimeBetweenAndIsDeletedFalse(
            pid, startScoreTime, endScoreTime
        ).stream().sorted(Comparator.comparing(SofaScore::getScoreTime)).toList();

        if (req.getLimit() > 0) {
            sofaScoreList = sofaScoreList.stream()
                .skip(Math.max(0, sofaScoreList.size() - req.getLimit()))
                .toList();
        }

        List<SofaScoreRecordPB> sofaScoreRecordList = new ArrayList<>();
        for (SofaScore sofaScore : sofaScoreList) {
            String scoredAtIso8601 = TimeUtils.toIso8601String(sofaScore.getScoreTime(), ZONE_ID);
            String scoredByAccountName = StrUtils.getStringOrDefault(sofaScore.getScoredByAccountName(), "");
            String evalStartAtIso8601 = TimeUtils.toIso8601String(sofaScore.getEvalStartAt(), ZONE_ID);
            String evalEndAtIso8601 = TimeUtils.toIso8601String(sofaScore.getEvalEndAt(), ZONE_ID);
            String createdAtIso8601 = TimeUtils.toIso8601String(sofaScore.getCreatedAt(), ZONE_ID);
            String modifiedAtIso8601 = TimeUtils.toIso8601String(sofaScore.getModifiedAt(), ZONE_ID);

            Pair<ReturnCode, SOFAScorePB> sofaScorePBPair = SofaScorer.toProto(
                sofaScoreMeta, sofaScoreRules, sofaScore, statusCodeMsgList
            );
            if (sofaScorePBPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to proto: entity {}, error: {}",
                sofaScore, sofaScorePBPair.getFirst().getMsg()
            );
            return GetSofaScoresResp.newBuilder()
                .setRt(sofaScorePBPair.getFirst())
                .build();
            }
            SOFAScorePB sofaScorePB = sofaScorePBPair.getSecond();

            SofaScoreRecordPB sofaScoreRecord = SofaScoreRecordPB.newBuilder()
                .setId(sofaScore.getId())
                .setPid(sofaScore.getPid())
                .setScoredAtIso8601(scoredAtIso8601)
                .setScoredBy(sofaScore.getScoredBy())
                .setScoredByAccountName(scoredByAccountName)
                .setEvalStartAtIso8601(evalStartAtIso8601)
                .setEvalEndAtIso8601(evalEndAtIso8601)
                .setCreatedAtIso8601(createdAtIso8601)
                .setModifiedAtIso8601(modifiedAtIso8601)
                .setScore(sofaScorePB)
                .build();
            sofaScoreRecordList.add(sofaScoreRecord);
        }

        return GetSofaScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(sofaScoreRecordList)
            .setScoreMeta(sofaScoreMeta)
            .build();
    }

    @Transactional
    public GetSofaScoreFactorsResp getSofaScoreFactors(String getSofaScoreFactorsReqJson) {
        final GetSofaScoreFactorsReq req;
        try {
            GetSofaScoreFactorsReq.Builder builder = GetSofaScoreFactorsReq.newBuilder();
            JsonFormat.parser().merge(getSofaScoreFactorsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetSofaScoreFactorsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取患者信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetSofaScoreFactorsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
            .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 校验参数
        LocalDateTime evalStartUtc = TimeUtils.fromIso8601String(req.getEvalStartIso8601(), "UTC");
        LocalDateTime evalEndUtc = TimeUtils.fromIso8601String(req.getEvalEndIso8601(), "UTC");
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("Failed to parse eval time: {}, {}", req.getEvalStartIso8601(), req.getEvalEndIso8601());
            return GetSofaScoreFactorsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_RANGE,
                req.getEvalStartIso8601() + "-" + req.getEvalEndIso8601()
            ))
            .build();
        }

        // 自动获取sofa评分要素
        Pair<ReturnCode, SOFAScorePB> scorePair = sofaScorer.getSofaScoreFactors(
            pid, deptId, evalStartUtc, evalEndUtc
        );
        if (scorePair.getFirst().getCode() != 0) {
            log.error("Failed to get sofa score factors: {}, error: {}",
            scorePair.getSecond(), scorePair.getFirst().getMsg()
            );
            return GetSofaScoreFactorsResp.newBuilder()
                .setRt(scorePair.getFirst()).build();
        }
        SOFAScorePB autoFetchedSofaPb = scorePair.getSecond();

        // 合并，重新打分
        SOFAScorePB sofaScorePb = SofaScorer.mergeProto(sofaScoreMeta, req.getScore(), autoFetchedSofaPb);
        Pair<ReturnCode, SOFAScorePB> sofaScorePBPair = SofaScorer.score(
            sofaScoreMeta, sofaScoreRules, sofaScorePb, statusCodeMsgList
        );
        if (sofaScorePBPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to proto: entity {}, error: {}",
            sofaScorePb, sofaScorePBPair.getFirst().getMsg()
            );
            return GetSofaScoreFactorsResp.newBuilder()
            .setRt(sofaScorePBPair.getFirst())
            .build();
        }

        return GetSofaScoreFactorsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setScore(sofaScorePBPair.getSecond())
            .build();
    }

    @Transactional
    public AddSofaScoreResp addSofaScore(String addSofaScoreReqJson) {
        final AddSofaScoreReq req;
        try {
            AddSofaScoreReq.Builder builder = AddSofaScoreReq.newBuilder();
            JsonFormat.parser().merge(addSofaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddSofaScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 根据患者id和时间检查是否存在对应的记录
        LocalDateTime scoreTimeUtc = TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC");
        if (scoreTimeUtc == null) {
            log.error("Failed to parse score time: {}", req.getScoredAtIso8601());
            return AddSofaScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, req.getScoredAtIso8601()
                ))
                .build();
        }
        SofaScore sofaScore = sofaRepo.findByPidAndScoreTimeAndIsDeletedFalse(
            req.getPid(), TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC")
        ).orElse(null);
        if (sofaScore != null) {
            log.error("Sofa score already exists.");
            return AddSofaScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.SOFA_SCORE_ALREADY_EXISTS,
                    req.getScoredAtIso8601()
                ))
                .build();
        }

        Pair<ReturnCode, SofaScore> sofaScoreEntityPair = processSofaScore(
            req.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (sofaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to entity: proto {}, error: {}",
                req.getScore(), sofaScoreEntityPair.getFirst().getMsg()
            );
            return AddSofaScoreResp.newBuilder()
                .setRt(sofaScoreEntityPair.getFirst())
                .build();
        }

        // 保存评分信息
        SofaScore sofaScoreEntity = sofaScoreEntityPair.getSecond();
        sofaScoreEntity.setCreatedAt(sofaScoreEntity.getModifiedAt());
        sofaScoreEntity.setCreatedBy(sofaScoreEntity.getModifiedBy());
        sofaScoreEntity = sofaRepo.save(sofaScoreEntity);

        return AddSofaScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(sofaScoreEntity.getId())
            .build();
    }

    @Transactional
    public GenericResp updateSofaScore(String updateSofaScoreReqJson) {
        final UpdateSofaScoreReq req;
        try {
            UpdateSofaScoreReq.Builder builder = UpdateSofaScoreReq.newBuilder();
            JsonFormat.parser().merge(updateSofaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找评分信息
        SofaScore sofaScore = sofaRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (sofaScore == null) {
            log.error("Sofa score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.SOFA_SCORE_NOT_FOUND,
                    String.valueOf(req.getId())
                ))
                .build();
        }

        // 加工评分信息
        Pair<ReturnCode, SofaScore> sofaScoreEntityPair = processSofaScore(
            sofaScore.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (sofaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to entity: proto {}, error: {}",
                req.getScore(), sofaScoreEntityPair.getFirst().getMsg()
            );
            return GenericResp.newBuilder()
                .setRt(sofaScoreEntityPair.getFirst())
                .build();
        }

        // 保存评分信息
        SofaScore sofaScoreEntity = sofaScoreEntityPair.getSecond();
        sofaScoreEntity.setId(sofaScore.getId());
        sofaScoreEntity.setCreatedAt(sofaScore.getCreatedAt());
        sofaScoreEntity.setCreatedBy(sofaScore.getCreatedBy());
        sofaScoreEntity = sofaRepo.save(sofaScoreEntity);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteSofaScore(String deleteSofaScoreReqJson) {
        final DeleteSofaScoreReq req;
        try {
            DeleteSofaScoreReq.Builder builder = DeleteSofaScoreReq.newBuilder();
            JsonFormat.parser().merge(deleteSofaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找评分信息
        SofaScore sofaScore = sofaRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (sofaScore == null) {
            log.error("Sofa score not found.");
            return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
            .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 删除评分信息
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        sofaScore.setIsDeleted(true);
        sofaScore.setDeletedAt(nowUtc);
        sofaScore.setDeletedBy(accountId);
        sofaScore.setDeletedByAccountName(accountName);
        sofaScore = sofaRepo.save(sofaScore);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetSofaFactorDetailsResp getSofaFactorDetails(String getSofaFactorDetailsReqJson) {
        final GetSofaFactorDetailsReq req;
        try {
            GetSofaFactorDetailsReq.Builder builder = GetSofaFactorDetailsReq.newBuilder();
            JsonFormat.parser().merge(getSofaFactorDetailsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetSofaFactorDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime evalStartUtc = TimeUtils.fromIso8601String(req.getEvalStartIso8601(), "UTC");
        LocalDateTime evalEndUtc = TimeUtils.fromIso8601String(req.getEvalEndIso8601(), "UTC");
        if (evalStartUtc == null || evalEndUtc == null) {
            log.error("Failed to parse eval time: {}, {}", req.getEvalStartIso8601(), req.getEvalEndIso8601());
            return GetSofaFactorDetailsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_RANGE,
                req.getEvalStartIso8601() + "-" + req.getEvalEndIso8601()
            ))
            .build();
        }

        GetSofaFactorDetailsResp.Builder respBuilder = GetSofaFactorDetailsResp.newBuilder();
        Pair<ReturnCode, DiseaseItemDetailsPB> detailsPair = sofaScorer.getSofaFactorDetails(
            req.getPid(), req.getDeptId(), req.getFactorCode(), evalStartUtc, evalEndUtc
        );
        if (detailsPair != null && detailsPair.getFirst().getCode() != 0) {
            log.error("Failed to get sofa factor details: {}, error: {}",
            detailsPair.getSecond(), detailsPair.getFirst().getMsg()
            );
            return respBuilder.setRt(detailsPair.getFirst()).build();
        }

        respBuilder.setRt(protoService.getReturnCode(StatusCode.OK));
        if (detailsPair != null) {
            respBuilder.setFactorDetails(detailsPair.getSecond());
        }

        return respBuilder.build();
    }

    @Transactional
    public GetVteCapriniScoresResp getVteCapriniScores(String getVteCapriniScoresReqJson) {
        final GetVteCapriniScoresReq req;
        try {
            GetVteCapriniScoresReq.Builder builder = GetVteCapriniScoresReq.newBuilder();
            JsonFormat.parser().merge(getVteCapriniScoresReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetVteCapriniScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetVteCapriniScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        LocalDateTime startScoreTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startScoreTime == null) startScoreTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endScoreTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endScoreTime == null) endScoreTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<VTECapriniScore> vteCapriniScoreList = vteCapriniRepo.findByPidAndScoreTimeBetweenAndIsDeletedFalse(
            pid, startScoreTime, endScoreTime
        ).stream().sorted(Comparator.comparing(VTECapriniScore::getScoreTime)).toList();

        if (req.getLimit() > 0) {
            vteCapriniScoreList = vteCapriniScoreList.stream()
                .skip(Math.max(0, vteCapriniScoreList.size() - req.getLimit()))
                .toList();
        }

        List<VteCapriniScoreRecordPB> vteCapriniScoreRecordList = new ArrayList<>();
        for (VTECapriniScore vteCapriniScore : vteCapriniScoreList) {
            String scoredAtIso8601 = TimeUtils.toIso8601String(vteCapriniScore.getScoreTime(), ZONE_ID);
            String scoredByAccountName = StrUtils.getStringOrDefault(vteCapriniScore.getScoredByAccountName(), "");
            String evalStartAtIso8601 = TimeUtils.toIso8601String(vteCapriniScore.getEvalStartAt(), ZONE_ID);
            String evalEndAtIso8601 = TimeUtils.toIso8601String(vteCapriniScore.getEvalEndAt(), ZONE_ID);

            Pair<ReturnCode, VTECapriniScorePB> vteCapriniScorePBPair = VTECapriniScorer.toProto(
                vteCapriniScoreMeta, vteCapriniScore, statusCodeMsgList
            );
            if (vteCapriniScorePBPair.getFirst().getCode() != 0) {
                log.error("Failed to convert VTE Caprini score to proto: entity {}, error: {}",
                    vteCapriniScore, vteCapriniScorePBPair.getFirst().getMsg()
                );
                return GetVteCapriniScoresResp.newBuilder()
                    .setRt(vteCapriniScorePBPair.getFirst())
                    .build();
            }
            VTECapriniScorePB vteCapriniScorePB = vteCapriniScorePBPair.getSecond();

            VteCapriniScoreRecordPB vteCapriniScoreRecord = VteCapriniScoreRecordPB.newBuilder()
                .setId(vteCapriniScore.getId())
                .setPid(vteCapriniScore.getPid())
                .setScoredAtIso8601(scoredAtIso8601)
                .setScoredBy(vteCapriniScore.getScoredBy())
                .setScoredByAccountName(scoredByAccountName)
                .setEvalStartAtIso8601(evalStartAtIso8601)
                .setEvalEndAtIso8601(evalEndAtIso8601)
                .setScore(vteCapriniScorePB)
                .build();
            vteCapriniScoreRecordList.add(vteCapriniScoreRecord);
        }

        return GetVteCapriniScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(vteCapriniScoreRecordList)
            .setScoreMeta(vteCapriniScoreMeta)
            .build();
    }

    @Transactional
    public AddVteCapriniScoreResp addVteCapriniScore(String addVteCapriniScoreReqJson) {
        final AddVteCapriniScoreReq req;
        try {
            AddVteCapriniScoreReq.Builder builder = AddVteCapriniScoreReq.newBuilder();
            JsonFormat.parser().merge(addVteCapriniScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddVteCapriniScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime scoreTimeUtc = TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC");
        if (scoreTimeUtc == null) {
            log.error("Failed to parse score time: {}", req.getScoredAtIso8601());
            return AddVteCapriniScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, req.getScoredAtIso8601()
                ))
                .build();
        }
        VTECapriniScore vteCapriniScore = vteCapriniRepo.findByPidAndScoreTimeAndIsDeletedFalse(
            req.getPid(), scoreTimeUtc
        ).orElse(null);
        if (vteCapriniScore != null) {
            log.error("VTE Caprini score already exists.");
            return AddVteCapriniScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.VTE_CAPRINI_SCORE_ALREADY_EXISTS,
                    req.getScoredAtIso8601()
                ))
                .build();
        }

        Pair<ReturnCode, VTECapriniScore> vteCapriniScoreEntityPair = processVteCapriniScore(
            req.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (vteCapriniScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Caprini score to entity: proto {}, error: {}",
                req.getScore(), vteCapriniScoreEntityPair.getFirst().getMsg()
            );
            return AddVteCapriniScoreResp.newBuilder()
                .setRt(vteCapriniScoreEntityPair.getFirst())
                .build();
        }

        VTECapriniScore vteCapriniScoreEntity = vteCapriniScoreEntityPair.getSecond();
        vteCapriniScoreEntity = vteCapriniRepo.save(vteCapriniScoreEntity);

        return AddVteCapriniScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(vteCapriniScoreEntity.getId())
            .build();
    }

    @Transactional
    public GenericResp updateVteCapriniScore(String updateVteCapriniScoreReqJson) {
        final UpdateVteCapriniScoreReq req;
        try {
            UpdateVteCapriniScoreReq.Builder builder = UpdateVteCapriniScoreReq.newBuilder();
            JsonFormat.parser().merge(updateVteCapriniScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        VTECapriniScore vteCapriniScore = vteCapriniRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (vteCapriniScore == null) {
            log.error("VTE Caprini score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.VTE_CAPRINI_SCORE_NOT_FOUND,
                    String.valueOf(req.getId())
                ))
                .build();
        }

        Pair<ReturnCode, VTECapriniScore> vteCapriniScoreEntityPair = processVteCapriniScore(
            vteCapriniScore.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (vteCapriniScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Caprini score to entity: proto {}, error: {}",
                req.getScore(), vteCapriniScoreEntityPair.getFirst().getMsg()
            );
            return GenericResp.newBuilder()
                .setRt(vteCapriniScoreEntityPair.getFirst())
                .build();
        }

        VTECapriniScore vteCapriniScoreEntity = vteCapriniScoreEntityPair.getSecond();
        vteCapriniScoreEntity.setId(vteCapriniScore.getId());
        vteCapriniScoreEntity = vteCapriniRepo.save(vteCapriniScoreEntity);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteVteCapriniScore(String deleteVteCapriniScoreReqJson) {
        final DeleteVteCapriniScoreReq req;
        try {
            DeleteVteCapriniScoreReq.Builder builder = DeleteVteCapriniScoreReq.newBuilder();
            JsonFormat.parser().merge(deleteVteCapriniScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        VTECapriniScore vteCapriniScore = vteCapriniRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (vteCapriniScore == null) {
            log.error("VTE Caprini score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        vteCapriniScore.setIsDeleted(true);
        vteCapriniScore.setDeletedAt(nowUtc);
        vteCapriniScore.setDeletedBy(accountId);
        vteCapriniScore.setDeletedByAccountName(accountName);
        vteCapriniScore = vteCapriniRepo.save(vteCapriniScore);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetVtePaduaScoresResp getVtePaduaScores(String getVtePaduaScoresReqJson) {
        final GetVtePaduaScoresReq req;
        try {
            GetVtePaduaScoresReq.Builder builder = GetVtePaduaScoresReq.newBuilder();
            JsonFormat.parser().merge(getVtePaduaScoresReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetVtePaduaScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetVtePaduaScoresResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        LocalDateTime startScoreTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startScoreTime == null) startScoreTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endScoreTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endScoreTime == null) endScoreTime = TimeUtils.getLocalTime(9900, 1, 1);

        List<VTEPaduaScore> vtePaduaScoreList = vtePaduaRepo.findByPidAndScoreTimeBetweenAndIsDeletedFalse(
            pid, startScoreTime, endScoreTime
        ).stream().sorted(Comparator.comparing(VTEPaduaScore::getScoreTime)).toList();

        if (req.getLimit() > 0) {
            vtePaduaScoreList = vtePaduaScoreList.stream()
                .skip(Math.max(0, vtePaduaScoreList.size() - req.getLimit()))
                .toList();
        }

        List<VtePaduaScoreRecordPB> vtePaduaScoreRecordList = new ArrayList<>();
        for (VTEPaduaScore vtePaduaScore : vtePaduaScoreList) {
            String scoredAtIso8601 = TimeUtils.toIso8601String(vtePaduaScore.getScoreTime(), ZONE_ID);
            String scoredByAccountName = StrUtils.getStringOrDefault(vtePaduaScore.getScoredByAccountName(), "");
            String evalStartAtIso8601 = TimeUtils.toIso8601String(vtePaduaScore.getEvalStartAt(), ZONE_ID);
            String evalEndAtIso8601 = TimeUtils.toIso8601String(vtePaduaScore.getEvalEndAt(), ZONE_ID);

            Pair<ReturnCode, VTEPaduaScorePB> vtePaduaScorePBPair = VTEPaduaScorer.toProto(
                vtePaduaScoreMeta, vtePaduaScore, statusCodeMsgList
            );
            if (vtePaduaScorePBPair.getFirst().getCode() != 0) {
                log.error("Failed to convert VTE Padua score to proto: entity {}, error: {}",
                    vtePaduaScore, vtePaduaScorePBPair.getFirst().getMsg()
                );
                return GetVtePaduaScoresResp.newBuilder()
                    .setRt(vtePaduaScorePBPair.getFirst())
                    .build();
            }
            VTEPaduaScorePB vtePaduaScorePB = vtePaduaScorePBPair.getSecond();

            VtePaduaScoreRecordPB vtePaduaScoreRecord = VtePaduaScoreRecordPB.newBuilder()
                .setId(vtePaduaScore.getId())
                .setPid(vtePaduaScore.getPid())
                .setScoredAtIso8601(scoredAtIso8601)
                .setScoredBy(vtePaduaScore.getScoredBy())
                .setScoredByAccountName(scoredByAccountName)
                .setEvalStartAtIso8601(evalStartAtIso8601)
                .setEvalEndAtIso8601(evalEndAtIso8601)
                .setScore(vtePaduaScorePB)
                .build();
            vtePaduaScoreRecordList.add(vtePaduaScoreRecord);
        }

        return GetVtePaduaScoresResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(vtePaduaScoreRecordList)
            .setScoreMeta(vtePaduaScoreMeta)
            .build();
    }

    @Transactional
    public AddVtePaduaScoreResp addVtePaduaScore(String addVtePaduaScoreReqJson) {
        final AddVtePaduaScoreReq req;
        try {
            AddVtePaduaScoreReq.Builder builder = AddVtePaduaScoreReq.newBuilder();
            JsonFormat.parser().merge(addVtePaduaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddVtePaduaScoreResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime scoreTimeUtc = TimeUtils.fromIso8601String(req.getScoredAtIso8601(), "UTC");
        if (scoreTimeUtc == null) {
            log.error("Failed to parse score time: {}", req.getScoredAtIso8601());
            return AddVtePaduaScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, req.getScoredAtIso8601()
                ))
                .build();
        }
        VTEPaduaScore vtePaduaScore = vtePaduaRepo.findByPidAndScoreTimeAndIsDeletedFalse(
            req.getPid(), scoreTimeUtc
        ).orElse(null);
        if (vtePaduaScore != null) {
            log.error("VTE Padua score already exists.");
            return AddVtePaduaScoreResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.VTE_PADUA_SCORE_ALREADY_EXISTS,
                    req.getScoredAtIso8601()
                ))
                .build();
        }

        Pair<ReturnCode, VTEPaduaScore> vtePaduaScoreEntityPair = processVtePaduaScore(
            req.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (vtePaduaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Padua score to entity: proto {}, error: {}",
                req.getScore(), vtePaduaScoreEntityPair.getFirst().getMsg()
            );
            return AddVtePaduaScoreResp.newBuilder()
                .setRt(vtePaduaScoreEntityPair.getFirst())
                .build();
        }

        VTEPaduaScore vtePaduaScoreEntity = vtePaduaScoreEntityPair.getSecond();
        vtePaduaScoreEntity = vtePaduaRepo.save(vtePaduaScoreEntity);

        return AddVtePaduaScoreResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(vtePaduaScoreEntity.getId())
            .build();
    }

    @Transactional
    public GenericResp updateVtePaduaScore(String updateVtePaduaScoreReqJson) {
        final UpdateVtePaduaScoreReq req;
        try {
            UpdateVtePaduaScoreReq.Builder builder = UpdateVtePaduaScoreReq.newBuilder();
            JsonFormat.parser().merge(updateVtePaduaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        VTEPaduaScore vtePaduaScore = vtePaduaRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (vtePaduaScore == null) {
            log.error("VTE Padua score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.VTE_PADUA_SCORE_NOT_FOUND,
                    String.valueOf(req.getId())
                ))
                .build();
        }

        Pair<ReturnCode, VTEPaduaScore> vtePaduaScoreEntityPair = processVtePaduaScore(
            vtePaduaScore.getPid(), req.getScoredAtIso8601(), req.getEvalStartAtIso8601(),
            req.getEvalEndAtIso8601(), req.getScore()
        );
        if (vtePaduaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Padua score to entity: proto {}, error: {}",
                req.getScore(), vtePaduaScoreEntityPair.getFirst().getMsg()
            );
            return GenericResp.newBuilder()
                .setRt(vtePaduaScoreEntityPair.getFirst())
                .build();
        }

        VTEPaduaScore vtePaduaScoreEntity = vtePaduaScoreEntityPair.getSecond();
        vtePaduaScoreEntity.setId(vtePaduaScore.getId());
        vtePaduaScoreEntity = vtePaduaRepo.save(vtePaduaScoreEntity);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteVtePaduaScore(String deleteVtePaduaScoreReqJson) {
        final DeleteVtePaduaScoreReq req;
        try {
            DeleteVtePaduaScoreReq.Builder builder = DeleteVtePaduaScoreReq.newBuilder();
            JsonFormat.parser().merge(deleteVtePaduaScoreReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        VTEPaduaScore vtePaduaScore = vtePaduaRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (vtePaduaScore == null) {
            log.error("VTE Padua score not found.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        vtePaduaScore.setIsDeleted(true);
        vtePaduaScore.setDeletedAt(nowUtc);
        vtePaduaScore.setDeletedBy(accountId);
        vtePaduaScore.setDeletedByAccountName(accountName);
        vtePaduaScore = vtePaduaRepo.save(vtePaduaScore);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private Pair<ReturnCode, ApacheIIScore> processApacheScore(
        Long pid, String scoredAtIso8601, String evalStartAtIso8601,
        String evalEndAtIso8601, ApacheIIScorePB score
    ) {
        // 验证患者ID
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<ReturnCode, ApacheIIScore>(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND), null);
        }
        final String deptId = patientRecord.getDeptId();

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return new Pair<ReturnCode, ApacheIIScore>(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND), null);
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime scoreTime = TimeUtils.fromIso8601String(scoredAtIso8601, "UTC");
        if (scoreTime == null) {
            log.error("Failed to parse score time: {}", scoredAtIso8601);
            return new Pair<ReturnCode, ApacheIIScore>(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, scoredAtIso8601), null);
        }
        LocalDateTime evalStartAt = TimeUtils.fromIso8601String(evalStartAtIso8601, "UTC");
        LocalDateTime evalEndAt = TimeUtils.fromIso8601String(evalEndAtIso8601, "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 构建评分信息
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            pid, deptId, scoreTime, accountId, accountName, evalStartAt, evalEndAt,
            nowUtc, accountId, false, null, null, null);

        // 打分, 构建Entity
        Pair<ReturnCode, ApacheIIScorePB> apacheIIScorePair = ApacheIIScorer.score(
            apacheScoreMeta, apacheScoreRules, score, statusCodeMsgList
        );
        if (apacheIIScorePair.getFirst().getCode() != 0) {
            log.error("Failed to convert Apache score to proto: entity {}, error: {}",
                score, apacheIIScorePair.getFirst().getMsg());
            return new Pair<ReturnCode, ApacheIIScore>(apacheIIScorePair.getFirst(), null);
        }

        Pair<ReturnCode, ApacheIIScore> apacheIIScoreEntityPair = ApacheIIScorer.toEntity(
            apacheScoreMeta, apacheIIScorePair.getSecond(), scorerInfo, statusCodeMsgList
        );
        if (apacheIIScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Apache score to entity: proto {}, error: {}",
                apacheIIScorePair.getSecond(), apacheIIScoreEntityPair.getFirst().getMsg());
            return new Pair<ReturnCode, ApacheIIScore>(apacheIIScoreEntityPair.getFirst(), null);
        }

        return apacheIIScoreEntityPair;
    }

    private Pair<ReturnCode, SofaScore> processSofaScore(
        Long pid, String scoredAtIso8601, String evalStartAtIso8601,
        String evalEndAtIso8601, SOFAScorePB score
    ) {
        // 验证患者ID
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<ReturnCode, SofaScore>(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND), null);
        }
        final String deptId = patientRecord.getDeptId();

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return new Pair<ReturnCode, SofaScore>(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND), null);
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime scoreTime = TimeUtils.fromIso8601String(scoredAtIso8601, "UTC");
        if (scoreTime == null) {
            log.error("Failed to parse score time: {}", scoredAtIso8601);
            return new Pair<ReturnCode, SofaScore>(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, scoredAtIso8601), null);
        }
        LocalDateTime evalStartAt = TimeUtils.fromIso8601String(evalStartAtIso8601, "UTC");
        LocalDateTime evalEndAt = TimeUtils.fromIso8601String(evalEndAtIso8601, "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 构建评分信息
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            pid, deptId, scoreTime, accountId, accountName, evalStartAt, evalEndAt,
            nowUtc, accountId, false, null, null, null);

        // 打分, 构建Entity
        Pair<ReturnCode, SOFAScorePB> sofaScorePair = SofaScorer.score(
            sofaScoreMeta, sofaScoreRules, score, statusCodeMsgList
        );
        if (sofaScorePair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to proto: entity {}, error: {}",
                score, sofaScorePair.getFirst().getMsg());
            return new Pair<ReturnCode, SofaScore>(sofaScorePair.getFirst(), null);
        }

        Pair<ReturnCode, SofaScore> sofaScoreEntityPair = SofaScorer.toEntity(
            sofaScoreMeta, sofaScorePair.getSecond(), scorerInfo, statusCodeMsgList
        );
        if (sofaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert Sofa score to entity: proto {}, error: {}",
                sofaScorePair.getSecond(), sofaScoreEntityPair.getFirst().getMsg());
            return new Pair<ReturnCode, SofaScore>(sofaScoreEntityPair.getFirst(), null);
        }

        return sofaScoreEntityPair;
    }

    private Pair<ReturnCode, VTECapriniScore> processVteCapriniScore(
        Long pid, String scoredAtIso8601, String evalStartAtIso8601,
        String evalEndAtIso8601, VTECapriniScorePB score
    ) {
        // 验证患者ID
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<>(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND), null);
        }
        final String deptId = patientRecord.getDeptId();

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return new Pair<>(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND), null);
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime scoreTime = TimeUtils.fromIso8601String(scoredAtIso8601, "UTC");
        if (scoreTime == null) {
            log.error("Failed to parse score time: {}", scoredAtIso8601);
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, scoredAtIso8601), null);
        }
        LocalDateTime evalStartAt = TimeUtils.fromIso8601String(evalStartAtIso8601, "UTC");
        LocalDateTime evalEndAt = TimeUtils.fromIso8601String(evalEndAtIso8601, "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 构建评分信息
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            pid, deptId, scoreTime, accountId, accountName, evalStartAt, evalEndAt,
            nowUtc, accountId, false, null, null, null);

        // 打分, 构建Entity
        Pair<ReturnCode, VTECapriniScorePB> vteCapriniScorePair = VTECapriniScorer.score(
            vteCapriniScoreMeta, score, statusCodeMsgList
        );
        if (vteCapriniScorePair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Caprini score to proto: entity {}, error: {}",
                score, vteCapriniScorePair.getFirst().getMsg());
            return new Pair<>(vteCapriniScorePair.getFirst(), null);
        }

        Pair<ReturnCode, VTECapriniScore> vteCapriniScoreEntityPair = VTECapriniScorer.toEntity(
            vteCapriniScoreMeta, vteCapriniScorePair.getSecond(), scorerInfo, statusCodeMsgList
        );
        if (vteCapriniScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Caprini score to entity: proto {}, error: {}",
                vteCapriniScorePair.getSecond(), vteCapriniScoreEntityPair.getFirst().getMsg());
            return new Pair<>(vteCapriniScoreEntityPair.getFirst(), null);
        }

        return vteCapriniScoreEntityPair;
    }

    private Pair<ReturnCode, VTEPaduaScore> processVtePaduaScore(
        Long pid, String scoredAtIso8601, String evalStartAtIso8601,
        String evalEndAtIso8601, VTEPaduaScorePB score
    ) {
        // 验证患者ID
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<>(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND), null);
        }
        final String deptId = patientRecord.getDeptId();

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return new Pair<>(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND), null);
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        LocalDateTime scoreTime = TimeUtils.fromIso8601String(scoredAtIso8601, "UTC");
        if (scoreTime == null) {
            log.error("Failed to parse score time: {}", scoredAtIso8601);
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.INVALID_TIME_FORMAT, scoredAtIso8601), null);
        }
        LocalDateTime evalStartAt = TimeUtils.fromIso8601String(evalStartAtIso8601, "UTC");
        LocalDateTime evalEndAt = TimeUtils.fromIso8601String(evalEndAtIso8601, "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 构建评分信息
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            pid, deptId, scoreTime, accountId, accountName, evalStartAt, evalEndAt,
            nowUtc, accountId, false, null, null, null);

        // 打分, 构建Entity
        Pair<ReturnCode, VTEPaduaScorePB> vtePaduaScorePair = VTEPaduaScorer.score(
            vtePaduaScoreMeta, score, statusCodeMsgList
        );
        if (vtePaduaScorePair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Padua score to proto: entity {}, error: {}",
                score, vtePaduaScorePair.getFirst().getMsg());
            return new Pair<>(vtePaduaScorePair.getFirst(), null);
        }

        Pair<ReturnCode, VTEPaduaScore> vtePaduaScoreEntityPair = VTEPaduaScorer.toEntity(
            vtePaduaScoreMeta, vtePaduaScorePair.getSecond(), scorerInfo, statusCodeMsgList
        );
        if (vtePaduaScoreEntityPair.getFirst().getCode() != 0) {
            log.error("Failed to convert VTE Padua score to entity: proto {}, error: {}",
                vtePaduaScorePair.getSecond(), vtePaduaScoreEntityPair.getFirst().getMsg());
            return new Pair<>(vtePaduaScoreEntityPair.getFirst(), null);
        }

        return vtePaduaScoreEntityPair;
    }

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;
    private final ApacheIIScorer apacheIIScorer;
    private final SofaScorer sofaScorer;

    private final String ZONE_ID;
    private final Map<String, String> scoreTypeMap;
    private final ApacheIIScoreMetaPB apacheScoreMeta;
    private final ApacheIIScoreRulesPB apacheScoreRules;
    private final SofaScoreMetaPB sofaScoreMeta;
    private final SofaScoreRulesPB sofaScoreRules;
    private final VTECapriniScoreMetaPB vteCapriniScoreMeta;
    private final VTEPaduaScoreMetaPB vtePaduaScoreMeta;
    private final List<String> statusCodeMsgList;

    private final DeptDoctorScoreTypeRepository deptDoctorScoreTypeRepo;
    private final ApacheIIScoreRepository apacheRepo;
    private final SofaScoreRepository sofaRepo;
    private final VTECapriniScoreRepository vteCapriniRepo;
    private final VTEPaduaScoreRepository vtePaduaRepo;
}