package com.jingyicare.jingyi_icis_engine.service.patients.write;

import java.time.LocalDateTime;
import java.util.Objects;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
class PatientBedAssignment {
    PatientBedAssignment(PatientReader patientReader, PatientBedHistoryRepository bedHistoryRepo) {
        this.patientReader = patientReader;
        this.bedHistoryRepo = bedHistoryRepo;
    }

    StatusCode moveInTransaction(
        String toHisBedNumber, LocalDateTime switchTime, String modifiedBy, String modifiedByName,
        /* OUT */ PatientRecord patient
    ) {
        return assignBed(toHisBedNumber, switchTime, false, modifiedBy, modifiedByName, patient);
    }

    StatusCode recordReadmissionInTransaction(
        String toHisBedNumber, LocalDateTime switchTime, CurrentAccount account,
        /* OUT */ PatientRecord previous
    ) {
        return assignBed(
            toHisBedNumber, switchTime, true, account.autoAcctId(), account.acctName(), previous);
    }

    // 调用方持有患者写锁，负责患者状态变更及提交。
    // 所有校验通过后才写床史。
    private StatusCode assignBed(
        String toHisBedNumber, LocalDateTime switchTime, boolean readmission,
        String modifiedBy, String modifiedByName, /* OUT */ PatientRecord patient
    ) {
        PatientReader.BedHistory usage = patientReader.findBedHistory(patient);
        if (usage.entries().isEmpty()) {
            log.warn("患者 {} 无床位历史", patient.getId());
            return StatusCode.BED_HISTORY_NOT_FOUND;
        }
        PatientReader.BedHistoryEntry previousBed = usage.entries().getLast();
        if (StrUtils.isBlank(previousBed.hisBedNumber())
            || StrUtils.isBlank(previousBed.deviceBedNumber())) {
            log.warn("患者 {} 的最近床位信息无效", patient.getId());
            return StatusCode.INVALID_BED_NAME;
        }
        if (!previousBed.hisBedNumber().equals(patient.getHisBedNumber())) {
            log.warn("患者 {} 的最近床位与当前床位不一致", patient.getId());
            return StatusCode.BED_HISTORY_MISMATCH;
        }
        BedConfigPB toBed = patientReader.findBedConfigByHisBedNumber(
            patient.getDeptId(), toHisBedNumber);
        if (toBed == null) {
            log.warn("找不到患者 {} 的目标床位 {}", patient.getId(), toHisBedNumber);
            return StatusCode.BED_CONFIG_NOT_EXISTS;
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        Integer admissionStatus = patient.getAdmissionStatus();

        if (readmission) {
            if (!Objects.equals(admissionStatus, PatientEnums.ADMISSION_STATUS_DISCHARGED)) {
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            if (!toBed.getHisMrn().equals(patient.getHisMrn())) {
                return StatusCode.BED_SWITCH_READMISSION_MRN_MISMATCH;
            }
            if (usage.endUtc() == null || switchTime == null
                || !switchTime.isAfter(usage.endUtc())) {
                return StatusCode.BED_SWITCH_READMISSION_END_TIME_INVALID;
            }

            // 先记录原床的出科时刻，再记录重返床位的入科时刻。
            bedHistoryRepo.save(newHistory(
                patient.getId(), previousBed.hisBedNumber(), previousBed.deviceBedNumber(),
                previousBed.displayBedNumber(), usage.endUtc(),
                PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE,
                modifiedBy, modifiedByName, nowUtc));
            bedHistoryRepo.save(newHistory(
                patient.getId(), toHisBedNumber, toBed.getDeviceBedNumber(),
                toBed.getDisplayBedNumber(), switchTime,
                PatientEnums.SWITCH_TYPE_READMISSION_ADMIT, modifiedBy, modifiedByName, nowUtc));
        } else {
            if (!Objects.equals(admissionStatus, PatientEnums.ADMISSION_STATUS_PRE_ADMISSION)
                && !Objects.equals(admissionStatus, PatientEnums.ADMISSION_STATUS_ADMITTED)) {
                return StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH;
            }
            if (toBed.getPid() != 0) {
                if (Objects.equals(toBed.getPid(), patient.getId())
                    && toBed.getHisBedNumber().equals(patient.getHisBedNumber())) {
                    return StatusCode.OK;
                }
                return StatusCode.BED_ALREADY_OCCUPIED;
            }
            if (switchTime == null || !switchTime.isAfter(previousBed.startUtc())) {
                return StatusCode.BED_SWITCH_TIME_OUTDATED;
            }
            if (usage.endUtc() != null) return StatusCode.BED_USAGE_END_TIME_NOT_NULL;

            bedHistoryRepo.save(newHistory(
                patient.getId(), previousBed.hisBedNumber(), previousBed.deviceBedNumber(),
                previousBed.displayBedNumber(), switchTime, PatientEnums.SWITCH_TYPE_NORMAL,
                modifiedBy, modifiedByName, nowUtc));
        }

        patient.setHisBedNumber(toHisBedNumber);
        return StatusCode.OK;
    }

    private static PatientBedHistory newHistory(
        long pid, String hisBedNumber, String deviceBedNumber, String displayBedNumber,
        LocalDateTime switchTime, Integer switchType, String modifiedBy, String modifiedByName,
        LocalDateTime nowUtc
    ) {
        return PatientBedHistory.builder()
            .patientId(pid)
            .hisBedNumber(hisBedNumber)
            .deviceBedNumber(deviceBedNumber)
            .displayBedNumber(displayBedNumber)
            .switchTime(switchTime)
            .switchType(switchType)
            .modifiedBy(modifiedBy)
            .modifiedByAccountName(modifiedByName)
            .modifiedAt(nowUtc)
            .build();
    }

    private final PatientReader patientReader;
    private final PatientBedHistoryRepository bedHistoryRepo;
}
