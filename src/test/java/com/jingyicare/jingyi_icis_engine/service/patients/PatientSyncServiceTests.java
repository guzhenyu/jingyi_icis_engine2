package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncHisPatientResp;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@SpringBootTest(properties = "jingyi.test.postgresql.database-name=patient_sync_test")
class PatientSyncServiceTests extends TestsBase {
    @AfterEach
    void clearPatientData() {
        bedHistoryRepo.deleteAllInBatch();
        diagnosisRepo.deleteAllInBatch();
        surgeryRepo.deleteAllInBatch();
        patientRepo.deleteAllInBatch();
        hisPatientRepo.deleteAllInBatch();
    }

    @Test
    void testSwitchBed() {
        String deptId = "10038";
        setupDepartment(deptId);
        setupBed(deptId, "hisBedNumber2001", "devBedNumber2001", "dispBedNumber2001");
        setupBed(deptId, "hisBedNumber2099", "devBedNumber2099", "dispBedNumber2099");
        LocalDateTime admissionTime = LocalDateTime.of(2025, 7, 30, 16, 0);
        PatientRecord patient = PatientTestUtils.newPatientRecord(
            2001L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        patient.setAdmissionTime(admissionTime);
        patient.setDischargeTime(null);
        patient.setFromDeptId(null);
        patient.setFromDeptName(null);
        Long patientId = patientRepo.save(patient).getId();

        HisPatientRecord hisPatient = PatientTestUtils.newHisPatientRecord(
            2001L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        hisPatient.setDeptAdmissionTime(admissionTime);
        hisPatient.setBedNumber("hisBedNumber2099");
        hisPatientRepo.save(hisPatient);

        assertThat(patientSyncService.syncPatientRecords(false)).isEqualTo(1);

        assertThat(bedHistoryRepo.findByPatientId(patientId))
            .extracting(PatientBedHistory::getDisplayBedNumber)
            .containsExactly("dispBedNumber2001");
        PatientRecord synced = patientRepo.findById(patientId).orElseThrow();
        assertThat(synced.getHisBedNumber()).isEqualTo("hisBedNumber2099");
        assertThat(synced.getFromDeptId()).isEqualTo("fromDeptId2001");
        assertThat(synced.getFromDeptName()).isEqualTo("fromDeptName2001");
    }

    @Test
    void testIgnoreInIcuHisPatientRecordWithDischargeTime() {
        String deptId = "sync-ignore";
        String mrn = "dirty-his-mrn";
        setupDepartment(deptId);
        setupBed(deptId, "10", "device-10", "display-10");
        hisPatientRepo.save(HisPatientRecord.builder()
            .pid("dirty-his-pid").mrn(mrn).hisEncounterId("dirty-his-encounter")
            .deptCode(deptId).admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED)
            .bedNumber("10").dischargeTime(LocalDateTime.of(2026, 5, 27, 5, 20)).build());

        assertThat(patientSyncService.syncPatientRecords(true)).isZero();
        assertThat(patientRepo.findByMrnOrName(mrn)).isEmpty();
    }

    @Test
    void testSyncUsesHighestIdActiveHisRecordPerMrn() {
        String deptId = "latest-his";
        String mrn = "latest-his-a";
        String hisPid = "latest-his-pid";
        LocalDateTime admissionTime = LocalDateTime.of(2026, 5, 27, 5, 20);
        setupDepartment(deptId);
        setupBed(deptId, "9", "device-9", "9");

        // 同一住院号旧记录的床号为空，最新有效记录的床号是 9。
        hisPatientRepo.save(HisPatientRecord.builder()
            .pid(hisPid).mrn(mrn).hisEncounterId("older-encounter")
            .admissionCount(5).deptCode(deptId)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED)
            .deptAdmissionTime(admissionTime).build());
        HisPatientRecord latest = hisPatientRepo.save(HisPatientRecord.builder()
            .pid(hisPid).mrn(mrn).hisEncounterId("latest-encounter")
            .admissionCount(6).deptCode(deptId)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED)
            .bedNumber("9").deptAdmissionTime(admissionTime).build());
        hisPatientRepo.save(HisPatientRecord.builder()
            .pid("second-his-pid").mrn("latest-his-b").deptCode(deptId)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED).build());
        // 最后一条虽然 ID 更大，但已有出科时间，不能参与归并。
        hisPatientRepo.save(HisPatientRecord.builder()
            .pid(hisPid).mrn(mrn).deptCode(deptId)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED)
            .bedNumber("99").dischargeTime(admissionTime.plusHours(1)).build());

        PatientRecord patient = new PatientRecord();
        patient.setHisMrn(mrn);
        patient.setHisPatientId(hisPid);
        patient.setHisEncounterId(latest.getHisEncounterId());
        patient.setHisAdmissionCount(5);
        patient.setHisBedNumber("9");
        patient.setDeptId(deptId);
        patient.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
        patient.setAdmissionTime(admissionTime);
        Long patientId = patientRepo.save(patient).getId();

        assertThat(patientSyncService.syncPatientRecords(false)).isEqualTo(2);

        PatientRecord synced = patientRepo.findById(patientId).orElseThrow();
        assertThat(synced.getHisBedNumber()).isEqualTo("9");
        assertThat(synced.getHisAdmissionCount()).isEqualTo(6);
        assertThat(synced.getHisEncounterId()).isEqualTo(latest.getHisEncounterId());
        assertThat(synced.getAdmissionStatus())
            .isEqualTo(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
    }

    @Test
    void testManualSyncCreatesPendingPatientAndDiagnosisWithoutImportingSurgery() {
        String deptId = "diagnosis-sync-test";
        String mrn = "diagnosis-sync-mrn";
        LocalDateTime diagnosisTime = LocalDateTime.of(2026, 7, 24, 3, 4, 5);
        setupDepartment(deptId);
        setupBed(deptId, "11", "device-11", "11");
        hisPatientRepo.save(HisPatientRecord.builder()
            .pid("diagnosis-sync-pid").mrn(mrn).deptCode(deptId)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED)
            .bedNumber("11").deptAdmissionTime(diagnosisTime)
            .diagnosis("HIS通用诊断").diagnosisCode("HIS-A01").diagnosisTime(diagnosisTime)
            .operation("HIS存量手术").operationTime(diagnosisTime).build());

        SyncHisPatientResp response = patientService.syncHisPatient("{\"forceSync\":false}");

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(response.getAdmittedHisPatientCount()).isEqualTo(1);
        assertThat(patientRepo.findByMrnOrName(mrn)).singleElement().satisfies(patient -> {
            assertThat(patient.getAdmissionStatus())
                .isEqualTo(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
            assertThat(patient.getDiagnosis()).isEqualTo("HIS通用诊断");
            assertThat(patient.getSurgeryOperation()).isNull();
            assertThat(patient.getSurgeryOperationTime()).isNull();
            assertThat(surgeryRepo.findByPatientId(patient.getId())).isEmpty();
            assertThat(diagnosisRepo.findByPatientIdAndIsDeletedFalse(patient.getId()))
                .singleElement().satisfies(history -> {
                    assertThat(history.getDiagnosis()).isEqualTo("HIS通用诊断");
                    assertThat(history.getDiagnosisCode()).isEqualTo("HIS-A01");
                    assertThat(history.getDiagnosisTime()).isEqualTo(diagnosisTime);
                });
        });
    }

    @Test
    void testEmptyHisListRequiresForceBeforeMarkingPatientsPendingDischarge() {
        PatientRecord admitted = PatientTestUtils.newPatientRecord(
            21001L, PatientEnums.ADMISSION_STATUS_ADMITTED, "sync-confirm-a");
        admitted.setDischargeTime(null);
        PatientRecord pending = PatientTestUtils.newPatientRecord(
            21002L, PatientEnums.ADMISSION_STATUS_PRE_ADMISSION, "sync-confirm-b");
        pending.setDischargeTime(null);
        List<Long> patientIds = List.of(
            patientRepo.save(admitted).getId(), patientRepo.save(pending).getId());

        SyncHisPatientResp refresh = patientService.syncHisPatient("{\"forceSync\":false}");

        assertThat(refresh.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(refresh.getAdmittedHisPatientCount()).isZero();
        List<PatientRecord> unchanged = patientRepo.findByIdIn(patientIds);
        assertThat(unchanged)
            .extracting(PatientRecord::getAdmissionStatus)
            .containsExactlyInAnyOrder(PatientEnums.ADMISSION_STATUS_ADMITTED,
                PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
        assertThat(unchanged).allSatisfy(patient ->
            assertThat(patient.getDischargeTime()).isNull());

        SyncHisPatientResp forced = patientService.syncHisPatient("{\"forceSync\":true}");

        assertThat(forced.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(forced.getAdmittedHisPatientCount()).isZero();
        assertThat(patientRepo.findByIdIn(patientIds)).hasSize(2).allSatisfy(patient -> {
            assertThat(patient.getAdmissionStatus())
                .isEqualTo(PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE);
            assertThat(patient.getDischargeTime()).isNotNull();
        });
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Long.class, "patient_sync")).isEqualTo(1L);
    }

    @Test
    void testReadmissionMergesExistingHistoryWithoutRunningManualSync() {
        String deptId = "sync-readmission";
        PatientRecord departed = PatientTestUtils.newPatientRecord(
            29001L, PatientEnums.ADMISSION_STATUS_DISCHARGED, deptId);
        PatientRecord pending = PatientTestUtils.newPatientRecord(
            29002L, PatientEnums.ADMISSION_STATUS_PRE_ADMISSION, deptId);
        pending.setHisMrn(departed.getHisMrn());
        pending.setHisPatientId(departed.getHisPatientId());
        pending.setHisBedNumber(departed.getHisBedNumber());
        departed = patientRepo.save(departed);
        pending = patientRepo.save(pending);

        HisPatientRecord source = PatientTestUtils.newHisPatientRecord(
            29002L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        source.setMrn(pending.getHisMrn());
        source.setPid(pending.getHisPatientId());
        source.setBedNumber(pending.getHisBedNumber());
        hisPatientRepo.save(source);
        SurgeryHistory existingSurgery = surgeryRepo.save(SurgeryHistory.builder()
            .patientId(pending.getId()).name("既有手术").build());

        assertThat(patientReadmissionService.readmitPatient(departed, pending))
            .isEqualTo(StatusCode.OK);

        assertThat(patientRepo.findById(departed.getId())).isPresent();
        assertThat(patientRepo.findById(pending.getId())).isEmpty();
        assertThat(surgeryRepo.findByPatientId(departed.getId()))
            .extracting(SurgeryHistory::getId).containsExactly(existingSurgery.getId());
    }

    private void setupDepartment(String deptId) {
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName(deptId);
        deptRepo.save(dept);
    }

    private void setupBed(String deptId, String hisBed, String deviceBed, String displayBed) {
        bedConfigRepo.save(BedConfig.builder()
            .departmentId(deptId)
            .hisBedNumber(hisBed)
            .deviceBedNumber(deviceBed)
            .displayBedNumber(displayBed)
            .bedType(PatientEnums.BED_TYPE_FIXED)
            .isDeleted(false)
            .build());
    }

    @Autowired private PatientService patientService;
    @Autowired private PatientSyncService patientSyncService;
    @Autowired private PatientReadmissionService patientReadmissionService;
    @Autowired private RbacDepartmentRepository deptRepo;
    @Autowired private HisPatientRecordRepository hisPatientRepo;
    @Autowired private PatientRecordRepository patientRepo;
    @Autowired private BedConfigRepository bedConfigRepo;
    @Autowired private PatientBedHistoryRepository bedHistoryRepo;
    @Autowired private DiagnosisHistoryRepository diagnosisRepo;
    @Autowired private SurgeryHistoryRepository surgeryRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
}
