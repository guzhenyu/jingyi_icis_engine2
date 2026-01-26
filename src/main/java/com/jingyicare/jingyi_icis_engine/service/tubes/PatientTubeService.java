package com.jingyicare.jingyi_icis_engine.service.tubes;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientTubeService {
    public PatientTubeService(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired PatientService patientService,
        @Autowired UserService userService,
        @Autowired TubeSetting setting,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired PatientNursingReportUtils pnrUtils
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        final TubeEnums tubeEnums = protoService.getConfig().getTube().getEnums();
        this.PATIENT_TUBE_DELETED = tubeEnums.getPatientTubeStatusDeleted().getId();
        this.PATIENT_TUBE_INSERTED = tubeEnums.getPatientTubeStatusInserted().getId();
        this.PATIENT_TUBE_REMOVED = tubeEnums.getPatientTubeStatusRemoved().getId();

        this.protoService = protoService;
        this.shiftUtils = shiftUtils;
        this.patientService = patientService;
        this.userService = userService;
        this.setting = setting;
        this.patientTubeImpl = patientTubeImpl;
        this.pnrUtils = pnrUtils;
    }

    public GetPatientTubeRecordsResp getPatientTubeRecords(String getPatientTubeRecordsReqJson) {
        final GetPatientTubeRecordsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientTubeRecordsReqJson, GetPatientTubeRecordsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientTubeRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前病人科室
        final PatientRecord patientRecord = patientService.getPatientRecord(req.getPid());
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientTubeRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        final ShiftSettingsPB shiftPb = shiftUtils.getShiftByDeptId(deptId);

        // 获取相关属性
        final Boolean isDeleted = (req.getIsDeleted() == 1);
        List<PatientTubeRecord> records = patientTubeImpl.fetchPatientTubeRecords(req.getPid(), isDeleted);
        Map<Long, List<TubeAttributePB>> attributeMap = patientTubeImpl.fetchPatientTubeAttributes(records);

        // 获取相关状态
        List<Long> recordIds = records.stream().map(PatientTubeRecord::getId).collect(Collectors.toList());
        LocalDateTime shiftStart = shiftUtils.getShiftStartTimeUtc(shiftPb, TimeUtils.getNowUtc(), ZONE_ID);
        Set<Long> recordIdsWithStatus = patientTubeImpl.fetchPatientTubeStatuses(
            recordIds, shiftStart, shiftStart.plusDays(1)
        ).stream().map(PatientTubeStatusRecord::getPatientTubeRecordId).collect(Collectors.toSet());

        // 转换记录并填充属性
        List<PatientTubeRecordPB> recordPBList = records.stream()
            .sorted(Comparator.comparing(PatientTubeRecord::getRemovedAt, Comparator.nullsFirst(Comparator.reverseOrder()))
                    .thenComparing(PatientTubeRecord::getInsertedAt, Comparator.reverseOrder()))
            .map(record -> {
                List<TubeAttributePB> attributes = attributeMap.getOrDefault(record.getId(), List.of());

                String removedBy = "";
                if (!StrUtils.isBlank(record.getRemovedBy())) {
                    removedBy = record.getRemovedBy();
                }

                String removeAtIso8601 = "";
                if (record.getRemovedAt() != null) {
                    removeAtIso8601 = TimeUtils.toIso8601String(record.getRemovedAt(), ZONE_ID);
                }

                String removalReason = "";
                if (!StrUtils.isBlank(record.getRemovalReason())) {
                    removalReason = record.getRemovalReason();
                }

                return PatientTubeRecordPB.newBuilder()
                        .setPatientTubeRecordId(record.getId())
                        .setTubeName(record.getTubeName())
                        .setTubeTypeId(record.getTubeTypeId())
                        .setStatus(getStatus(record))
                        .setInsertedBy(record.getInsertedBy())
                        .setInsertedByAccountName(record.getInsertedByAccountName())
                        .setInsertedAtIso8601(TimeUtils.toIso8601String(record.getInsertedAt(), ZONE_ID))
                        .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(record.getPlannedRemovalAt(), ZONE_ID))
                        .setDurationDays(getDurationDays(record))
                        .setIsRetainedOnDischarge(record.getIsRetainedOnDischarge() != null ?
                            (record.getIsRetainedOnDischarge() ? 1 : 0) :
                            0)
                        .setRemovedBy(removedBy)
                        .setRemovedByAccountName(record.getRemovedByAccountName())
                        .setRemovedAtIso8601(removeAtIso8601)
                        .setRemovalReason(removalReason)
                        .setNote(record.getNote() != null ? record.getNote() : "")
                        .setShiftDataFilled(recordIdsWithStatus.contains(record.getId()) ? 1 : 0)
                        .addAllAttributes(attributes)
                        .build();
            }).collect(Collectors.toList());

        return GetPatientTubeRecordsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(recordPBList)
            .build();
    }

    @Transactional
    public NewPatientTubeResp newPatientTube(String newPatientTubeReqJson) {
        NewPatientTubeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(newPatientTubeReqJson, NewPatientTubeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("accountId is empty.");
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 提取关键参数
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        LocalDateTime insertedAt = TimeUtils.fromIso8601String(req.getInsertedAtIso8601(), "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (insertedAt == null || insertedAt.isAfter(nowUtc)) {
            log.warn("Failed to add a tube type, insertedAt invalid {}", req.getInsertedAtIso8601());
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 获取管道名称
        TubeType tubeType = setting.findTubeType(req.getTubeTypeId());
        if (tubeType == null) {
            log.warn("Failed to add a tube type, type not exist, json {}", newPatientTubeReqJson);
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }

        // 根据管道名称，检查病人已经插了同名的管道
        final String tubeName = StrUtils.isBlank(req.getTubeName()) ? tubeType.getName() : req.getTubeName();
        List<PatientTubeRecord> recordsWithName = patientTubeImpl.fetchPatientTubeRecords(req.getPid(), tubeName);
        if (recordsWithName.size() > 0) {
            log.warn("Failed to add a tube type, tube already exist, json {}", newPatientTubeReqJson);
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_EXIST))
                .build();
        }

        // 检查管道属性
        if (req.getAttributesCount() > 0) {
            for (TubeAttributePB attrPb : req.getAttributesList()) {
                TubeTypeAttribute tubeTypeAttr = setting.findTubeTypeAttribute(attrPb.getTubeAttrId());
                if (tubeTypeAttr == null) {
                    log.warn("Failed to add a tube type, attribute not exist, json {}", newPatientTubeReqJson);
                    return NewPatientTubeResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_NOT_EXIST))
                        .build();
                }
                if (!tubeTypeAttr.getTubeTypeId().equals(tubeType.getId())) {
                    log.warn("Failed to add a tube type, attribute not match, json {}", newPatientTubeReqJson);
                    return NewPatientTubeResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_ID_NOT_MATCH))
                        .build();
                }
            }
        }

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, insertedAt, insertedAt, nowUtc);

        log.info("a patient tube is created, accountId {}, json {}", accountId, newPatientTubeReqJson);
        // 调用实现类进行插管记录的创建
        return NewPatientTubeResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(patientTubeImpl.addPatientTube(req, tubeType.getType(), tubeName, accountId, accountName))
            .build();
    }

    public GenericResp removePatientTube(String removePatientTubeReqJson) {
        RemovePatientTubeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(removePatientTubeReqJson, RemovePatientTubeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 调用实现类执行拔管记录删除操作
        patientTubeImpl.removePatientTube(req, accountId);
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GenericResp deletePatientTube(String deletePatientTubeReqJson) {
        DeletePatientTubeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientTubeReqJson, DeletePatientTubeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 调用实现类执行删除操作
        patientTubeImpl.deletePatientTube(req, accountId);
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GenericResp retainTubeOnDischarge(String retainTubeOnDischargeReqJson) {
        RetainTubeOnDischargeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(retainTubeOnDischargeReqJson, RetainTubeOnDischargeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 调用实现类执行带管出科标记操作
        patientTubeImpl.retainTubeOnDischarge(req, accountId);
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public NewPatientTubeResp replacePatientTube(String replacePatientTubeReqJson) {
        ReplacePatientTubeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(replacePatientTubeReqJson, ReplacePatientTubeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 确认旧管道信息
        PatientTubeRecord oldRecord = patientTubeImpl.findPatientTubeRecord(req.getReplacedTubeRecordId());
        if (oldRecord == null) {
            return NewPatientTubeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_NOT_FOUND))
                .build();
        }
        final Integer tubeTypeId = oldRecord.getTubeTypeId();

        // 检查管道属性
        if (req.getAttributesCount() > 0) {
            for (TubeAttributePB attrPb : req.getAttributesList()) {
                TubeTypeAttribute tubeTypeAttr = setting.findTubeTypeAttribute(attrPb.getTubeAttrId());
                if (tubeTypeAttr == null) {
                    log.warn("Failed to replace a tube type, attribute not exist, json {}", replacePatientTubeReqJson);
                    return NewPatientTubeResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_NOT_EXIST))
                        .build();
                }
                if (!tubeTypeAttr.getTubeTypeId().equals(tubeTypeId)) {
                    log.warn("Failed to replace a tube type, attribute not match, json {}", replacePatientTubeReqJson);
                    return NewPatientTubeResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_ID_NOT_MATCH))
                        .build();
                }
            }
        }
        log.info("a patient tube is to be replaced, accountId {}, json {}", accountId, replacePatientTubeReqJson);

        return NewPatientTubeResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(patientTubeImpl.replacePatientTube(req, oldRecord, accountId))
            .build();
    }

    public GenericResp updatePatientTubeAttr(String updatePatientTubeAttrReqJson) {
        UpdatePatientTubeAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientTubeAttrReqJson, UpdatePatientTubeAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 确认管道信息
        PatientTubeRecord record = patientTubeImpl.findPatientTubeRecord(req.getPatientTubeRecordId());
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_NOT_FOUND))
                .build();
        }
        final Integer tubeTypeId = record.getTubeTypeId();

        // 检查管道属性
        if (req.getAttrsCount() > 0) {
            for (TubeAttributePB attrPb : req.getAttrsList()) {
                TubeTypeAttribute tubeTypeAttr = setting.findTubeTypeAttribute(attrPb.getTubeAttrId());
                if (tubeTypeAttr == null) {
                    log.warn("Failed to replace a tube type, attribute not exist, json {}", updatePatientTubeAttrReqJson);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_NOT_EXIST))
                        .build();
                }
                if (!tubeTypeAttr.getTubeTypeId().equals(tubeTypeId)) {
                    log.warn("Failed to replace a tube type, attribute not match, json {}", updatePatientTubeAttrReqJson);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_ID_NOT_MATCH))
                        .build();
                }
            }
        }
        log.info("a patient tube is to be updated, accountId {}, json {}", accountId, updatePatientTubeAttrReqJson);

        // 调用实现类更新属性记录
        String updatedBy = req.getInsertedBy();
        String updatedByAccountName = null;
        if (!StrUtils.isBlank(updatedBy)) {
            updatedByAccountName = userService.getNameByAutoId(updatedBy);
        }
        patientTubeImpl.updatePatientTubeRecord(
            record, updatedBy, updatedByAccountName, req.getInsertedAtIso8601(),
            req.getPlannedRemovalAtIso8601(), req.getRemovedAtIso8601(),
            req.getNote()
        );
        patientTubeImpl.updatePatientTubeAttr(req, record, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetPatientTubeStatusResp getPatientTubeStatus(String getPatientTubeStatusReqJson) {
        GetPatientTubeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientTubeStatusReqJson, GetPatientTubeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientTubeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetPatientTubeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 确认管道信息
        PatientTubeRecord record = patientTubeImpl.findPatientTubeRecord(req.getPatientTubeRecordId());
        if (record == null) {
            return GetPatientTubeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_NOT_FOUND))
                .build();
        }

        Integer numRows = 0;
        Integer numPages = 0;
        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (req.getPageSize() > 0 && req.getPageIndex() > 0) {
            List<LocalDateTime> recordedAtList = patientTubeImpl.fetchPatientTubeStatusRows(
                req.getPatientTubeRecordId(), queryStart, queryEnd);
            numRows = recordedAtList.size();
            numPages = (int) Math.ceil((double) numRows / req.getPageSize());

            final Integer startIndex = (req.getPageIndex() - 1) * req.getPageSize();
            if (startIndex >= numRows) {
                log.error("Page index out of range.");
                return GetPatientTubeStatusResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .setNumRows(numRows)
                    .setNumPages(numPages)
                    .build();
            }
            queryStart = recordedAtList.get(startIndex);
            queryEnd = recordedAtList.get(Math.min(startIndex + req.getPageSize(), numRows) - 1);
        }

        // 调用实现类获取状态记录
        List<TubeTimeStatusValListPB> timeStatus = patientTubeImpl.fetchPatientTubeStatuses(
            req.getPatientTubeRecordId(), queryStart, queryEnd, ZONE_ID);

        // 构造并返回响应
        return GetPatientTubeStatusResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setNumRows(numRows)
            .setNumPages(numPages)
            .addAllTimeStatus(timeStatus)
            .build();
    }

    public GenericResp newPatientTubeStatus(String newPatientTubeStatusReqJson) {
        NewPatientTubeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(newPatientTubeStatusReqJson, NewPatientTubeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 确认管道记录信息
        PatientTubeRecord record = patientTubeImpl.findPatientTubeRecord(req.getPatientTubeRecordId());
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_NOT_FOUND))
                .build();
        }

        // 检查是否已存在相同状态记录
        if (patientTubeImpl.checkExistingStatus(req)) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_STATUS_EXIST))
                .build();
        }

        // 获取病人信息
        final Long pid = record.getPid();
        PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        LocalDateTime recordedAt = TimeUtils.fromIso8601String(req.getTimeStatus().getRecordedAtIso8601(), "UTC");
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (recordedAt == null || recordedAt.isAfter(nowUtc)) {
            log.warn("Failed to add a tube status, recordedAt invalid {}", req.getTimeStatus().getRecordedAtIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 检查状态和新增记录
        patientTubeImpl.newPatientTubeStatus(req, accountId, accountName);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, recordedAt, recordedAt, nowUtc);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GenericResp deletePatientTubeStatus(String deletePatientTubeStatusReqJson) {
        DeletePatientTubeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientTubeStatusReqJson, DeletePatientTubeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 确认管道状态记录信息
        PatientTubeRecord record = patientTubeImpl.findPatientTubeRecord(req.getPatientTubeRecordId());
        if (record == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_TUBE_NOT_FOUND))
                .build();
        }

        // 获取病人信息
        final Long pid = record.getPid();
        PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 执行删除操作
        List<PatientTubeStatusRecord> statusRecords = patientTubeImpl.deletePatientTubeStatus(req, accountId);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        for (PatientTubeStatusRecord statusRecord : statusRecords) {
            pnrUtils.updateLatestDataTime(pid, deptId, statusRecord.getRecordedAt(), statusRecord.getRecordedAt(), nowUtc);
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private int getStatus(PatientTubeRecord record) {
        if (record.getIsDeleted()) {
            return PATIENT_TUBE_DELETED;
        } else if (record.getRemovedAt() == null) {
            return PATIENT_TUBE_INSERTED;
        } else {
            return PATIENT_TUBE_REMOVED;
        }
    }

    private int getDurationDays(PatientTubeRecord record) {
        LocalDateTime insertedAtLocal = TimeUtils.getLocalDateTimeFromUtc(record.getInsertedAt(), ZONE_ID);
        LocalDateTime nowLocal = TimeUtils.getLocalDateTimeFromUtc(TimeUtils.getNowUtc(), ZONE_ID);
        LocalDateTime removedAt = TimeUtils.getLocalDateTimeFromUtc(record.getRemovedAt(), ZONE_ID);
        LocalDateTime durationEnd = (removedAt != null) ? removedAt : nowLocal;

        // 计算插入时间和当前时间的天数差，算头算尾
        long daysBetween = Duration.between(insertedAtLocal.toLocalDate().atStartOfDay(), durationEnd.toLocalDate().atStartOfDay().plusDays(1)).toDays();

        // 直接返回天数差
        return (int) daysBetween;
    }

    private final String ZONE_ID;

    private final Integer PATIENT_TUBE_DELETED;
    private final Integer PATIENT_TUBE_INSERTED;
    private final Integer PATIENT_TUBE_REMOVED;
    private final ConfigProtoService protoService;  // 配置协议服务
    private final ConfigShiftUtils shiftUtils;  // 班次工具类
    private final PatientService patientService;    // 病人服务
    private final UserService userService;  // 用户服务
    private final TubeSetting setting;       // 管道设置
    private final PatientTubeImpl patientTubeImpl;  // 病人管道实现类
    private final PatientNursingReportUtils pnrUtils;  // 病人护理报告工具类
}