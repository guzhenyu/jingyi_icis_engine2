package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncHisPatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncHisPatientResp;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@SpringBootTest(properties = "patient.sync.enabled=false")
@ActiveProfiles("test")
public class PatientSyncDisabledIntegrationTests {
    @Autowired
    private PatientService patientService;

    @Autowired
    private PatientReadmissionService patientReadmissionService;

    @Autowired
    private ConfigProtoService protoService;

    @Autowired
    private PatientRecordRepository patientRepo;

    @Autowired
    private HisPatientRecordRepository hisPatientRepo;

    @SpyBean
    private PatientSyncService patientSyncService;

    @Test
    public void testManualSyncReturnsDisabledStatusAndDoesNotSynchronize() {
        SyncHisPatientReq req = SyncHisPatientReq.newBuilder()
            .setForceSync(true)
            .build();

        SyncHisPatientResp resp = patientService.syncHisPatient(ProtoUtils.protoToJson(req));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.PATIENT_SYNC_DISABLED.getNumber());
        assertThat(resp.getRt().getMsg()).isEqualTo("患者同步已禁用");
        assertThat(resp.getAdmittedHisPatientCount()).isZero();
        verify(patientSyncService, never()).syncPatientRecords(anyBoolean());
        verify(patientSyncService, never()).getInIcuHisPatientRecords();
    }

    @Test
    public void testReadmissionStillWorksWhenSyncIsDisabled() {
        int pendingAdmission = admissionStatus(
            protoService.getConfig().getPatient().getPendingAdmissionName()
        );
        int inIcu = admissionStatus(protoService.getConfig().getPatient().getInIcuName());
        int discharged = admissionStatus(
            protoService.getConfig().getPatient().getDischargedName()
        );
        long dischargedSeed = 29001L;
        long newPatientSeed = 29002L;
        String mrn = "sync-disabled-readmission-mrn";
        String hisPatientId = "sync-disabled-readmission-his-pid";
        String bedNumber = "sync-disabled-readmission-bed";
        String deptId = "sync-disabled-readmission-dept";

        PatientRecord dischargedPatient = PatientTestUtils.newPatientRecord(
            dischargedSeed, discharged, deptId
        );
        dischargedPatient.setHisMrn(mrn);
        dischargedPatient.setHisPatientId(hisPatientId);
        dischargedPatient.setHisBedNumber(bedNumber);
        dischargedPatient = patientRepo.save(dischargedPatient);

        PatientRecord newPatient = PatientTestUtils.newPatientRecord(
            newPatientSeed, pendingAdmission, deptId
        );
        newPatient.setHisMrn(mrn);
        newPatient.setHisPatientId(hisPatientId);
        newPatient.setHisBedNumber(bedNumber);
        newPatient = patientRepo.save(newPatient);

        HisPatientRecord hisPatient = PatientTestUtils.newHisPatientRecord(
            newPatientSeed, inIcu, deptId
        );
        hisPatient.setMrn(mrn);
        hisPatient.setPid(hisPatientId);
        hisPatient.setBedNumber(bedNumber);
        hisPatientRepo.save(hisPatient);

        assertThat(patientSyncService.isSyncEnabled()).isFalse();
        assertThat(
            patientReadmissionService.readmitPatient(dischargedPatient, newPatient)
        ).isEqualTo(StatusCode.OK);
        assertThat(patientRepo.findById(dischargedPatient.getId())).isPresent();
        assertThat(patientRepo.findById(newPatient.getId())).isEmpty();
    }

    private int admissionStatus(String name) {
        return protoService.getConfig().getPatient().getEnumsV2().getAdmissionStatusList()
            .stream()
            .filter(status -> status.getName().equals(name))
            .findFirst()
            .orElseThrow()
            .getId();
    }
}
