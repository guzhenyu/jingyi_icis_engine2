package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientDeviceService {
    public PatientDeviceService(
        @Autowired ProtoConfig protoConfig,
        @Autowired UserQuery userQuery,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientBedHistoryRepository bedHistoryRepo,
        @Autowired PatientReader patientReader
    ) {
        Config config = protoConfig.getConfig();
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();

        this.userQuery = userQuery;
        this.patientRepo = patientRepo;
        this.bedHistoryRepo = bedHistoryRepo;
        this.patientReader = patientReader;
    }

    @Transactional
    public GenericResp switchBed(SwitchPatientBedReq req) {
        // 提取关键参数
        Long pid = req.getPid();
        String toHisBedNumber = req.getToHisBedNumber();
        LocalDateTime switchTime = TimeUtils.fromIso8601String(req.getSwitchTimeIso8601(), "UTC");
        if (switchTime == null) switchTime = TimeUtils.getNowUtc();

        // 获取病人信息
        PatientRecord patient = patientRepo.findById(pid).orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        if (patient.getAdmissionStatus() != PatientEnums.ADMISSION_STATUS_ADMITTED) {
            log.error("Patient not in ICU: {}", patient);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }

        // 判断是否需要切换床位
        String fromHisBedNumber = patient.getHisBedNumber();
        if (toHisBedNumber.equals(fromHisBedNumber)) {
            log.info("Bed switch not needed: {}", fromHisBedNumber);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        StatusCode statusCode = switchBedImpl(patient, toHisBedNumber, switchTime, false, "his", "his");
        if (statusCode != StatusCode.OK) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    // 如果是普通换床
    // - 增加一条床位历史记录
    //   (bed = fromHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = PatientEnums.SWITCH_TYPE_NORMAL)
    //
    // 如果是病人重返，则
    // - 需要满足 patient.getAdmissionStatus() 为 '已出科'
    // - 增加两条床位历史记录
    //   1. (bed = patient.getHisBedNumber(),
    //       switchTime = patient.getDischargeTime(),
    //       switchType = PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE)
    //   2. (bed = toHisBedNumber,
    //       switchTime = switchTime,
    //       switchType = PatientEnums.SWITCH_TYPE_READMISSION_ADMIT)
    @Transactional
    public StatusCode switchBedImpl(
        PatientRecord patient, String toHisBedNumber, LocalDateTime switchTime,
        boolean readmission, String modifiedBy, String modifiedByName
    ) {
        // 获取床位历史，校验合法性
        PatientReader.BedHistory usageHistory = patientReader.findBedHistory(patient);
        if (usageHistory.entries().isEmpty()) {
            log.error("No bed history found for patient: {}", patient.getId());
            return StatusCode.BED_HISTORY_NOT_FOUND;
        }
        PatientReader.BedHistoryEntry latestBedName = usageHistory.entries().getLast();
        LocalDateTime latestBedStartTime = latestBedName.startUtc();
        LocalDateTime latestBedEndTime = usageHistory.endUtc();
        if (StrUtils.isBlank(latestBedName.hisBedNumber()) ||
            StrUtils.isBlank(latestBedName.deviceBedNumber())
        ) {
            log.error("Invalid latest bed name: {}", latestBedName);
            return StatusCode.INVALID_BED_NAME;
        }
        if (!latestBedName.hisBedNumber().equals(patient.getHisBedNumber())) {
            log.error("Latest bed does not match patient's current bed: {}, expected: {}",
                latestBedName.hisBedNumber(), patient.getHisBedNumber());
            return StatusCode.BED_HISTORY_MISMATCH;
        }
        // 获取目标床位信息，校验合法性
        BedConfigPB toBedConfigPB = patientReader.findBedConfigByHisBedNumber(
            patient.getDeptId(), toHisBedNumber);
        if (toBedConfigPB == null) {
            log.error("BedConfig not found: {}", toHisBedNumber);
            return StatusCode.BED_CONFIG_NOT_EXISTS;
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 换床
        if (!readmission) {  // 普通换床
            // 检查合法性
            if (!patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION) &&
                !patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_ADMITTED)
            ) {
                log.error("Invalid admission status for bed switch: {}, pid {}",
                    patient.getAdmissionStatus(), patient.getId()
                );
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            long toBedPid = toBedConfigPB.getPid();
            if (toBedPid != 0) {
                if (toBedPid == patient.getId() &&
                    toBedConfigPB.getHisBedNumber().equals(patient.getHisBedNumber())
                ) return StatusCode.OK;

                log.error("Bed already occupied: hisBedNumber {}[by {}], pid {}, curBed {}",
                    toHisBedNumber, toBedPid, patient.getId(), patient.getHisBedNumber());
                return StatusCode.BED_ALREADY_OCCUPIED;
            }
            if (!switchTime.isAfter(latestBedStartTime)) {
                log.error("Switch time is earlier than the current bed starting time: {}", switchTime);
                return StatusCode.BED_SWITCH_TIME_OUTDATED;
            }
            if (latestBedEndTime != null) {
                log.error("The bed usage end time, for patients in icu [pid {}], should be open/null", patient.getId());
                return StatusCode.BED_USAGE_END_TIME_NOT_NULL;
            }

            // 换床 * 1
            PatientBedHistory bedHistoryRecord = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(latestBedName.hisBedNumber())
                .deviceBedNumber(latestBedName.deviceBedNumber())
                .displayBedNumber(latestBedName.displayBedNumber())
                .switchTime(switchTime)
                .switchType(PatientEnums.SWITCH_TYPE_NORMAL)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(bedHistoryRecord);
            log.info("Bed switched:patient {}, {} -> {}",
                patient.getIcuName(), latestBedName.hisBedNumber(), toBedConfigPB.getHisBedNumber()
            );
        } else {  // 病人重返
            // 检查合法性
            if (!patient.getAdmissionStatus().equals(PatientEnums.ADMISSION_STATUS_DISCHARGED)) {
                log.error("Invalid admission status for bed switch: {}, pid {}",
                    patient.getAdmissionStatus(), patient.getId()
                );
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            if (!toBedConfigPB.getHisMrn().equals(patient.getHisMrn())) {
                log.error("MRN mismatch for bed switch, expected: {}, actual: {}",
                    patient.getHisMrn(), toBedConfigPB.getHisMrn()
                );
                return StatusCode.BED_SWITCH_READMISSION_MRN_MISMATCH;
            }
            if (latestBedEndTime == null || !switchTime.isAfter(latestBedEndTime)) {
                log.error("Invalid bed end time for readmission: {}, pid {}, switch time {}",
                    latestBedEndTime, patient.getId(), switchTime
                );
                return StatusCode.BED_SWITCH_READMISSION_END_TIME_INVALID;
            }

            // 插入重返记录 * 2
            PatientBedHistory dischargedBedHistory = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(latestBedName.hisBedNumber())
                .deviceBedNumber(latestBedName.deviceBedNumber())
                .displayBedNumber(latestBedName.displayBedNumber())
                .switchTime(latestBedEndTime)
                .switchType(PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(dischargedBedHistory);
            PatientBedHistory readmitBedHistory = PatientBedHistory.builder()
                .patientId(patient.getId())
                .hisBedNumber(toHisBedNumber)
                .deviceBedNumber(toBedConfigPB.getDeviceBedNumber())
                .displayBedNumber(toBedConfigPB.getDisplayBedNumber())
                .switchTime(switchTime)
                .switchType(PatientEnums.SWITCH_TYPE_READMISSION_ADMIT)
                .modifiedBy(modifiedBy)
                .modifiedByAccountName(modifiedByName)
                .modifiedAt(nowUtc)
                .build();
            bedHistoryRepo.save(readmitBedHistory);
        }

        patient.setHisBedNumber(toHisBedNumber);
        if (readmission) {
            patient.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED);
            PatientConfig.clearDischargeFields(patient);
        }
        patientRepo.save(patient);

        return StatusCode.OK;
    }

    private final List<String> statusCodeMsgList;
    private final UserQuery userQuery;
    private final PatientRecordRepository patientRepo;
    private final PatientBedHistoryRepository bedHistoryRepo;
    private final PatientReader patientReader;
}
