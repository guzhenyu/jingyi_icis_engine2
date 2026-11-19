package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.util.HashMap;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;

@Service
@Slf4j
public class PatientReadmissionService {
    public PatientReadmissionService(
        PatientSyncService patientSyncService,
        PatientRecordRepository patientRepo,
        DiagnosisHistoryRepository diagnosisRepo,
        SurgeryHistoryRepository surgeryRepo,
        HisPatientRecordRepository hisPatientRepo
    ) {
        this.patientSyncService = patientSyncService;
        this.patientRepo = patientRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.surgeryRepo = surgeryRepo;
        this.hisPatientRepo = hisPatientRepo;
    }

    // 患者重返：
    // 1. 在 patient_bed_history 中增加两条记录，重返出科 + 重返入科
    // 2. 将原来 dischargedPatient 的入科状态改成"入科"
    // 3. 通过待入科记录找到 HIS 记录，更新已出科记录
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
            newPatient.getHisPatientId(), ADMISSION_STATUS_ADMITTED
        ).orElse(null);
        if (hisPatientRecord == null) {
            log.warn("Cannot find hisPatientRecord for: {}", newPatient.getHisPatientId());
            return StatusCode.READMISSION_PATIENT_NOT_FOUND;
        }

        boolean toUpdate = patientSyncService.updatePatientRecord(
            hisPatientRecord, dischargedPatient, new HashMap<>()
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

    private final PatientSyncService patientSyncService;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final SurgeryHistoryRepository surgeryRepo;
    private final HisPatientRecordRepository hisPatientRepo;
}
