package com.jingyicare.jingyi_icis_engine.service.patients.write;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DischargePatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceBindingService;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class PatientDischargeService {
    public PatientDischargeService(
        LockExecutor lockExecutor, PatientRecordRepository patientRepo,
        DeviceBindingService deviceBindingService
    ) {
        this.lockExecutor = lockExecutor;
        this.patientRepo = patientRepo;
        this.deviceBindingService = deviceBindingService;
    }

    public StatusCode discharge(DischargePatientReq req, CurrentAccount account) {
        return lockExecutor.execute(
            List.of(LockKeys.patientRecords(), LockKeys.patientDeviceBindings(req.getId())),
            () -> {
                PatientRecord patient = patientRepo.findById(req.getId()).orElse(null);
                if (patient == null) return StatusCode.PATIENT_NOT_FOUND;
                if (!Objects.equals(patient.getAdmissionStatus(), ADMISSION_STATUS_PRE_DISCHARGE)) {
                    log.error("患者出科状态无效，患者={}，状态={}",
                        patient.getId(), patient.getAdmissionStatus());
                    return StatusCode.INTERNAL_EXCEPTION;
                }

                LocalDateTime nowUtc = TimeUtils.getNowUtc();
                LocalDateTime dischargeTime = TimeUtils.fromIso8601String(
                    req.getDischargeTime(), "UTC");
                if (dischargeTime == null) return StatusCode.INVALID_TIME_FORMAT;
                if (dischargeTime.isAfter(nowUtc)) return StatusCode.DISCHARGE_TIME_IS_AFTER_NOW;

                LocalDateTime deathTime = TimeUtils.fromIso8601String(req.getDeathTime(), "UTC");
                LocalDateTime exitTime = TimeUtils.fromIso8601String(
                    req.getHisDischargeTimeIso8601(), "UTC");
                int dischargeType = req.getDischargeType();
                boolean transfer = dischargeType == DISCHARGE_TYPE_TRANSFER;
                boolean death = dischargeType == DISCHARGE_TYPE_DEAD;
                if (transfer) {
                    if (StrUtils.isBlank(req.getToDeptName())) {
                        return StatusCode.DISCHARGE_TYPE_TRANSFER_NEEDS_TARGET_DEPT_NAME;
                    }
                } else if (death) {
                    if (deathTime == null) {
                        return StatusCode.DISCHARGE_TYPE_DEATH_NEEDS_DEATH_TIME;
                    }
                } else if (dischargeType == DISCHARGE_TYPE_EXIT_HOSPITAL) {
                    if (exitTime == null) {
                        return StatusCode.DISCHARGE_TYPE_EXIT_HOSPITAL_NEEDS_EXIT_TIME;
                    }
                } else {
                    return StatusCode.INVALID_DISCHARGE_TYPE;
                }

                // 批量解绑先校验全部绑定，再修改数据；解绑与出科共用当前事务。
                StatusCode unbindingStatus = deviceBindingService
                    .unbindAllForDischargeInTransaction(
                        patient.getId(), dischargeTime, account, nowUtc);
                if (unbindingStatus != StatusCode.OK) return unbindingStatus;

                patient.setDischargeTime(dischargeTime);
                patient.setDischargeType(dischargeType);
                if (transfer) {
                    patient.setToDeptName(req.getToDeptName());
                } else if (death) {
                    patient.setDeathTime(deathTime);
                } else {
                    patient.setHisDischargeTime(exitTime);
                }
                patient.setAdmissionStatus(ADMISSION_STATUS_DISCHARGED);
                patientRepo.save(patient);
                return StatusCode.OK;
            });
    }

    private final LockExecutor lockExecutor;
    private final PatientRecordRepository patientRepo;
    private final DeviceBindingService deviceBindingService;
}
