package com.jingyicare.jingyi_icis_engine.service.patients;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientSyncUtils.PatientSyncInfo;

@Service
@Slf4j
public class PatientReadmissionService {
    public PatientReadmissionService(
        ConfigProtoService protoService,
        PatientSyncUtils patientSyncUtils,
        PatientRecordRepository patientRepo,
        DiagnosisHistoryRepository diagnosisRepo,
        SurgeryHistoryRepository surgeryRepo,
        HisPatientRecordRepository hisPatientRepo
    ) {
        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.IN_ICU_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();

        this.patientSyncUtils = patientSyncUtils;
        this.patientRepo = patientRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.surgeryRepo = surgeryRepo;
        this.hisPatientRepo = hisPatientRepo;
    }

    // 患者重返：
    // 1. 在 patient_bed_history 中增加两条记录，重返出科 + 重返入科
    // 2. 将原来 dischargedPatient 的入科状态改成"入科"
    // 3. 通过newPatient(由刷新服务产生)找到对应的HisPatientRecord，更新对应的 dischargedPatient
    // 4. 将更新后的 dischargedPatient 保存到数据库，删除 newPatient
    @Transactional
    public StatusCode readmitPatient(PatientRecord dischargedPatient, PatientRecord newPatient) {
        if (dischargedPatient == null || newPatient == null ||
            !dischargedPatient.getHisMrn().equals(newPatient.getHisMrn())
        ) {
            log.warn("Cannot merge patients: {} and {}", dischargedPatient, newPatient);
            return StatusCode.READMISSION_PATIENT_INCONSISTENT;
        }
        HisPatientRecord hisPatientRecord = hisPatientRepo.findByPidAndAdmissionStatus(
            newPatient.getHisPatientId(), IN_ICU_VAL
        ).orElse(null);
        if (hisPatientRecord == null) {
            log.warn("Cannot find hisPatientRecord for: {}", newPatient.getHisPatientId());
            return StatusCode.READMISSION_PATIENT_NOT_FOUND;
        }

        Map<String, PatientSyncInfo> syncInfoMap = new HashMap<>();
        boolean toUpdate = patientSyncUtils.updatePatientRecord(
            hisPatientRecord, dischargedPatient, syncInfoMap
        );
        if (toUpdate) {
            patientRepo.save(dischargedPatient);
        }
        patientRepo.delete(newPatient);

        List<SurgeryHistory> surgeries = surgeryRepo.findByPatientId(newPatient.getId());
        for (SurgeryHistory surgery : surgeries) {
            surgery.setPatientId(dischargedPatient.getId());
        }
        if (!surgeries.isEmpty()) {
            surgeryRepo.saveAll(surgeries);
        }

        List<DiagnosisHistory> diagnoses = diagnosisRepo.findByPatientIdAndIsDeletedFalse(newPatient.getId());
        for (DiagnosisHistory diagnosis : diagnoses) {
            diagnosis.setPatientId(dischargedPatient.getId());
        }
        if (!diagnoses.isEmpty()) {
            diagnosisRepo.saveAll(diagnoses);
        }

        return StatusCode.OK;
    }

    private final Integer IN_ICU_VAL;

    private final PatientSyncUtils patientSyncUtils;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final SurgeryHistoryRepository surgeryRepo;
    private final HisPatientRecordRepository hisPatientRepo;
}
