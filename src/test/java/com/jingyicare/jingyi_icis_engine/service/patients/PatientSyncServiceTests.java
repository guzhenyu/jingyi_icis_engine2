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
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientSyncService;
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
    void testInvalidBedLeavesPatientUnchangedAndOtherPatientStillSyncs() {
        String deptId = "sync-invalid-bed";
        setupDepartment(deptId);
        setupBed(deptId, "hisBedNumber2002", "device-2002", "display-2002");
        setupBed(deptId, "hisBedNumber2003", "device-2003", "display-2003");

        PatientRecord rejected = PatientTestUtils.newPatientRecord(
            2002L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        rejected.setDiagnosis(null);
        rejected.setHisEncounterId(null);
        rejected.setDischargeTime(null);
        long rejectedPid = patientRepo.save(rejected).getId();

        HisPatientRecord rejectedHis = PatientTestUtils.newHisPatientRecord(
            2002L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        rejectedHis.setBedNumber("nonexistent-bed");
        rejectedHis.setName("HIS修改姓名");
        hisPatientRepo.save(rejectedHis);

        HisPatientRecord acceptedHis = PatientTestUtils.newHisPatientRecord(
            2003L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        hisPatientRepo.save(acceptedHis);

        assertThat(patientSyncService.syncPatientRecords(false)).isEqualTo(2);

        PatientRecord unchanged = patientRepo.findById(rejectedPid).orElseThrow();
        assertThat(unchanged.getHisBedNumber()).isEqualTo("hisBedNumber2002");
        assertThat(unchanged.getIcuName()).isEqualTo("icuName2002");
        assertThat(unchanged.getDiagnosis()).isBlank();
        assertThat(unchanged.getHisEncounterId()).isBlank();
        assertThat(unchanged.getAdmissionStatus()).isEqualTo(PatientEnums.ADMISSION_STATUS_ADMITTED);
        assertThat(bedHistoryRepo.findByPatientId(rejectedPid)).isEmpty();
        assertThat(diagnosisRepo.findByPatientIdAndIsDeletedFalse(rejectedPid)).isEmpty();
        assertThat(patientRepo.findByMrnOrName("mrn2003")).singleElement()
            .satisfies(patient -> assertThat(patient.getAdmissionStatus())
                .isEqualTo(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION));
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
    void testDuplicateLocalMrnKeepsDiagnosisWithMatchedCase() {
        String deptId = "sync-duplicate-local";
        setupDepartment(deptId);
        setupBed(deptId, "hisBedNumber2004", "device-2004", "display-2004");
        PatientRecord first = PatientTestUtils.newPatientRecord(
            2004L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        PatientRecord duplicate = PatientTestUtils.newPatientRecord(
            2005L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId);
        duplicate.setHisMrn(first.getHisMrn());
        duplicate.setHisPatientId(first.getHisPatientId());
        duplicate.setHisBedNumber(first.getHisBedNumber());
        first.setDiagnosis(null);
        duplicate.setDiagnosis(null);
        List<Long> pids = List.of(
            patientRepo.save(first).getId(), patientRepo.save(duplicate).getId());
        hisPatientRepo.save(PatientTestUtils.newHisPatientRecord(
            2004L, PatientEnums.ADMISSION_STATUS_ADMITTED, deptId));

        assertThat(patientSyncService.syncPatientRecords(false)).isEqualTo(1);

        List<PatientRecord> patients = patientRepo.findByIdIn(pids);
        assertThat(patients).filteredOn(patient ->
            PatientEnums.ADMISSION_STATUS_ADMITTED.equals(patient.getAdmissionStatus()))
            .singleElement().satisfies(patient -> assertThat(
                diagnosisRepo.findByPatientIdAndIsDeletedFalse(patient.getId()))
                .singleElement().satisfies(history ->
                    assertThat(history.getDiagnosis()).isEqualTo("diagnosis2004")));
        assertThat(patients).filteredOn(patient ->
            PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE.equals(patient.getAdmissionStatus()))
            .singleElement().satisfies(patient -> assertThat(
                diagnosisRepo.findByPatientIdAndIsDeletedFalse(patient.getId())).isEmpty());
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
    @Autowired private RbacDepartmentRepository deptRepo;
    @Autowired private HisPatientRecordRepository hisPatientRepo;
    @Autowired private PatientRecordRepository patientRepo;
    @Autowired private BedConfigRepository bedConfigRepo;
    @Autowired private PatientBedHistoryRepository bedHistoryRepo;
    @Autowired private DiagnosisHistoryRepository diagnosisRepo;
    @Autowired private SurgeryHistoryRepository surgeryRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
}
