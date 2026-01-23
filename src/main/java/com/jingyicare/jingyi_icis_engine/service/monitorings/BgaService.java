package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class BgaService {
    public BgaService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired BgaParamRepository bgaParamRepo,
        @Autowired PatientBgaRecordRepository recordRepo,
        @Autowired PatientBgaRecordDetailRepository recordDetailRepo,
        @Autowired RawBgaRecordRepository rawBgaRecordRepo,
        @Autowired RawBgaRecordDetailRepository rawBgaRecordDetailRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.protoService = protoService;
        this.bgaCategoryMap = protoService.getConfig().getBga().getEnums().getBgaCategoryList()
            .stream().collect(Collectors.toMap(EnumValue::getId, EnumValue::getName));
        this.userService = userService;
        this.patientService = patientService;
        this.patientDeviceService = patientDeviceService;
        this.monitoringConfig = monitoringConfig;
        this.pnrUtils = pnrUtils;

        this.bgaParamRepository = bgaParamRepo;
        this.recordRepository = recordRepo;
        this.recordDetailRepository = recordDetailRepo;
        this.rawBgaRecordRepository = rawBgaRecordRepo;
        this.rawBgaRecordDetailRepository = rawBgaRecordDetailRepo;
    }

    @Transactional
    public GetBgaParamsResp getBgaParams(String getBgaParamsReqJson) {
        final GetBgaParamsReq req;
        try {
            GetBgaParamsReq.Builder builder = GetBgaParamsReq.newBuilder();
            JsonFormat.parser().merge(getBgaParamsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetBgaParamsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        List<BgaParamPB> bgaParamPbs = new ArrayList<>();
        for (BgaParam bgaParam : monitoringConfig.getBgaParamList(deptId)) {
            MonitoringParamPB monitoringParamPb = monitoringConfig.getMonitoringParam(
                deptId, bgaParam.getMonitoringParamCode()
            );
            if (monitoringParamPb == null) {
                log.error("Failed to find monitoring param: {}", bgaParam.getMonitoringParamCode());
                continue;
            }
            bgaParamPbs.add(BgaParamPB.newBuilder()
                .setParamCode(bgaParam.getMonitoringParamCode())
                .setDisplayOrder(bgaParam.getDisplayOrder())
                .setEnabled(bgaParam.getEnabled())
                .setParam(monitoringParamPb)
                .build()
            );
        }


        return GetBgaParamsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllParam(bgaParamPbs)
            .build();
    }

    @Transactional
    public GenericResp enableBgaParam(String enableBgaParamReqJson) {
        final EnableBgaParamReq req;
        try {
            EnableBgaParamReq.Builder builder = EnableBgaParamReq.newBuilder();
            JsonFormat.parser().merge(enableBgaParamReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取参数信息
        BgaParam bgaParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            req.getDeptId(), req.getParamCode()
        ).orElse(null);
        if (bgaParam == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.BGA_PARAM_CODE_NOT_FOUND))
                .build();
        }

        // 更新参数信息
        bgaParam.setEnabled(req.getEnabled());
        bgaParam.setModifiedBy(accountId);
        bgaParam.setModifiedAt(TimeUtils.getNowUtc());
        bgaParamRepository.save(bgaParam);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reorderBgaParams(String reorderBgaParamsReqJson) {
        final ReorderBgaParamsReq req;
        try {
            ReorderBgaParamsReq.Builder builder = ReorderBgaParamsReq.newBuilder();
            JsonFormat.parser().merge(reorderBgaParamsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取参数信息
        Map<String, BgaParam> bgaParams = bgaParamRepository.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(req.getDeptId())
            .stream()
            .collect(Collectors.toMap(BgaParam::getMonitoringParamCode, bgaParam -> bgaParam));

        List<BgaParam> paramsToUpdate = new ArrayList<>();
        for (BgaParamPB bgaParamPb : req.getParamList()) {
            BgaParam bgaParam = bgaParams.get(bgaParamPb.getParamCode());
            if (bgaParam == null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.BGA_PARAM_CODE_NOT_FOUND, bgaParamPb.getParamCode()))
                    .build();
            }
            bgaParam.setDisplayOrder(bgaParamPb.getDisplayOrder());
            bgaParam.setModifiedBy(accountId);
            bgaParam.setModifiedAt(TimeUtils.getNowUtc());
            paramsToUpdate.add(bgaParam);
        }
        bgaParamRepository.saveAll(paramsToUpdate);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetPatientBgaRecordsResp getPatientBgaRecords(String getPatientBgaRecordsReqJson) {
        final GetPatientBgaRecordsReq req;
        try {
            GetPatientBgaRecordsReq.Builder builder = GetPatientBgaRecordsReq.newBuilder();
            JsonFormat.parser().merge(getPatientBgaRecordsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetPatientBgaRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取元数据
        final Long pid = req.getPid();
        final LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            return GetPatientBgaRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_RANGE))
                .build();
        }
        final boolean forceSync = req.getForceSync();

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GetPatientBgaRecordsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 验证患者ID
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientBgaRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 获取血气记录
        List<PatientBgaRecord> records = recordRepository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
            req.getPid(), queryStartUtc, queryEndUtc
        );
        List<Long> recordIds = records.stream().map(PatientBgaRecord::getId).collect(Collectors.toList());
        Map<Long, List<PatientBgaRecordDetail>> recordDetails = recordDetailRepository
            .findByRecordIdInAndIsDeletedFalse(recordIds)
            .stream().collect(Collectors.groupingBy(PatientBgaRecordDetail::getRecordId));
        if (forceSync) {
            syncRawBgaRecords(
                patientRecord, records, recordDetails, queryStartUtc, queryEndUtc,
                accountId, accountName, nowUtc
            );

            // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
            pnrUtils.updateLatestDataTime(pid, deptId, queryStartUtc, queryEndUtc, nowUtc);
        }
        records = records.stream().sorted(
            Comparator.comparing(PatientBgaRecord::getEffectiveTime).reversed()).toList();

        // 组装血气记录
        List<PatientBgaRecord> recordsToUpdate = new ArrayList<>();
        List<PatientBgaRecordPB> recordPbs = new ArrayList<>();
        for (PatientBgaRecord record : records) {
            String reviewedBy = StrUtils.isBlank(record.getReviewedByAccountName()) ? "" : record.getReviewedByAccountName();
            if (StrUtils.isBlank(record.getRecordedBy())) {
                record.setRecordedBy(accountId);
                record.setRecordedByAccountName(accountName);
                record.setRecordedAt(nowUtc);
                recordsToUpdate.add(record);
            }

            PatientBgaRecordPB.Builder recordPb = PatientBgaRecordPB.newBuilder()
                .setId(record.getId())
                .setPid(record.getPid())
                .setBgaCategoryId(record.getBgaCategoryId())
                .setBgaCategoryName(record.getBgaCategoryName())
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .setRecordedBy(record.getRecordedByAccountName())
                .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getRecordedAt(), ZONE_ID))
                .setModifiedBy(record.getModifiedByAccountName())
                .setReviewedBy(reviewedBy)
                .setReviewedAtIso8601(TimeUtils.toIso8601String(record.getReviewedAt(), ZONE_ID));

            for (PatientBgaRecordDetail detail :
                recordDetails.getOrDefault(record.getId(), new ArrayList<>())
                    .stream().sorted(Comparator.comparing(PatientBgaRecordDetail::getId)).toList()
            ) {
                GenericValuePB genericValue = ProtoUtils.decodeGenericValue(detail.getParamValue());
                if (genericValue == null) continue;
                ValueMetaPB valueMeta = monitoringConfig.getMonitoringParamMeta(deptId, detail.getMonitoringParamCode());
                if (valueMeta == null) continue;
                String valueStr = ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta);
                recordPb.addBgaResult(BgaResultPB.newBuilder()
                    .setId(detail.getId())
                    .setParamCode(detail.getMonitoringParamCode())
                    .setValue(genericValue)
                    .setValueStr(valueStr)
                    .build()
                );
            }
            recordPbs.add(recordPb.build());
        }
        if (!recordsToUpdate.isEmpty()) {
            recordRepository.saveAll(recordsToUpdate);
        }

        return GetPatientBgaRecordsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllRecord(recordPbs)
            .build();
    }

    @Transactional
    public AddPatientBgaRecordResp addPatientBgaRecord(String addPatientBgaRecordReqJson) {
        final AddPatientBgaRecordReq req;
        try {
            AddPatientBgaRecordReq.Builder builder = AddPatientBgaRecordReq.newBuilder();
            JsonFormat.parser().merge(addPatientBgaRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 验证患者ID
        Long pid = req.getRecord().getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 检查BGA记录是否已存在
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(req.getRecord().getEffectiveTimeIso8601(), "UTC");
        if (effectiveTime == null) {
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        if (patientRecord.getAdmissionTime() != null && effectiveTime.isBefore(patientRecord.getAdmissionTime())) {
            log.error("Effective time is before admission time: {}, {}", effectiveTime, patientRecord.getAdmissionTime());
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TIME_IS_BEFORE_ADMISSION_TIME))
                .build();
        }
        if (patientRecord.getDischargeTime() != null && effectiveTime.isAfter(patientRecord.getDischargeTime())) {
            log.error("Effective time is after discharge time: {}, {}", effectiveTime, patientRecord.getDischargeTime());
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TIME_IS_AFTER_DISCHARGE_TIME))
                .build();
        }

        PatientBgaRecord existingRecord = recordRepository.findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
            pid, req.getRecord().getBgaCategoryId(), effectiveTime
        ).orElse(null);
        if (existingRecord != null) {
            return AddPatientBgaRecordResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_BGA_RECORD_ALREADY_EXISTS))
                .build();
        }

        // 插入BGA记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime reviewedAt = TimeUtils.fromIso8601String(req.getRecord().getReviewedAtIso8601(), "UTC");
        PatientBgaRecord record = PatientBgaRecord.builder()
            .pid(pid)
            .deptId(deptId)
            .bgaCategoryId(req.getRecord().getBgaCategoryId())
            .bgaCategoryName(req.getRecord().getBgaCategoryName())
            .recordedBy(accountId)
            .recordedByAccountName(accountName)
            .recordedAt(nowUtc)
            .effectiveTime(effectiveTime)
            .isDeleted(false)
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .modifiedAt(nowUtc)
            .reviewedBy(req.getRecord().getReviewedBy())
            .reviewedAt(reviewedAt)
            .build();
        record = recordRepository.save(record);
        Long recordId = record.getId();

        // 新建BGA记录明细
        for (BgaResultPB bgaResultPb : req.getRecord().getBgaResultList()) {
            GenericValuePB genericValue = bgaResultPb.getValue();
            String paramCode = bgaResultPb.getParamCode();
            ValueMetaPB valueMeta = monitoringConfig.getMonitoringParamMeta(deptId, paramCode);
            if (valueMeta == null) continue;
            String valueStr = ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta);
            PatientBgaRecordDetail recordDetail = PatientBgaRecordDetail.builder()
                .recordId(recordId)
                .monitoringParamCode(paramCode)
                .paramValue(ProtoUtils.encodeGenericValue(genericValue))
                .paramValueStr(valueStr)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(nowUtc)
                .build();
            recordDetailRepository.save(recordDetail);
        }

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        log.info("Added BGA record: {}", req.getRecord().getId());

        return AddPatientBgaRecordResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .setId(recordId)
            .build();
    }

    public GenericResp updatePatientBgaRecord(String updatePatientBgaRecordReqJson) {
        final AddPatientBgaRecordReq req;
        try {
            AddPatientBgaRecordReq.Builder builder = AddPatientBgaRecordReq.newBuilder();
            JsonFormat.parser().merge(updatePatientBgaRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 验证患者ID
        Long pid = req.getRecord().getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 更新BGA记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(req.getRecord().getEffectiveTimeIso8601(), "UTC");
        if (effectiveTime == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        Pair<ReturnCode, Long> updateResult = updateExistingRecord(
            req.getRecord(), effectiveTime, accountId, accountName, nowUtc
        );
        if (updateResult.getFirst().getCode() != 0) {
            return GenericResp.newBuilder()
                .setRt(updateResult.getFirst())
                .build();
        }
        Long recordId = updateResult.getSecond();

        // 删除旧的BGA记录明细
        deleteOldDetails(recordId, accountId, nowUtc);

        // 新建BGA记录明细
        saveNewDetails(recordId, deptId, req.getRecord().getBgaResultList(), accountId, nowUtc);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        log.info("Updated BGA record: {}", req.getRecord().getId());

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientBgaRecord(String deletePatientBgaRecordReqJson) {
        final DeletePatientBgaRecordReq req;
        try {
            DeletePatientBgaRecordReq.Builder builder = DeletePatientBgaRecordReq.newBuilder();
            JsonFormat.parser().merge(deletePatientBgaRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查BGA记录是否已存在
        PatientBgaRecord existingRecord = recordRepository.findByIdAndIsDeletedFalse(
            req.getId()).orElse(null);
        if (existingRecord == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .build();
        }
        Long recordId = existingRecord.getId();
        final Long pid = existingRecord.getPid();
        final LocalDateTime effectiveTime = existingRecord.getEffectiveTime();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 删除BGA记录
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        existingRecord.setIsDeleted(true);
        existingRecord.setDeletedBy(accountId);
        existingRecord.setDeletedByAccountName(accountName);
        existingRecord.setDeletedAt(nowUtc);
        recordRepository.save(existingRecord);

        // 删除BGA记录明细
        List<PatientBgaRecordDetail> oldDetails = recordDetailRepository.findByRecordIdAndIsDeletedFalse(recordId);
        for (PatientBgaRecordDetail detail : oldDetails) {
            detail.setIsDeleted(true);
            detail.setDeletedBy(accountId);
            detail.setDeletedAt(nowUtc);
        }
        recordDetailRepository.saveAll(oldDetails);
        log.info("Deleted BGA record: {}", req.getId());

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp reviewPatientBgaRecords(String reviewPatientBgaRecordsReqJson) {
        final ReviewPatientBgaRecordsReq req;
        try {
            ReviewPatientBgaRecordsReq.Builder builder = ReviewPatientBgaRecordsReq.newBuilder();
            JsonFormat.parser().merge(reviewPatientBgaRecordsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 审核时间
        LocalDateTime reviewTime = TimeUtils.fromIso8601String(req.getReviewTimeIso8601(), "UTC");
        if (reviewTime == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 更新记录
        List<PatientBgaRecord> recordsToUpdate = recordRepository.findByIdInAndIsDeletedFalse(req.getIdList());
        if (recordsToUpdate.size() != req.getIdCount()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_BGA_RECORD_NOT_FOUND))
                .build();
        }

        for (PatientBgaRecord record : recordsToUpdate) {
            record.setReviewedBy(accountId);
            record.setReviewedByAccountName(accountName);
            record.setReviewedAt(reviewTime);
        }
        recordRepository.saveAll(recordsToUpdate);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Pair<ReturnCode, Long> updateExistingRecord(
        PatientBgaRecordPB record, LocalDateTime effectiveTime,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        PatientBgaRecord existingRecord = recordRepository.findByIdAndIsDeletedFalse(record.getId()).orElse(null);
        if (existingRecord == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_BGA_RECORD_NOT_FOUND),
                null
            );
        }

        // 如果有另一条记录，和已有记录的时间相同，不允许修改
        PatientBgaRecord recordToUpdate = recordRepository.findByPidAndBgaCategoryIdAndEffectiveTimeAndIsDeletedFalse(
            record.getPid(), record.getBgaCategoryId(), effectiveTime
        ).orElse(null);
        if (recordToUpdate != null && !recordToUpdate.getId().equals(record.getId())) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_BGA_RECORD_ALREADY_EXISTS),
                null
            );
        }

        // 获取患者记录
        final PatientRecord patientRecord = patientService.getPatientRecord(record.getPid());
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_NOT_FOUND),
                null
            );
        }
        if (patientRecord.getAdmissionTime() != null && effectiveTime.isBefore(patientRecord.getAdmissionTime())) {
            log.error("Effective time is before admission time: {}, {}", effectiveTime, patientRecord.getAdmissionTime());
            return  new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.TIME_IS_BEFORE_ADMISSION_TIME),
                null
            );
        }
        if (patientRecord.getDischargeTime() != null && effectiveTime.isAfter(patientRecord.getDischargeTime())) {
            log.error("Effective time is after discharge time: {}, {}", effectiveTime, patientRecord.getDischargeTime());
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.TIME_IS_AFTER_DISCHARGE_TIME),
                null
            );
        }

        existingRecord.setBgaCategoryId(record.getBgaCategoryId());
        existingRecord.setBgaCategoryName(record.getBgaCategoryName());
        existingRecord.setEffectiveTime(effectiveTime);
        existingRecord.setModifiedBy(accountId);
        existingRecord.setModifiedByAccountName(accountName);
        existingRecord.setModifiedAt(nowUtc);
        existingRecord.setReviewedBy(record.getReviewedBy());
        LocalDateTime reviewedAt = TimeUtils.fromIso8601String(record.getReviewedAtIso8601(), "UTC");
        existingRecord.setReviewedAt(reviewedAt);
        existingRecord = recordRepository.save(existingRecord);

        return new Pair<>(
            ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK),
            existingRecord.getId()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deleteOldDetails(Long recordId, String accountId, LocalDateTime nowUtc) {
        List<PatientBgaRecordDetail> oldDetails = recordDetailRepository.findByRecordIdAndIsDeletedFalse(recordId);
        for (PatientBgaRecordDetail detail : oldDetails) {
            detail.setIsDeleted(true);
            detail.setDeletedBy(accountId);
            detail.setDeletedAt(nowUtc);
        }
        recordDetailRepository.saveAll(oldDetails);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveNewDetails(Long recordId, String deptId, List<BgaResultPB> bgaResultList, String accountId, LocalDateTime nowUtc) {
        List<PatientBgaRecordDetail> newDetails = new ArrayList<>();
        for (BgaResultPB bgaResultPb : bgaResultList) {
            GenericValuePB genericValue = bgaResultPb.getValue();
            String paramCode = bgaResultPb.getParamCode();
            ValueMetaPB valueMeta = monitoringConfig.getMonitoringParamMeta(deptId, paramCode);
            if (valueMeta == null) continue;
            String valueStr = ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta);
            PatientBgaRecordDetail recordDetail = PatientBgaRecordDetail.builder()
                .recordId(recordId)
                .monitoringParamCode(paramCode)
                .paramValue(ProtoUtils.encodeGenericValue(genericValue))
                .paramValueStr(valueStr)
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(nowUtc)
                .build();
            newDetails.add(recordDetail);
        }
        recordDetailRepository.saveAll(newDetails);
    }

    @Transactional
    public Pair<ValueMetaPB, List<TimedGenericValuePB>> fetchArterialBgaRecords(
        Long pid, String deptId, String bgaCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, bgaCode);
        if (meta == null) {
            log.error("Missing meta for param: {}", bgaCode);
            return null;
        }

        // 获取血气明细记录
        final Integer arterialBgaCategoryId = 1;
        List<PatientBgaRecordDetail> details = recordDetailRepository.findRecordDetails(
            pid, arterialBgaCategoryId, bgaCode, queryStartUtc, queryEndUtc
        );

        // 获取血气记录
        List<Long> recordIds = details.stream()
            .map(PatientBgaRecordDetail::getRecordId).distinct().toList();
        Map<Long, LocalDateTime> recordEffectiveTimeMap = recordRepository
            .findByIdInAndIsDeletedFalse(recordIds)
            .stream()
            .collect(Collectors.toMap(PatientBgaRecord::getId, PatientBgaRecord::getEffectiveTime));

        // 血气记录明细转化成血气记录明细
        List<TimedGenericValuePB> timedValues = new ArrayList<>();
        for (PatientBgaRecordDetail detail : details) {
            GenericValuePB genericValue = ProtoUtils.decodeGenericValue(detail.getParamValue());
            if (genericValue == null) continue;

            // 血气记录的有效时间
            LocalDateTime recordEffectiveTime = recordEffectiveTimeMap.get(detail.getRecordId());
            if (recordEffectiveTime == null) continue;

            final String effectiveTimeIso8601 = TimeUtils.toIso8601String(recordEffectiveTime, ZONE_ID);
            if (effectiveTimeIso8601 == null) continue;

            TimedGenericValuePB timedValue = TimedGenericValuePB.newBuilder()
                .setRecordedAtIso8601(effectiveTimeIso8601)
                .setValue(genericValue)
                .setValueStr(detail.getParamValueStr())
                .build();
            timedValues.add(timedValue);
        }
        timedValues = timedValues.stream()
            .sorted(Comparator.comparing(TimedGenericValuePB::getRecordedAtIso8601))
            .toList();

        return new Pair<>(meta, timedValues);
    }

    private void syncRawBgaRecords(
        PatientRecord patientRecord,
        List<PatientBgaRecord> records,
        Map<Long, List<PatientBgaRecordDetail>> recordDetails,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        final Long pid = patientRecord.getId();
        final String deptId = patientRecord.getDeptId();
log.info("\n\n\nSyncing raw BGA records for patient {}, from {} to {}",
    pid, queryStartUtc, queryEndUtc);
        // 根据MRN和床位历史查询对应的原始血气记录
        List<RawBgaRecord> rawBgaRecords = collectRawBgaRecords(patientRecord, queryStartUtc, queryEndUtc);

        // 将原始血气记录转化成病人的血气记录，并返回原始血气到病人血气记录的映射
        List<PatientBgaRecord> savedRecords = createNewPatientRecords(
            rawBgaRecords, pid, deptId, accountId, accountName, nowUtc);
        records.addAll(savedRecords);

        // 将原始血气记录明细转化成病人的血气记录明细，返回病人的血气记录明细
        List<PatientBgaRecordDetail> newDetails = createNewRecordDetails(
            savedRecords, deptId, accountId, accountName, nowUtc
        );

        for (PatientBgaRecordDetail newDetail : newDetails) {
            recordDetails
                .computeIfAbsent(newDetail.getRecordId(), k -> new ArrayList<>())
                .add(newDetail);
        }
    }

    private List<RawBgaRecord> collectRawBgaRecords(
        PatientRecord patient, LocalDateTime start, LocalDateTime end
    ) {
        List<RawBgaRecord> result = new ArrayList<>();
        result.addAll(rawBgaRecordRepository
            .findByMrnBednumAndEffectiveTimeBetween(patient.getHisMrn(), start, end));

        PatientDeviceService.UsageHistory<PatientDeviceService.BedName> bedHistory =
            patientDeviceService.getBedHistory(patient);
        for (int i = 0; i < bedHistory.usageRecords.size(); i++) {
            PatientDeviceService.BedName bedName = bedHistory.usageRecords.get(i).getFirst();
            if (bedName.switchType == patientDeviceService.getSwitchTypeReadmissionAdmitId()) {
                continue;
            }
            LocalDateTime bedStart = bedHistory.usageRecords.get(i).getSecond();
            LocalDateTime bedEnd = (i + 1 < bedHistory.usageRecords.size())
                ? bedHistory.usageRecords.get(i + 1).getSecond()
                : (bedHistory.endTime != null ? bedHistory.endTime : TimeUtils.getLocalTime(9999, 1, 1));
            String bedNumber = bedName.displayBedNumber;

            // 床号匹配尾数，前面0可以忽略
            List<RawBgaRecord> bedRecords = rawBgaRecordRepository
                .findByEffectiveTimeBetween(bedStart, bedEnd);
            List<RawBgaRecord> qualifiedBedRecords = new ArrayList<>();
            for (RawBgaRecord rec : bedRecords) {
                if (bedNumber.endsWith(rec.getMrnBednum())) {
                    String prefix = bedNumber.substring(0, bedNumber.length() - rec.getMrnBednum().length());
                    if (prefix.isEmpty() || prefix.matches("0+")) {
                        qualifiedBedRecords.add(rec);
                    }
                }
            }
            result.addAll(qualifiedBedRecords);
        }
        return result;
    }

    private List<PatientBgaRecord> createNewPatientRecords(
        List<RawBgaRecord> rawRecords, Long pid, String deptId,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        // 将rawRecords按照mrn_bednum, effective_time去重，保留id最大的记录
        rawRecords.sort(Comparator.comparing(RawBgaRecord::getId).reversed());
        Map<String, Set<LocalDateTime>> seen = new HashMap<>();
        List<RawBgaRecord> uniqueRecords = new ArrayList<>();
        for (RawBgaRecord raw : rawRecords) {
            String mrnOrBedNum = raw.getMrnBednum();
            LocalDateTime effectiveTime = raw.getEffectiveTime();
            if (!seen.containsKey(mrnOrBedNum) || !seen.get(mrnOrBedNum).contains(effectiveTime)) {
                seen.computeIfAbsent(mrnOrBedNum, k -> new HashSet<>()).add(effectiveTime);
                uniqueRecords.add(raw);
            }
        }
        rawRecords = uniqueRecords;

        // 将原始血气记录转化成病人的血气记录
        List<PatientBgaRecord> toSave = new ArrayList<>();

        for (RawBgaRecord raw : rawRecords) {
            boolean exists = recordRepository.existsByPidAndRawRecordId(pid, raw.getId());
            if (exists) continue;

            PatientBgaRecord rec = PatientBgaRecord.builder()
                .pid(pid)
                .deptId(deptId)
                .bgaCategoryId(raw.getBgaCategoryId())
                .bgaCategoryName(bgaCategoryMap.getOrDefault(raw.getBgaCategoryId(), ""))
                .effectiveTime(raw.getEffectiveTime())
                .rawRecordId(raw.getId())
                .isDeleted(false)
                .recordedBy(accountId)
                .recordedByAccountName(accountName)
                .recordedAt(nowUtc)
                .modifiedBy(accountId)
                .modifiedByAccountName(accountName)
                .modifiedAt(nowUtc)
                .build();
            toSave.add(rec);
        }

        return recordRepository.saveAll(toSave);
    }

    private List<PatientBgaRecordDetail> createNewRecordDetails(
        List<PatientBgaRecord> bgaRecords, String deptId,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        List<Long> rawIds = bgaRecords.stream().map(PatientBgaRecord::getRawRecordId).toList();
        Map<Long, Long> rawToRecordMap = bgaRecords.stream()
            .collect(Collectors.toMap(PatientBgaRecord::getRawRecordId, PatientBgaRecord::getId));
        List<RawBgaRecordDetail> rawDetails = rawBgaRecordDetailRepository.findByRecordIdIn(rawIds);
        List<PatientBgaRecordDetail> newDetails = new ArrayList<>();

        for (RawBgaRecordDetail raw : rawDetails) {
            Long recordId = rawToRecordMap.get(raw.getRecordId());
            if (recordId == null) {
                log.error("No matching record for raw detail: {}", raw.getRecordId());
                continue;
            }

            ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, raw.getMonitoringParamCode());
            if (meta == null) {
                log.error("Missing meta for param: {}", raw.getMonitoringParamCode());
                continue;
            }

            GenericValuePB value = ValueMetaUtils.toGenericValue(raw.getParamValueStr(), meta);
            if (value == null) {
                log.error("Invalid value: {}", raw.getParamValueStr());
                continue;
            }

            newDetails.add(PatientBgaRecordDetail.builder()
                .recordId(recordId)
                .monitoringParamCode(raw.getMonitoringParamCode())
                .paramValue(ProtoUtils.encodeGenericValue(value))
                .paramValueStr(raw.getParamValueStr())
                .isDeleted(false)
                .modifiedBy(accountId)
                .modifiedAt(nowUtc)
                .build());
        }

        return recordDetailRepository.saveAll(newDetails);
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgs;

    private final ConfigProtoService protoService;
    private final Map<Integer, String> bgaCategoryMap;
    private final UserService userService;
    private final PatientService patientService;
    private final PatientDeviceService patientDeviceService;
    private final MonitoringConfig monitoringConfig;
    private final PatientNursingReportUtils pnrUtils;

    private final BgaParamRepository bgaParamRepository;
    private final PatientBgaRecordRepository recordRepository;
    private final PatientBgaRecordDetailRepository recordDetailRepository;
    private final RawBgaRecordRepository rawBgaRecordRepository;
    private final RawBgaRecordDetailRepository rawBgaRecordDetailRepository;
}