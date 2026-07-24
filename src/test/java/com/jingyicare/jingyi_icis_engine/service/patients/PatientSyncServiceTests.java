package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientSyncServiceTests extends TestsBase {
    public PatientSyncServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientSyncService patientSyncService,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired HisPatientRecordRepository hisPatientRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired PatientBedHistoryRepository bedHistoryRepo,
        @Autowired PatientSyncUtils patientSyncUtils
    ) {
        this.protoService = protoService;
        this.patientSyncService = patientSyncService;
        this.deptRepo = deptRepo;
        this.hisPatientRepo = hisPatientRepo;
        this.patientRepo = patientRepo;
        this.bedConfigRepo = bedConfigRepo;
        this.bedHistoryRepo = bedHistoryRepo;
        this.patientSyncUtils = patientSyncUtils;
    }

    @Test
    public void testSwitchBed() {
        // 初始化测试部门
        String deptId = "10038";
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName("dept-10038");
        deptRepo.save(dept);

        // 初始化床位配置
        BedConfig bedConfig = BedConfig.builder()
            .departmentId(deptId)
            .hisBedNumber("hisBedNumber2001")
            .deviceBedNumber("devBedNumber2001")
            .displayBedNumber("dispBedNumber2001")
            .bedType(1/*固定*/)
            .isDeleted(false)
            .build();
        bedConfigRepo.save(bedConfig);
        bedConfig.setId(null);
        bedConfig.setHisBedNumber("hisBedNumber2099");
        bedConfig.setDeviceBedNumber("devBedNumber2099");
        bedConfig.setDisplayBedNumber("dispBedNumber2099");
        bedConfigRepo.save(bedConfig);

        // 初始化测试病人
        LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 7, 31), "Asia/Shanghai"
        );
        PatientRecord patient = PatientTestUtils.newPatientRecord(2001L, 1/*在科*/, deptId);
        patient.setAdmissionTime(admissionTime);
        patient.setDischargeTime(null);
        patient.setFromDeptId(null);
        patient.setFromDeptName(null);
        patient = patientRepo.save(patient);

        HisPatientRecord hisPatient = PatientTestUtils.newHisPatientRecord(2001L, 1/*在科*/, deptId);
        hisPatient.setDeptAdmissionTime(admissionTime);
        hisPatient = hisPatientRepo.save(hisPatient);

        // 检查病人床位历史
        List<PatientBedHistory> bedHistories = bedHistoryRepo.findByPatientId(patient.getId());
        assertThat(bedHistories).isEmpty();

        // 执行切换床位操作
        hisPatient.setBedNumber("hisBedNumber2099");
        hisPatient = hisPatientRepo.save(hisPatient);
        patientSyncService.syncPatientRecords(true, deptId);

        // 检查病人床位记录
        bedHistories = bedHistoryRepo.findByPatientId(patient.getId());
        assertThat(bedHistories).hasSize(1);
        assertThat(bedHistories.get(0).getDisplayBedNumber()).isEqualTo("dispBedNumber2001");
        patient = patientRepo.findById(patient.getId()).orElse(null);
        assertThat(patient).isNotNull();
        assertThat(patient.getHisBedNumber()).isEqualTo("hisBedNumber2099");
        assertThat(patient.getFromDeptId()).isEqualTo("fromDeptId2001");
        assertThat(patient.getFromDeptName()).isEqualTo("fromDeptName2001");
    }

    @Test
    public void testIgnoreInIcuHisPatientRecordWithDischargeTime() {
        String idSuffix = UUID.randomUUID().toString().substring(0, 8);
        String deptId = "sync-ignore-" + idSuffix;
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName("dept-" + deptId);
        deptRepo.save(dept);

        Long hisId = 26000000L + Integer.toUnsignedLong(idSuffix.hashCode()) % 1000000;
        String mrn = "dirty-his-mrn-" + idSuffix;
        HisPatientRecord hisPatient = PatientTestUtils.newHisPatientRecord(hisId, 1/*在科*/, deptId);
        hisPatient.setPid("dirty-his-pid-" + idSuffix);
        hisPatient.setMrn(mrn);
        hisPatient.setHisEncounterId("dirty-his-encounter-" + idSuffix);
        hisPatient.setDischargeTime(LocalDateTime.of(2026, 5, 27, 5, 20));
        hisPatientRepo.save(hisPatient);

        patientSyncService.syncPatientRecords(true, deptId);

        assertThat(patientRepo.findByMrnOrName(mrn)).isEmpty();
    }

    @Test
    public void testSyncGenericDiagnosisIntoPatientAndHistory() {
        Long id = 29000001L;
        String deptId = "diagnosis-sync-test";
        PatientRecord patient = PatientTestUtils.newPatientRecord(id, 1, deptId);
        HisPatientRecord hisPatient = PatientTestUtils.newHisPatientRecord(id, 1, deptId);
        LocalDateTime diagnosisTime = LocalDateTime.of(2026, 7, 24, 3, 4, 5);

        patient.setDiagnosis(null);
        hisPatient.setDiagnosis("HIS通用诊断");
        hisPatient.setDiagnosisCode("HIS-A01");
        hisPatient.setDiagnosisTime(diagnosisTime);

        Map<String, PatientSyncUtils.PatientSyncInfo> syncInfoMap = new HashMap<>();
        assertThat(patientSyncUtils.updatePatientRecord(hisPatient, patient, syncInfoMap)).isTrue();
        assertThat(patient.getDiagnosis()).isEqualTo("HIS通用诊断");
        assertThat(syncInfoMap).containsKey(hisPatient.getMrn());
        assertThat(syncInfoMap.get(hisPatient.getMrn()).diagnosisHisList).singleElement()
            .satisfies(history -> {
                assertThat(history.getDiagnosis()).isEqualTo("HIS通用诊断");
                assertThat(history.getDiagnosisCode()).isEqualTo("HIS-A01");
                assertThat(history.getDiagnosisTime()).isEqualTo(diagnosisTime);
            });
    }

    private final ConfigProtoService protoService;
    private final PatientSyncService patientSyncService;

    private final RbacDepartmentRepository deptRepo;
    private final HisPatientRecordRepository hisPatientRepo;
    private final PatientRecordRepository patientRepo;
    private final BedConfigRepository bedConfigRepo;
    private final PatientBedHistoryRepository bedHistoryRepo;
    private final PatientSyncUtils patientSyncUtils;
}
