package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.LocalDateTime;
import java.util.*;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class PatientDevUtils {
    public static StatusCode unbindDevice(
        PatientDevice patientDevice, LocalDateTime unbindingTime,
        String autoAccountId, String accountName, LocalDateTime modifiedAt
    ) {
        if (patientDevice == null || unbindingTime == null) {
            log.error("PatientDevice is null, debug stack {}", Arrays.toString(Thread.currentThread().getStackTrace()));
            return StatusCode.INTERNAL_EXCEPTION;
        }

        if (!patientDevice.getBindingTime().isBefore(unbindingTime)) {
            log.error("Unbinding time is earlier than the binding time: {}", unbindingTime);
            return StatusCode.UNBINDING_TIME_OUTDATED;
        }

        // 解绑设备
        patientDevice.setUnbindingTime(unbindingTime);
        patientDevice.setModifiedBy(autoAccountId);
        patientDevice.setModifiedByAccountName(accountName);
        patientDevice.setModifiedAt(modifiedAt);
        return StatusCode.OK;
    }
}