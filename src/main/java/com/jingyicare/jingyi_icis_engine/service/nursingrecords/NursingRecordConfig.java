package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
public class NursingRecordConfig {
    public NursingRecordConfig(
        @Autowired NursingOrderTemplateService templateService,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired DeptSystemSettingsRepository deptSettingRepo
    ) {
        this.templateService = templateService;
        this.recordRepo = recordRepo;
        this.patientRepo = patientRepo;
        this.deptSettingRepo = deptSettingRepo;
    }

    public void initialize() {
        templateService.initDeptTemplateGroups();
    }

    @Transactional
    public void checkIntegrity() {
    }

    public void refresh() {
    }

    public NursingRecordSettingsPB getNursingRecordSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);

        NursingRecordSettingsPB settingsPb = null;
        if (entity != null) {
            settingsPb = ProtoUtils.decodeNursingRecordSettings(entity.getSettingsPb());
        }
        if (settingsPb == null) settingsPb = NursingRecordSettingsPB.newBuilder().build();

        return settingsPb;
    }

    public boolean getEnableUpdatingCreatedBy(Long nursingRecordId) {
        // 获取病人id
        NursingRecord record = recordRepo.findByIdAndIsDeletedFalse(nursingRecordId).orElse(null);
        if (record == null) return false;
        Long pid = record.getPatientId();

        // 获取病人部门
        PatientRecord patientRec = patientRepo.findById(pid).orElse(null);
        if (patientRec == null) return false;
        String deptId = patientRec.getDeptId();

        NursingRecordSettingsPB settingsPb = getNursingRecordSettings(deptId);
        return settingsPb.getEnableUpdatingCreatedBy();
    }

    public void setNursingRecordSettings(
        String deptId, NursingRecordSettingsPB settingsPb, String modifiedBy
    ) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeNursingRecordSettings(settingsPb);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) {
            entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        } else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingRepo.save(entity);
    }

    private final NursingOrderTemplateService templateService;
    private final NursingRecordRepository recordRepo;
    private final PatientRecordRepository patientRepo;
    private final DeptSystemSettingsRepository deptSettingRepo;
}
