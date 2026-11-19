package com.jingyicare.jingyi_icis_engine.service.patients.write;

import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientRecordPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientRecordMapper.DischargeTimes;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Service
public class PatientEditService {
    public PatientEditService(
        LockExecutor lockExecutor, PatientRecordRepository patientRepo, PatientRecordMapper mapper
    ) {
        this.lockExecutor = lockExecutor;
        this.patientRepo = patientRepo;
        this.mapper = mapper;
    }

    public StatusCode update(PatientRecordPB patientInfo) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            PatientRecord patient = patientRepo.findById(patientInfo.getPid()).orElse(null);
            if (patient == null) return StatusCode.PATIENT_NOT_FOUND;

            DischargeTimes times = PatientRecordMapper.toDischargeTimes(patientInfo);
            // 校验通过前不修改受 JPA 管理的患者实体。
            if (!PatientEnums.ADMISSION_STATUS_DISCHARGED.equals(patient.getAdmissionStatus())
                && (times.dischargeTimeUtc() != null
                    || !StrUtils.isBlank(patientInfo.getToDeptName())
                    || times.deathTimeUtc() != null)) {
                return StatusCode.PATIENT_IS_NOT_DISCHARGED;
            }

            mapper.toPatientRecordEntity(patientInfo, times, patient);
            patientRepo.save(patient);
            return StatusCode.OK;
        });
    }

    private final LockExecutor lockExecutor;
    private final PatientRecordRepository patientRepo;
    private final PatientRecordMapper mapper;
}
