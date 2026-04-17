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

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PatientNursingReportInvalidationService;
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
        @Autowired PatientNursingReportInvalidationService pnrUtils,
        @Autowired RbacDepartmentRepository departmentRepo,
        @Autowired BgaParamRepository bgaParamRepo,
        @Autowired BgaCategoryMappingRepository bgaCategoryMappingRepo,
        @Autowired PatientLisItemRepository lisItemRepo,
        @Autowired PatientLisResultRepository lisResultRepo,
        @Autowired PatientBgaRecordRepository recordRepo,
        @Autowired PatientBgaRecordDetailRepository recordDetailRepo,
        @Autowired RawBgaRecordRepository rawBgaRecordRepo,
        @Autowired RawBgaRecordDetailRepository rawBgaRecordDetailRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.protoService = protoService;
        List<EnumValue> bgaCategories = protoService.getConfig().getBga().getEnums().getBgaCategoryList();
        this.bgaCategoryIds = bgaCategories.stream().map(EnumValue::getId).toList();
        this.bgaCategoryMap = bgaCategories.stream().collect(Collectors.toMap(
            EnumValue::getId, EnumValue::getName, (left, right) -> left, LinkedHashMap::new
        ));
        this.userService = userService;
        this.patientService = patientService;
        this.patientDeviceService = patientDeviceService;
        this.monitoringConfig = monitoringConfig;
        this.pnrUtils = pnrUtils;
        this.departmentRepository = departmentRepo;

        this.bgaParamRepository = bgaParamRepo;
        this.bgaCategoryMappingRepository = bgaCategoryMappingRepo;
        this.patientLisItemRepository = lisItemRepo;
        this.patientLisResultRepository = lisResultRepo;
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
                .setLisResultCode(StrUtils.getStringOrDefault(bgaParam.getLisResultCode(), ""))
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
    public GenericResp saveBgaParam(String saveBgaParamReqJson) {
        final SaveBgaParamReq req;
        try {
            SaveBgaParamReq.Builder builder = SaveBgaParamReq.newBuilder();
            JsonFormat.parser().merge(saveBgaParamReqJson, builder);
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
        bgaParam.setLisResultCode(normalizeNullableString(req.getLisResultCode()));
        bgaParam.setModifiedBy(accountId);
        bgaParam.setModifiedAt(TimeUtils.getNowUtc());
        bgaParamRepository.save(bgaParam);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetBgaCategoryResp getBgaCategory(String getBgaCategoryReqJson) {
        final GetBgaCategoryReq req;
        try {
            GetBgaCategoryReq.Builder builder = GetBgaCategoryReq.newBuilder();
            JsonFormat.parser().merge(getBgaCategoryReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetBgaCategoryResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<BgaCategoryMappingPB> mappingPbs = getOrCreateBgaCategoryMappings(req.getDeptId()).stream()
            .map(mapping -> BgaCategoryMappingPB.newBuilder()
                .setId(mapping.getId())
                .setDeptId(mapping.getDeptId())
                .setBgaCategoryId(mapping.getBgaCategoryId())
                .setLisItemCode(StrUtils.getStringOrDefault(mapping.getLisItemCode(), ""))
                .build()
            )
            .toList();

        return GetBgaCategoryResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllMapping(mappingPbs)
            .build();
    }

    @Transactional
    public GenericResp saveBgaCategory(String saveBgaCategoryReqJson) {
        final BgaCategoryMappingPB req;
        try {
            SaveBgaCategoryReq.Builder builder = SaveBgaCategoryReq.newBuilder();
            JsonFormat.parser().merge(saveBgaCategoryReqJson, builder);
            req = builder.build().getBgaCategoryMapping();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        final String accountId = account.getFirst();
        final String deptId = req.getDeptId();
        final Integer bgaCategoryId = req.getBgaCategoryId();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        BgaCategoryMapping mapping = req.getId() > 0
            ? bgaCategoryMappingRepository.findByIdAndIsDeletedFalse(req.getId()).orElse(null)
            : null;
        if (mapping == null) {
            mapping = getOrCreateBgaCategoryMappings(deptId).stream()
                .filter(item -> Objects.equals(item.getBgaCategoryId(), bgaCategoryId))
                .findFirst()
                .orElse(null);
        }
        if (mapping == null) {
            mapping = BgaCategoryMapping.builder()
                .deptId(deptId)
                .bgaCategoryId(bgaCategoryId)
                .isDeleted(false)
                .build();
        }

        mapping.setDeptId(deptId);
        mapping.setBgaCategoryId(bgaCategoryId);
        mapping.setLisItemCode(normalizeNullableString(req.getLisItemCode()));
        mapping.setModifiedBy(accountId);
        mapping.setModifiedAt(nowUtc);
        bgaCategoryMappingRepository.save(mapping);

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

        if (forceSync) {
            syncPatientBgaRecords(
                patientRecord, queryStartUtc, queryEndUtc,
                accountId, accountName, nowUtc
            );

            // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
            pnrUtils.updateLatestDataTime(pid, deptId, queryStartUtc, queryEndUtc, nowUtc);
        }

        // 获取血气记录
        List<PatientBgaRecord> records = recordRepository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
            req.getPid(), queryStartUtc, queryEndUtc
        );
        Map<Long, List<PatientBgaRecordDetail>> recordDetails = getRecordDetailsMap(records);
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
        List<Long> recordIds = req.getIdList().stream().distinct().toList();
        List<PatientBgaRecord> recordsToUpdate = recordRepository.findByIdInAndIsDeletedFalse(recordIds);
        if (recordsToUpdate.size() != recordIds.size()) {
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

    private Map<Long, List<PatientBgaRecordDetail>> getRecordDetailsMap(List<PatientBgaRecord> records) {
        List<Long> recordIds = records.stream().map(PatientBgaRecord::getId).toList();
        if (recordIds.isEmpty()) return new HashMap<>();
        return recordDetailRepository.findByRecordIdInAndIsDeletedFalse(recordIds)
            .stream()
            .collect(Collectors.groupingBy(PatientBgaRecordDetail::getRecordId));
    }

    private void syncPatientBgaRecords(
        PatientRecord patientRecord,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        String deptId = patientRecord.getDeptId();
        Map<Integer, String> cateItemMap = new LinkedHashMap<>();
        for (BgaCategoryMapping mapping : getOrCreateBgaCategoryMappings(deptId)) {
            cateItemMap.putIfAbsent(mapping.getBgaCategoryId(), normalizeNullableString(mapping.getLisItemCode()));
        }

        List<BgaSyncRecordCandidate> candidates = new ArrayList<>();
        candidates.addAll(collectRawBgaCandidates(patientRecord, cateItemMap, queryStartUtc, queryEndUtc, nowUtc));
        candidates.addAll(collectLisBgaCandidates(patientRecord, cateItemMap, queryStartUtc, queryEndUtc));

        upsertSyncedBgaRecords(
            patientRecord.getId(), deptId, cateItemMap, candidates,
            accountId, accountName, nowUtc, queryStartUtc, queryEndUtc
        );
    }

    private List<BgaSyncRecordCandidate> collectRawBgaCandidates(
        PatientRecord patientRecord, Map<Integer, String> cateItemMap,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc, LocalDateTime nowUtc
    ) {
        String deptId = patientRecord.getDeptId();
        List<RawBgaRecord> rawRecords = collectRawBgaRecords(patientRecord, queryStartUtc, queryEndUtc).stream()
            .filter(raw -> StrUtils.isBlank(cateItemMap.get(raw.getBgaCategoryId())))
            .sorted(Comparator.comparing(RawBgaRecord::getId).reversed())
            .toList();
        if (rawRecords.isEmpty()) return Collections.emptyList();

        Map<String, RawBgaRecord> uniqueRawRecordMap = new LinkedHashMap<>();
        for (RawBgaRecord rawRecord : rawRecords) {
            uniqueRawRecordMap.putIfAbsent(buildRawRecordDedupKey(rawRecord), rawRecord);
        }

        List<RawBgaRecord> uniqueRawRecords = new ArrayList<>(uniqueRawRecordMap.values());
        List<Long> rawRecordIds = uniqueRawRecords.stream().map(RawBgaRecord::getId).toList();
        Map<Long, List<RawBgaRecordDetail>> rawDetailMap = rawRecordIds.isEmpty()
            ? Collections.emptyMap()
            : rawBgaRecordDetailRepository.findByRecordIdIn(rawRecordIds).stream()
                .collect(Collectors.groupingBy(RawBgaRecordDetail::getRecordId));

        List<BgaSyncRecordCandidate> candidates = new ArrayList<>();
        for (RawBgaRecord rawRecord : uniqueRawRecords) {
            candidates.add(new BgaSyncRecordCandidate(
                BgaRecordSource.RAW,
                rawRecord.getBgaCategoryId(),
                bgaCategoryMap.getOrDefault(rawRecord.getBgaCategoryId(), ""),
                rawRecord.getEffectiveTime(),
                null,
                rawRecord.getId(),
                nowUtc,
                buildRawDetailCandidates(
                    rawDetailMap.getOrDefault(rawRecord.getId(), Collections.emptyList()),
                    deptId
                )
            ));
        }
        return candidates;
    }

    private List<BgaSyncRecordCandidate> collectLisBgaCandidates(
        PatientRecord patientRecord, Map<Integer, String> cateItemMap,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        String hisPid = patientRecord.getHisPatientId();
        if (StrUtils.isBlank(hisPid)) return Collections.emptyList();

        Map<String, List<Integer>> itemCodeToCategoryIds = new LinkedHashMap<>();
        for (Map.Entry<Integer, String> entry : cateItemMap.entrySet()) {
            String lisItemCode = normalizeNullableString(entry.getValue());
            if (StrUtils.isBlank(lisItemCode)) continue;
            itemCodeToCategoryIds.computeIfAbsent(lisItemCode, key -> new ArrayList<>()).add(entry.getKey());
        }
        if (itemCodeToCategoryIds.isEmpty()) return Collections.emptyList();

        Map<String, List<String>> lisResultCodeToParamCodes = getLisResultCodeToMonitoringParamCodes(
            patientRecord.getDeptId()
        );
        if (lisResultCodeToParamCodes.isEmpty()) return Collections.emptyList();

        List<PatientLisItem> lisItems = collectPatientLisItems(
            hisPid, new ArrayList<>(itemCodeToCategoryIds.keySet()), queryStartUtc, queryEndUtc
        );
        if (lisItems.isEmpty()) return Collections.emptyList();

        Map<String, PatientLisItem> reportIdToItemMap = lisItems.stream()
            .collect(Collectors.toMap(
                PatientLisItem::getReportId,
                item -> item,
                this::selectPreferredLisItem,
                LinkedHashMap::new
            ));
        List<String> reportIds = lisItems.stream()
            .map(PatientLisItem::getReportId)
            .distinct()
            .toList();
        List<String> externalParamCodes = new ArrayList<>(lisResultCodeToParamCodes.keySet());
        Map<String, Map<String, PatientLisResult>> latestResultMap = buildLatestLisResultMap(
            reportIdToItemMap, reportIds, externalParamCodes
        );

        List<BgaSyncRecordCandidate> candidates = new ArrayList<>();
        for (PatientLisItem lisItem : lisItems) {
            String lisItemCode = normalizeNullableString(lisItem.getLisItemCode());
            List<Integer> categoryIds = itemCodeToCategoryIds.get(lisItemCode);
            LocalDateTime effectiveTime = resolveLisItemAuthTime(lisItem);
            if (categoryIds == null || effectiveTime == null) continue;

            List<BgaSyncDetailCandidate> detailCandidates = buildLisDetailCandidates(
                latestResultMap.getOrDefault(lisItem.getReportId(), Collections.emptyMap()),
                lisResultCodeToParamCodes,
                patientRecord.getDeptId(),
                lisItem
            );
            if (detailCandidates.isEmpty()) continue;

            for (Integer categoryId : categoryIds) {
                candidates.add(new BgaSyncRecordCandidate(
                    BgaRecordSource.LIS,
                    categoryId,
                    bgaCategoryMap.getOrDefault(categoryId, ""),
                    effectiveTime,
                    lisItemCode,
                    null,
                    effectiveTime,
                    detailCandidates
                ));
            }
        }
        return candidates;
    }

    private List<PatientLisItem> collectPatientLisItems(
        String hisPid, List<String> lisItemCodes, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        List<PatientLisItem> lisItems = new ArrayList<>(
            patientLisItemRepository.findByHisPidAndLisItemCodeInAndAuthTimeBetween(
                hisPid, lisItemCodes, queryStartUtc, queryEndUtc
            )
        );

        for (PatientLisItem item : patientLisItemRepository.findByHisPidAndLisItemCodeInAndAuthTimeIsNull(
            hisPid, lisItemCodes
        )) {
            LocalDateTime authTime = resolveLisItemAuthTime(item);
            if (authTime == null || authTime.isBefore(queryStartUtc) || authTime.isAfter(queryEndUtc)) continue;
            item.setAuthTime(authTime);
            lisItems.add(item);
        }
        return lisItems;
    }

    private Map<String, Map<String, PatientLisResult>> buildLatestLisResultMap(
        Map<String, PatientLisItem> reportIdToItemMap, List<String> reportIds, List<String> externalParamCodes
    ) {
        if (reportIds.isEmpty() || externalParamCodes.isEmpty()) return Collections.emptyMap();

        Map<String, Map<String, PatientLisResult>> latestResultMap = new HashMap<>();
        for (PatientLisResult result : patientLisResultRepository
            .findByReportIdInAndExternalParamCodeInAndIsDeletedFalse(reportIds, externalParamCodes)
        ) {
            PatientLisItem lisItem = reportIdToItemMap.get(result.getReportId());
            if (lisItem == null) continue;

            LocalDateTime authTime = resolveLisResultAuthTime(result, lisItem);
            if (authTime == null) continue;

            result.setAuthTime(authTime);
            latestResultMap.computeIfAbsent(result.getReportId(), key -> new HashMap<>())
                .merge(
                    result.getExternalParamCode(),
                    result,
                    (left, right) -> right.getAuthTime().isAfter(left.getAuthTime()) ? right : left
                );
        }
        return latestResultMap;
    }

    private void upsertSyncedBgaRecords(
        Long pid, String deptId, Map<Integer, String> cateItemMap, List<BgaSyncRecordCandidate> candidates,
        String accountId, String accountName, LocalDateTime nowUtc,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        Map<Integer, BgaRecordSource> managedSourceMap = new LinkedHashMap<>();
        for (Integer bgaCategoryId : bgaCategoryIds) {
            managedSourceMap.put(
                bgaCategoryId,
                StrUtils.isBlank(cateItemMap.get(bgaCategoryId)) ? BgaRecordSource.RAW : BgaRecordSource.LIS
            );
        }

        Map<BgaRecordKey, BgaSyncRecordCandidate> candidateMap = new LinkedHashMap<>();
        for (BgaSyncRecordCandidate candidate : candidates) {
            candidateMap.merge(getRecordKey(candidate.bgaCategoryId(), candidate.effectiveTime()), candidate,
                this::selectPreferredSyncCandidate);
        }

        List<PatientBgaRecord> existingRecords = recordRepository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
            pid, queryStartUtc, queryEndUtc
        );
        Map<BgaRecordKey, PatientBgaRecord> existingRecordMap = existingRecords.stream()
            .collect(Collectors.toMap(
                record -> getRecordKey(record.getBgaCategoryId(), record.getEffectiveTime()),
                record -> record,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Set<BgaRecordKey> syncedKeys = new HashSet<>();

        for (Map.Entry<BgaRecordKey, BgaSyncRecordCandidate> entry : candidateMap.entrySet()) {
            PatientBgaRecord syncedRecord = upsertSyncedBgaRecord(
                existingRecordMap.get(entry.getKey()), pid, deptId, entry.getValue(), accountId, accountName, nowUtc
            );
            if (syncedRecord == null) continue;
            syncPatientBgaRecordDetails(syncedRecord.getId(), entry.getValue().details(), accountId, nowUtc);
            existingRecordMap.put(entry.getKey(), syncedRecord);
            syncedKeys.add(entry.getKey());
        }

        cleanupStaleSyncedRecords(
            recordRepository.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(pid, queryStartUtc, queryEndUtc),
            managedSourceMap, syncedKeys, accountId, accountName, nowUtc
        );
    }

    private PatientBgaRecord upsertSyncedBgaRecord(
        PatientBgaRecord existingRecord, Long pid, String deptId, BgaSyncRecordCandidate candidate,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        BgaRecordSource existingSource = existingRecord == null ? null : classifyRecordSource(existingRecord);
        if (existingSource == BgaRecordSource.MANUAL) {
            log.info(
                "Skip syncing BGA record because a manual record already exists. pid={}, cateId={}, effectiveTime={}",
                pid, candidate.bgaCategoryId(), candidate.effectiveTime()
            );
            return null;
        }

        boolean isNewRecord = existingRecord == null;
        PatientBgaRecord record = isNewRecord ? new PatientBgaRecord() : existingRecord;
        String nextLisItemCode = candidate.source() == BgaRecordSource.LIS ? candidate.lisItemCode() : null;
        Long nextRawRecordId = candidate.source() == BgaRecordSource.RAW ? candidate.rawRecordId() : null;
        boolean shouldResetRecordedInfo = isNewRecord ||
            existingSource != candidate.source() ||
            !Objects.equals(record.getRawRecordId(), nextRawRecordId) ||
            !Objects.equals(normalizeNullableString(record.getLisItemCode()), nextLisItemCode) ||
            record.getRecordedAt() == null;

        record.setPid(pid);
        record.setDeptId(deptId);
        record.setBgaCategoryId(candidate.bgaCategoryId());
        record.setBgaCategoryName(candidate.bgaCategoryName());
        record.setLisItemCode(nextLisItemCode);
        record.setEffectiveTime(candidate.effectiveTime());
        record.setRawRecordId(nextRawRecordId);
        if (shouldResetRecordedInfo) {
            record.setRecordedBy(accountId);
            record.setRecordedByAccountName(accountName);
            record.setRecordedAt(candidate.recordedAt());
            if (!isNewRecord) {
                record.setReviewedBy(null);
                record.setReviewedByAccountName(null);
                record.setReviewedAt(null);
            }
        }
        record.setIsDeleted(false);
        record.setModifiedBy(accountId);
        record.setModifiedByAccountName(accountName);
        record.setModifiedAt(nowUtc);
        return recordRepository.save(record);
    }

    private void syncPatientBgaRecordDetails(
        Long recordId, List<BgaSyncDetailCandidate> detailCandidates, String accountId, LocalDateTime nowUtc
    ) {
        List<PatientBgaRecordDetail> existingDetails = recordDetailRepository.findByRecordIdAndIsDeletedFalse(recordId);
        Map<String, PatientBgaRecordDetail> existingDetailMap = existingDetails.stream()
            .collect(Collectors.toMap(
                PatientBgaRecordDetail::getMonitoringParamCode,
                detail -> detail,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Map<String, BgaSyncDetailCandidate> desiredDetailMap = new LinkedHashMap<>();
        for (BgaSyncDetailCandidate candidate : detailCandidates) {
            desiredDetailMap.put(candidate.monitoringParamCode(), candidate);
        }

        List<PatientBgaRecordDetail> detailsToSave = new ArrayList<>();
        for (BgaSyncDetailCandidate detailCandidate : desiredDetailMap.values()) {
            PatientBgaRecordDetail existingDetail = existingDetailMap.remove(detailCandidate.monitoringParamCode());
            if (existingDetail == null) {
                detailsToSave.add(PatientBgaRecordDetail.builder()
                    .recordId(recordId)
                    .monitoringParamCode(detailCandidate.monitoringParamCode())
                    .paramValue(detailCandidate.paramValue())
                    .paramValueStr(detailCandidate.paramValueStr())
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedAt(nowUtc)
                    .build());
                continue;
            }

            if (Objects.equals(existingDetail.getParamValue(), detailCandidate.paramValue()) &&
                Objects.equals(existingDetail.getParamValueStr(), detailCandidate.paramValueStr())
            ) {
                continue;
            }

            existingDetail.setParamValue(detailCandidate.paramValue());
            existingDetail.setParamValueStr(detailCandidate.paramValueStr());
            existingDetail.setModifiedBy(accountId);
            existingDetail.setModifiedAt(nowUtc);
            detailsToSave.add(existingDetail);
        }

        for (PatientBgaRecordDetail staleDetail : existingDetailMap.values()) {
            staleDetail.setIsDeleted(true);
            staleDetail.setDeletedBy(accountId);
            staleDetail.setDeletedAt(nowUtc);
            detailsToSave.add(staleDetail);
        }

        if (!detailsToSave.isEmpty()) recordDetailRepository.saveAll(detailsToSave);
    }

    private void cleanupStaleSyncedRecords(
        List<PatientBgaRecord> activeRecords,
        Map<Integer, BgaRecordSource> managedSourceMap,
        Set<BgaRecordKey> syncedKeys,
        String accountId, String accountName, LocalDateTime nowUtc
    ) {
        List<PatientBgaRecord> recordsToDelete = activeRecords.stream()
            .filter(record -> {
                BgaRecordSource recordSource = classifyRecordSource(record);
                if (recordSource == BgaRecordSource.MANUAL) return false;
                BgaRecordSource managedSource = managedSourceMap.get(record.getBgaCategoryId());
                if (managedSource == null) return false;
                if (recordSource != managedSource) return true;
                return !syncedKeys.contains(getRecordKey(record.getBgaCategoryId(), record.getEffectiveTime()));
            })
            .toList();
        if (recordsToDelete.isEmpty()) return;

        List<Long> recordIds = recordsToDelete.stream().map(PatientBgaRecord::getId).toList();
        List<PatientBgaRecordDetail> detailsToDelete = recordDetailRepository.findByRecordIdInAndIsDeletedFalse(recordIds);

        for (PatientBgaRecord record : recordsToDelete) {
            record.setIsDeleted(true);
            record.setDeletedBy(accountId);
            record.setDeletedByAccountName(accountName);
            record.setDeletedAt(nowUtc);
        }
        for (PatientBgaRecordDetail detail : detailsToDelete) {
            detail.setIsDeleted(true);
            detail.setDeletedBy(accountId);
            detail.setDeletedAt(nowUtc);
        }

        recordRepository.saveAll(recordsToDelete);
        if (!detailsToDelete.isEmpty()) recordDetailRepository.saveAll(detailsToDelete);
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

    private String buildRawRecordDedupKey(RawBgaRecord rawRecord) {
        return rawRecord.getMrnBednum() + "|" + rawRecord.getBgaCategoryId() + "|" + rawRecord.getEffectiveTime();
    }

    private List<BgaSyncDetailCandidate> buildRawDetailCandidates(
        List<RawBgaRecordDetail> rawDetails, String deptId
    ) {
        List<BgaSyncDetailCandidate> detailCandidates = new ArrayList<>();
        for (RawBgaRecordDetail rawDetail : rawDetails) {
            ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, rawDetail.getMonitoringParamCode());
            if (meta == null) {
                log.error("Missing meta for param: {}", rawDetail.getMonitoringParamCode());
                continue;
            }

            GenericValuePB value = ValueMetaUtils.toGenericValue(rawDetail.getParamValueStr(), meta);
            if (value == null) {
                log.error("Invalid raw BGA value: {}", rawDetail.getParamValueStr());
                continue;
            }

            detailCandidates.add(new BgaSyncDetailCandidate(
                rawDetail.getMonitoringParamCode(),
                ProtoUtils.encodeGenericValue(value),
                rawDetail.getParamValueStr()
            ));
        }
        return detailCandidates;
    }

    private List<BgaSyncDetailCandidate> buildLisDetailCandidates(
        Map<String, PatientLisResult> latestResults,
        Map<String, List<String>> lisResultCodeToParamCodes,
        String deptId,
        PatientLisItem lisItem
    ) {
        Map<String, BgaSyncDetailCandidate> detailCandidateMap = new LinkedHashMap<>();
        for (PatientLisResult lisResult : latestResults.values()) {
            List<String> monitoringParamCodes = lisResultCodeToParamCodes.get(lisResult.getExternalParamCode());
            if (monitoringParamCodes == null || monitoringParamCodes.isEmpty()) continue;

            for (String monitoringParamCode : monitoringParamCodes) {
                ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, monitoringParamCode);
                if (meta == null) {
                    log.error("Missing meta for LIS mapped param: {}", monitoringParamCode);
                    continue;
                }

                GenericValuePB value = ValueMetaUtils.toGenericValue(lisResult.getResultStr(), meta);
                if (value == null) {
                    log.error(
                        "Invalid LIS result value. reportId={}, lisItemCode={}, externalParamCode={}, result={}",
                        lisItem.getReportId(), lisItem.getLisItemCode(),
                        lisResult.getExternalParamCode(), lisResult.getResultStr()
                    );
                    continue;
                }

                detailCandidateMap.put(monitoringParamCode, new BgaSyncDetailCandidate(
                    monitoringParamCode,
                    ProtoUtils.encodeGenericValue(value),
                    lisResult.getResultStr()
                ));
            }
        }
        return new ArrayList<>(detailCandidateMap.values());
    }

    private Map<String, List<String>> getLisResultCodeToMonitoringParamCodes(String deptId) {
        Map<String, List<String>> resultCodeMap = new LinkedHashMap<>();
        for (BgaParam bgaParam : monitoringConfig.getBgaParamList(deptId)) {
            String lisResultCode = normalizeNullableString(bgaParam.getLisResultCode());
            if (StrUtils.isBlank(lisResultCode)) continue;
            resultCodeMap.computeIfAbsent(lisResultCode, key -> new ArrayList<>())
                .add(bgaParam.getMonitoringParamCode());
        }
        return resultCodeMap;
    }

    private LocalDateTime resolveLisItemAuthTime(PatientLisItem lisItem) {
        if (lisItem == null) return null;
        if (lisItem.getAuthTime() != null) return lisItem.getAuthTime();
        return lisItem.getCollectTime();
    }

    private PatientLisItem selectPreferredLisItem(PatientLisItem left, PatientLisItem right) {
        LocalDateTime leftTime = resolveLisItemAuthTime(left);
        LocalDateTime rightTime = resolveLisItemAuthTime(right);
        if (leftTime == null) return right;
        if (rightTime == null) return left;
        return rightTime.isAfter(leftTime) ? right : left;
    }

    private LocalDateTime resolveLisResultAuthTime(PatientLisResult lisResult, PatientLisItem lisItem) {
        if (lisResult == null) return null;
        if (lisResult.getAuthTime() != null) return lisResult.getAuthTime();
        return resolveLisItemAuthTime(lisItem);
    }

    private BgaRecordSource classifyRecordSource(PatientBgaRecord record) {
        if (record == null) return BgaRecordSource.MANUAL;
        if (record.getRawRecordId() != null) return BgaRecordSource.RAW;
        if (!StrUtils.isBlank(record.getLisItemCode())) return BgaRecordSource.LIS;
        return BgaRecordSource.MANUAL;
    }

    private BgaRecordKey getRecordKey(Integer bgaCategoryId, LocalDateTime effectiveTime) {
        return new BgaRecordKey(bgaCategoryId, effectiveTime);
    }

    private BgaSyncRecordCandidate selectPreferredSyncCandidate(
        BgaSyncRecordCandidate left, BgaSyncRecordCandidate right
    ) {
        if (right.details().size() != left.details().size()) {
            return right.details().size() > left.details().size() ? right : left;
        }
        if (right.rawRecordId() != null && left.rawRecordId() != null) {
            return right.rawRecordId() > left.rawRecordId() ? right : left;
        }
        return right;
    }

    private List<BgaCategoryMapping> getOrCreateBgaCategoryMappings(String deptId) {
        Map<Integer, BgaCategoryMapping> activeMappings = bgaCategoryMappingRepository
            .findByDeptIdAndIsDeletedFalseOrderByBgaCategoryId(deptId)
            .stream()
            .filter(mapping -> bgaCategoryIds.contains(mapping.getBgaCategoryId()))
            .collect(Collectors.toMap(
                BgaCategoryMapping::getBgaCategoryId,
                mapping -> mapping,
                this::selectPreferredBgaCategoryMapping,
                LinkedHashMap::new
            ));
        if (activeMappings.size() == bgaCategoryIds.size()) return new ArrayList<>(activeMappings.values());

        departmentRepository.findByDeptIdForUpdate(deptId);
        List<BgaCategoryMapping> existingMappings = bgaCategoryMappingRepository.findByDeptIdOrderByBgaCategoryId(deptId);
        Map<Integer, BgaCategoryMapping> activeMappingMap = existingMappings.stream()
            .filter(mapping -> !Boolean.TRUE.equals(mapping.getIsDeleted()))
            .filter(mapping -> bgaCategoryIds.contains(mapping.getBgaCategoryId()))
            .collect(Collectors.toMap(
                BgaCategoryMapping::getBgaCategoryId,
                mapping -> mapping,
                this::selectPreferredBgaCategoryMapping,
                LinkedHashMap::new
            ));
        Map<Integer, BgaCategoryMapping> deletedMappingMap = existingMappings.stream()
            .filter(mapping -> Boolean.TRUE.equals(mapping.getIsDeleted()))
            .filter(mapping -> bgaCategoryIds.contains(mapping.getBgaCategoryId()))
            .collect(Collectors.toMap(
                BgaCategoryMapping::getBgaCategoryId,
                mapping -> mapping,
                this::selectPreferredBgaCategoryMapping,
                LinkedHashMap::new
            ));

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<BgaCategoryMapping> mappingsToSave = new ArrayList<>();
        for (Integer bgaCategoryId : bgaCategoryIds) {
            if (activeMappingMap.containsKey(bgaCategoryId)) continue;

            BgaCategoryMapping mapping = deletedMappingMap.get(bgaCategoryId);
            if (mapping == null) {
                mapping = BgaCategoryMapping.builder()
                    .deptId(deptId)
                    .bgaCategoryId(bgaCategoryId)
                    .isDeleted(false)
                    .build();
            } else {
                mapping.setIsDeleted(false);
                mapping.setDeletedBy(null);
                mapping.setDeletedByAccountName(null);
                mapping.setDeletedAt(null);
            }

            mapping.setDeptId(deptId);
            mapping.setBgaCategoryId(bgaCategoryId);
            mapping.setModifiedBy("system");
            mapping.setModifiedAt(nowUtc);
            mappingsToSave.add(mapping);
            activeMappingMap.put(bgaCategoryId, mapping);
        }
        if (!mappingsToSave.isEmpty()) bgaCategoryMappingRepository.saveAll(mappingsToSave);
        return bgaCategoryIds.stream()
            .map(activeMappingMap::get)
            .filter(Objects::nonNull)
            .toList();
    }

    private BgaCategoryMapping selectPreferredBgaCategoryMapping(
        BgaCategoryMapping left, BgaCategoryMapping right
    ) {
        if (Boolean.TRUE.equals(left.getIsDeleted()) != Boolean.TRUE.equals(right.getIsDeleted())) {
            return Boolean.TRUE.equals(left.getIsDeleted()) ? right : left;
        }
        if (left.getModifiedAt() == null) return right;
        if (right.getModifiedAt() == null) return left;
        return right.getModifiedAt().isAfter(left.getModifiedAt()) ? right : left;
    }

    private String normalizeNullableString(String value) {
        if (StrUtils.isBlank(value)) return null;
        return value.trim();
    }

    private enum BgaRecordSource {
        RAW,
        LIS,
        MANUAL
    }

    private record BgaRecordKey(Integer bgaCategoryId, LocalDateTime effectiveTime) {
    }

    private record BgaSyncDetailCandidate(
        String monitoringParamCode,
        String paramValue,
        String paramValueStr
    ) {
    }

    private record BgaSyncRecordCandidate(
        BgaRecordSource source,
        Integer bgaCategoryId,
        String bgaCategoryName,
        LocalDateTime effectiveTime,
        String lisItemCode,
        Long rawRecordId,
        LocalDateTime recordedAt,
        List<BgaSyncDetailCandidate> details
    ) {
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgs;

    private final ConfigProtoService protoService;
    private final List<Integer> bgaCategoryIds;
    private final Map<Integer, String> bgaCategoryMap;
    private final UserService userService;
    private final PatientService patientService;
    private final PatientDeviceService patientDeviceService;
    private final MonitoringConfig monitoringConfig;
    private final PatientNursingReportInvalidationService pnrUtils;
    private final RbacDepartmentRepository departmentRepository;

    private final BgaParamRepository bgaParamRepository;
    private final BgaCategoryMappingRepository bgaCategoryMappingRepository;
    private final PatientLisItemRepository patientLisItemRepository;
    private final PatientLisResultRepository patientLisResultRepository;
    private final PatientBgaRecordRepository recordRepository;
    private final PatientBgaRecordDetailRepository recordDetailRepository;
    private final RawBgaRecordRepository rawBgaRecordRepository;
    private final RawBgaRecordDetailRepository rawBgaRecordDetailRepository;
}
