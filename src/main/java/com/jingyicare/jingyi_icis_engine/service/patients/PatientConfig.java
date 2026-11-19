package com.jingyicare.jingyi_icis_engine.service.patients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientConfig {
    public PatientConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService
    ) {
        this.context = context;
        this.protoService = protoService;

        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.ADMISSION_TYPE_SURGERY_VAL = enumsV2.getAdmissionTypeList().stream()
            .filter(e -> e.getName().equals("手术"))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGE_TYPE_DEAD_VAL = enumsV2.getDischargeTypeList().stream()
            .filter(e -> e.getName().equals("死亡"))
            .findFirst()
            .get()
            .getId();
    }

    public Integer getAdmissionTypeSurgeryId() {
        return ADMISSION_TYPE_SURGERY_VAL;
    }

    public Integer getDischargeTypeDeadId() {
        return DISCHARGE_TYPE_DEAD_VAL;
    }

    public static void clearDischargeFields(PatientRecord patient) {
        patient.setDischargeType(null);
        patient.setDeathTime(null);
        patient.setHisDischargeTime(null);
        patient.setDischargeDiagnosis(null);
        patient.setDischargeDiagnosisCode(null);
        patient.setToDeptName(null);
        patient.setToDeptId(null);
        patient.setDischargeTime(null);
        patient.setDischargeEditTime(null);
        patient.setDischargeAccountId(null);
    }

    @Transactional
    public void checkIntegrity() {
        if (!checkDeviceCascade()) {
            log.error("Patient config integrity check failed. Exiting...");
            LogUtils.flushAndQuit(context);
        }
    }

    public String getAdmissionTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient()
                .getEnumsV2().getAdmissionTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    public String getDischargeTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient()
                .getEnumsV2().getDischargeTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    private boolean checkDeviceCascade() {
        DeviceCascadePB cascade = protoService.getConfig().getDevice().getDeviceCascade();
        for (DeviceTypeEntryPB entry : cascade.getEntryList()) {
            if (!DeviceEnums.isValidDeviceTypeId(entry.getDeviceTypeId())) {
                log.error("Unknown device_cascade device_type_id {} for '{}'",
                    entry.getDeviceTypeId(), entry.getDeviceTypeName());
                return false;
            }
        }
        return true;
    }

    private final Integer ADMISSION_TYPE_SURGERY_VAL;
    private final Integer DISCHARGE_TYPE_DEAD_VAL;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
}
