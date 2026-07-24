package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.Patient;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

public class PatientReadmissionServiceTests {
    @Test
    public void testReadmitPatientStillMergesPatientAndHistoriesIndependentlyOfScheduledSync() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        Config config = Config.newBuilder()
            .setPatient(Patient.newBuilder()
                .setInIcuName("在科")
                .setEnumsV2(PatientEnumsV2.newBuilder()
                    .addAdmissionStatus(EnumValue.newBuilder().setId(1).setName("在科"))))
            .build();
        when(protoService.getConfig()).thenReturn(config);

        PatientSyncUtils patientSyncUtils = mock(PatientSyncUtils.class);
        PatientRecordRepository patientRepo = mock(PatientRecordRepository.class);
        DiagnosisHistoryRepository diagnosisRepo = mock(DiagnosisHistoryRepository.class);
        SurgeryHistoryRepository surgeryRepo = mock(SurgeryHistoryRepository.class);
        HisPatientRecordRepository hisPatientRepo = mock(HisPatientRecordRepository.class);
        PatientReadmissionService service = new PatientReadmissionService(
            protoService,
            patientSyncUtils,
            patientRepo,
            diagnosisRepo,
            surgeryRepo,
            hisPatientRepo
        );

        PatientRecord dischargedPatient = new PatientRecord();
        dischargedPatient.setId(1001L);
        dischargedPatient.setHisMrn("readmission-mrn");

        PatientRecord newPatient = new PatientRecord();
        newPatient.setId(1002L);
        newPatient.setHisMrn("readmission-mrn");
        newPatient.setHisPatientId("readmission-his-pid");

        HisPatientRecord hisPatientRecord = new HisPatientRecord();
        when(hisPatientRepo.findByPidAndAdmissionStatus("readmission-his-pid", 1))
            .thenReturn(Optional.of(hisPatientRecord));
        when(patientSyncUtils.updatePatientRecord(
            same(hisPatientRecord), same(dischargedPatient), anyMap()
        )).thenReturn(true);

        List<SurgeryHistory> surgeries = new ArrayList<>(List.of(
            SurgeryHistory.builder().patientId(newPatient.getId()).name("surgery").build()
        ));
        when(surgeryRepo.findByPatientId(newPatient.getId())).thenReturn(surgeries);

        List<DiagnosisHistory> diagnoses = new ArrayList<>(List.of(
            DiagnosisHistory.builder().patientId(newPatient.getId()).diagnosis("diagnosis").build()
        ));
        when(diagnosisRepo.findByPatientIdAndIsDeletedFalse(newPatient.getId())).thenReturn(diagnoses);

        StatusCode statusCode = service.readmitPatient(dischargedPatient, newPatient);

        assertThat(statusCode).isEqualTo(StatusCode.OK);
        assertThat(surgeries).allMatch(surgery -> surgery.getPatientId().equals(dischargedPatient.getId()));
        assertThat(diagnoses).allMatch(diagnosis -> diagnosis.getPatientId().equals(dischargedPatient.getId()));
        verify(patientSyncUtils).updatePatientRecord(
            same(hisPatientRecord), same(dischargedPatient), anyMap()
        );
        verify(patientRepo).save(dischargedPatient);
        verify(patientRepo).delete(newPatient);
        verify(surgeryRepo).saveAll(eq(surgeries));
        verify(diagnosisRepo).saveAll(eq(diagnoses));
    }
}
