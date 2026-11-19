package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AdmitPatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.ReadmissionHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class PatientAdmissionServiceTests extends TestsBase {
    @BeforeEach
    void signIn() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null,
                AuthorityUtils.createAuthorityList("ROLE_1")));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
            DELETE FROM patient_devices
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("""
            DELETE FROM patient_bed_history
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("""
            DELETE FROM diagnosis_history
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("""
            DELETE FROM surgery_history
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("""
            DELETE FROM readmission_history
            WHERE patient_id IN (SELECT id FROM patient_records WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("DELETE FROM device_infos WHERE department_id = ?", DEPT);
        jdbc.update("DELETE FROM bed_configs WHERE department_id = ?", DEPT);
        jdbc.update("DELETE FROM his_patient_records WHERE dept_code = ?", DEPT);
        jdbc.update("DELETE FROM patient_records WHERE dept_id = ?", DEPT);
    }

    @Test
    void readmissionKeepsOriginalCaseAndMigratesExistingHistory() {
        ReadmissionFixture fixture = createReadmission("新床");
        DiagnosisHistory diagnosis = diagnosisRepo.save(DiagnosisHistory.builder()
            .patientId(fixture.pendingId()).diagnosis("已有诊断").diagnosisTime(BED_START)
            .diagnosisAccountId("his").isDeleted(false)
            .modifiedAt(BED_START).modifiedBy("his").build());
        SurgeryHistory surgery = surgeryRepo.save(SurgeryHistory.builder()
            .patientId(fixture.pendingId()).name("已有手术").build());

        assertThat(admit(fixture).getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

        PatientRecord previous = patientRepo.findById(fixture.previousId()).orElseThrow();
        assertThat(previous.getAdmissionStatus()).isEqualTo(PatientEnums.ADMISSION_STATUS_ADMITTED);
        assertThat(previous.getAdmissionTime()).isEqualTo(ORIGINAL_ADMISSION);
        assertThat(previous.getDischargeTime()).isNull();
        assertThat(previous.getHisBedNumber()).isEqualTo("新床");
        assertThat(patientRepo.findById(fixture.pendingId())).isEmpty();
        assertThat(bedHistoryRepo.findByPatientId(fixture.previousId()))
            .extracting(PatientBedHistory::getSwitchType)
            .containsExactlyInAnyOrder(
                PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE,
                PatientEnums.SWITCH_TYPE_READMISSION_ADMIT);
        assertThat(readmissionHistoryRepo.findByPatientId(fixture.previousId())).hasSize(1);
        assertThat(diagnosisRepo.findById(diagnosis.getId()).orElseThrow().getPatientId())
            .isEqualTo(fixture.previousId());
        assertThat(surgeryRepo.findById(surgery.getId()).orElseThrow().getPatientId())
            .isEqualTo(fixture.previousId());
    }

    @Test
    void failureAfterInitialBedChangeRollsBackReadmission() {
        ReadmissionFixture fixture = createReadmission("HIS床位无配置");
        Map<String, Object> previousBefore = snapshot(fixture.previousId());
        Map<String, Object> pendingBefore = snapshot(fixture.pendingId());

        GenericResp response = admit(fixture);

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.BED_CONFIG_NOT_EXISTS_VALUE);
        assertThat(snapshot(fixture.previousId())).isEqualTo(previousBefore);
        assertThat(snapshot(fixture.pendingId())).isEqualTo(pendingBefore);
        assertThat(bedHistoryRepo.findByPatientId(fixture.previousId())).isEmpty();
        assertThat(readmissionHistoryRepo.findByPatientId(fixture.previousId())).isEmpty();
    }

    @Test
    void historicalDeviceBindingPreventsDeletingPendingCase() {
        ReadmissionFixture fixture = createReadmission("新床");
        int deviceId = jdbc.queryForObject("""
            INSERT INTO device_infos (department_id, device_name, device_type, is_deleted,
                device_sn, source_mode, source_topology, upstream_device_id, pds_ip_seq)
            VALUES (?, '重返测试设备', ?, FALSE, '', ?, ?, 0, 0) RETURNING id
            """, Integer.class, DEPT, DeviceEnums.DEVICE_TYPE_MONITOR,
            DeviceEnums.SOURCE_MODE_INBOUND_SERVER, DeviceEnums.SOURCE_TOPOLOGY_SINGLE_DEVICE);
        jdbc.update("""
            INSERT INTO patient_devices (patient_id, device_id, binding_time, unbinding_time,
                is_deleted, modified_at)
            VALUES (?, ?, ?, ?, FALSE, ?)
            """, fixture.pendingId(), deviceId, BED_START, BED_START.plusHours(1), BED_START);
        Map<String, Object> previousBefore = snapshot(fixture.previousId());
        Map<String, Object> pendingBefore = snapshot(fixture.pendingId());

        GenericResp response = admit(fixture);

        assertThat(response.getRt().getCode())
            .isEqualTo(StatusCode.READMISSION_PATIENT_INCONSISTENT_VALUE);
        assertThat(snapshot(fixture.previousId())).isEqualTo(previousBefore);
        assertThat(snapshot(fixture.pendingId())).isEqualTo(pendingBefore);
        assertThat(bedHistoryRepo.findByPatientId(fixture.previousId())).isEmpty();
        assertThat(readmissionHistoryRepo.findByPatientId(fixture.previousId())).isEmpty();
    }

    private ReadmissionFixture createReadmission(String hisBedNumber) {
        saveBed("旧床");
        saveBed("新床");
        PatientRecord previous = new PatientRecord();
        previous.setDeptId(DEPT);
        previous.setHisMrn(MRN);
        previous.setHisPatientId(HIS_PID);
        previous.setHisBedNumber("旧床");
        previous.setAdmissionTime(ORIGINAL_ADMISSION);
        previous.setDischargeTime(BED_START.minusHours(1));
        previous.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_DISCHARGED);
        PatientRecord pending = new PatientRecord();
        pending.setDeptId(DEPT);
        pending.setHisMrn(MRN);
        pending.setHisPatientId(HIS_PID);
        pending.setHisBedNumber("新床");
        pending.setAdmissionTime(BED_START);
        pending.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
        long previousId = patientRepo.save(previous).getId();
        long pendingId = patientRepo.save(pending).getId();
        hisRepo.save(HisPatientRecord.builder().pid(HIS_PID).mrn(MRN).deptCode(DEPT)
            .name("重返患者").bedNumber(hisBedNumber).deptAdmissionTime(BED_START)
            .admissionStatus(PatientEnums.ADMISSION_STATUS_ADMITTED).build());
        return new ReadmissionFixture(previousId, pendingId);
    }

    private void saveBed(String bedNumber) {
        bedRepo.save(BedConfig.builder().departmentId(DEPT)
            .hisBedNumber(bedNumber).displayBedNumber(bedNumber).deviceBedNumber(bedNumber)
            .bedType(PatientEnums.BED_TYPE_FIXED).isDeleted(false).build());
    }

    private GenericResp admit(ReadmissionFixture fixture) {
        AdmitPatientReq req = AdmitPatientReq.newBuilder().setDeptId(DEPT).setHisMrn(MRN)
            .setAdmissionTime(TimeUtils.toIso8601String(BED_START.plusMinutes(5), "UTC"))
            .setToMerge(true).setLastPid(fixture.previousId()).build();
        return service.admitPatient(ProtoUtils.protoToJson(req));
    }

    private Map<String, Object> snapshot(long patientId) {
        return jdbc.queryForMap("SELECT * FROM patient_records WHERE id = ?", patientId);
    }

    private record ReadmissionFixture(long previousId, long pendingId) {}

    private static final String DEPT = "admission-write-tests";
    private static final String MRN = "admission-write-mrn";
    private static final String HIS_PID = "admission-write-his-pid";
    private static final LocalDateTime BED_START = LocalDateTime.of(2025, 7, 24, 3, 4);
    private static final LocalDateTime ORIGINAL_ADMISSION = BED_START.minusDays(1);

    @Autowired private PatientService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private BedConfigRepository bedRepo;
    @Autowired private HisPatientRecordRepository hisRepo;
    @Autowired private PatientRecordRepository patientRepo;
    @Autowired private DiagnosisHistoryRepository diagnosisRepo;
    @Autowired private SurgeryHistoryRepository surgeryRepo;
    @Autowired private PatientBedHistoryRepository bedHistoryRepo;
    @Autowired private ReadmissionHistoryRepository readmissionHistoryRepo;
}
