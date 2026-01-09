package com.jingyicare.jingyi_icis_engine.service.patientshifts;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientShiftService {
    public PatientShiftService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired PatientShiftRecordRepository psrRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        this.protoService = protoService;
        this.userService = userService;
        this.patientService = patientService;
        this.psrRepo = psrRepo;
    }

    @Transactional
    public GetPatientShiftRecordsResp getPatientShiftRecords(String getPatientShiftRecordsReqJson) {
        final GetPatientShiftRecordsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientShiftRecordsReqJson, GetPatientShiftRecordsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPatientShiftRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取病人信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return GetPatientShiftRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (queryStartUtc == null) queryStartUtc = TimeUtils.getMin();
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryEndUtc == null) queryEndUtc = TimeUtils.getMax();

        // 查找相关记录
        List<PatientShiftRecordPB> psRecords = psrRepo
            .findByPidAndShiftStartBetweenAndIsDeletedFalse(pid, queryStartUtc, queryEndUtc)
            .stream()
            .sorted(Comparator.comparing(PatientShiftRecord::getShiftStart).reversed())
            .map(psr -> PatientShiftRecordPB.newBuilder()
                .setId(psr.getId())
                .setContent(psr.getContent())
                .setShiftNurseId(psr.getShiftNurseId())
                .setShiftNurseName(psr.getShiftNurseName())
                .setShiftName(psr.getShiftName())
                .setShiftStartIso8601(TimeUtils.toIso8601String(psr.getShiftStart(), ZONE_ID))
                .setShiftEndIso8601(TimeUtils.toIso8601String(psr.getShiftEnd(), ZONE_ID))
                .build()
            )
            .toList();

        return GetPatientShiftRecordsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(psRecords)
            .build();
    }

    @Transactional
    public PatientShiftRecordExistsResp patientShiftRecordExists(String patientShiftRecordExistsReqJson) {
        final PatientShiftRecordExistsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(patientShiftRecordExistsReqJson, PatientShiftRecordExistsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return PatientShiftRecordExistsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取病人信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return PatientShiftRecordExistsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 查找记录
        final String shiftName = req.getShiftName();
        final LocalDateTime shiftStartUtc = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        PatientShiftRecord record = psrRepo
            .findByPidAndShiftNameAndShiftStartAndIsDeletedFalse(pid, shiftName, shiftStartUtc)
            .orElse(null);
        final Boolean exists = record != null;

        return PatientShiftRecordExistsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setExists(exists)
            .setPatientShiftRecordId(exists ? record.getId() : -1)
            .setShiftNurseName(exists ? record.getShiftNurseName() : "")
            .build();
    }

    @Transactional
    public AddPatientShiftRecordResp addPatientShiftRecord(String addPatientShiftRecordReqJson) {
        final AddPatientShiftRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientShiftRecordReqJson, AddPatientShiftRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddPatientShiftRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddPatientShiftRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        final String accountName = accountWithAutoId.getSecond();

        // 获取病人信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return AddPatientShiftRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 检查班次时间合法性
        final LocalDateTime shiftStartUtc = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        final LocalDateTime shiftEndUtc = TimeUtils.fromIso8601String(req.getShiftEndIso8601(), "UTC");
        StatusCode statusCode = patientService.validateTimeRange(patientRecord, shiftStartUtc, shiftEndUtc);
        if (statusCode != StatusCode.OK) {
            return AddPatientShiftRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // 检查是否有重复记录
        final String shiftName = req.getShiftName();
        if (psrRepo.findByPidAndShiftNameAndShiftStartAndIsDeletedFalse(pid, shiftName, shiftStartUtc).isPresent()) {
            log.error("PatientShiftRecord already exists: {} - {} - {}", pid, shiftName, shiftStartUtc);
            return AddPatientShiftRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SHIFT_RECORD_EXISTS))
                .build();
        }

        // 新增记录，写日志
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        PatientShiftRecord psRecord = PatientShiftRecord.builder()
            .pid(pid)
            .content(req.getContent())
            .shiftNurseId(accountId)
            .shiftNurseName(accountName)
            .shiftName(shiftName)
            .shiftStart(shiftStartUtc)
            .shiftEnd(shiftEndUtc)
            .modifiedAt(nowUtc)
            .isDeleted(false)
            .build();
        psRecord = psrRepo.save(psRecord);
        log.info("PatientShiftRecord added: {} - {} - {}, by {}", pid, shiftName, shiftStartUtc, accountId);

        return AddPatientShiftRecordResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(psRecord.getId())
            .build();
    }

    @Transactional
    public GenericResp updatePatientShiftRecord(String updatePatientShiftRecordReqJson) {
        final AddPatientShiftRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientShiftRecordReqJson, AddPatientShiftRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();
        final String accountName = accountWithAutoId.getSecond();

        // 获取病人信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 检查班次时间合法性
        final LocalDateTime shiftStartUtc = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        final LocalDateTime shiftEndUtc = TimeUtils.fromIso8601String(req.getShiftEndIso8601(), "UTC");
        StatusCode statusCode = patientService.validateTimeRange(patientRecord, shiftStartUtc, shiftEndUtc);
        if (statusCode != StatusCode.OK) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        final String shiftName = req.getShiftName();

        // 检查是否有记录
        PatientShiftRecord psRecord = psrRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (psRecord == null) {
            log.error("PatientShiftRecord not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SHIFT_RECORD_NOT_FOUND))
                .build();
        }

        // 更新记录，写日志
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        psRecord.setContent(req.getContent());
        psRecord.setShiftNurseId(accountId);
        psRecord.setShiftNurseName(accountName);
        // pid和班次等信息不能变
        // psRecord.setShiftName(shiftName);
        // psRecord.setShiftStart(shiftStartUtc);
        // psRecord.setShiftEnd(shiftEndUtc);
        psRecord.setModifiedAt(nowUtc);
        psrRepo.save(psRecord);
        log.info("PatientShiftRecord updated: {} - {} - {}, by {}", pid, shiftName, shiftStartUtc, accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deletePatientShiftRecord(String deletePatientShiftRecordReqJson) {
        final DeletePatientShiftRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientShiftRecordReqJson, DeletePatientShiftRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查是否有记录
        PatientShiftRecord psRecord = psrRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (psRecord == null) {
            log.error("PatientShiftRecord not exists: {}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SHIFT_RECORD_NOT_FOUND))
                .build();
        }

        // 删除记录，写日志
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        psRecord.setIsDeleted(true);
        psRecord.setDeletedAt(nowUtc);
        psRecord.setDeletedBy(accountId);
        psrRepo.save(psRecord);
        log.info("PatientShiftRecord deleted: {} , by {}", psRecord.getId(), accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private final String ZONE_ID;
    private final ConfigProtoService protoService;
    private final UserService userService;
    private final PatientService patientService;
    private final PatientShiftRecordRepository psrRepo;
}