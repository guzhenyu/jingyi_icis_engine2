package com.jingyicare.jingyi_icis_engine.service.therapies;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.therapies.SepsisAndSepticShockBundle;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSepticShock.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.therapies.*;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Transactional
public class SepsisAndSepticShockBundleServiceTests extends TestsBase {
    public SepsisAndSepticShockBundleServiceTests(
        @Autowired SepsisAndSepticShockBundleService service,
        @Autowired PatientRecordRepository patientRecordRepo,
        @Autowired DiagnosisHistoryRepository diagnosisHistoryRepo,
        @Autowired PatientMonitoringRecordRepository patientMonitoringRecordRepo,
        @Autowired PatientLisItemRepository patientLisItemRepo,
        @Autowired PatientLisResultRepository patientLisResultRepo,
        @Autowired MedicationOrderGroupRepository medicationOrderGroupRepo,
        @Autowired MedicationExecutionRecordRepository medicationExecutionRecordRepo,
        @Autowired SepsisAndSepticShockBundleRepository bundleRepo
    ) {
        this.service = service;
        this.patientRecordRepo = patientRecordRepo;
        this.diagnosisHistoryRepo = diagnosisHistoryRepo;
        this.patientMonitoringRecordRepo = patientMonitoringRecordRepo;
        this.patientLisItemRepo = patientLisItemRepo;
        this.patientLisResultRepo = patientLisResultRepo;
        this.medicationOrderGroupRepo = medicationOrderGroupRepo;
        this.medicationExecutionRecordRepo = medicationExecutionRecordRepo;
        this.bundleRepo = bundleRepo;
    }

    @AfterEach
    void clearDiagnosisConfigOverride() {
        service.setDiagnosisConfigOverrideForTest(null);
        SecurityContextHolder.clearContext();
    }

    @Test
    void buildCasesCollectsEvidenceAndGeneratesBundle() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        long seed = 890001L;
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 10, 8, 0);
        LocalDateTime t0 = admissionTime.plusMinutes(10);

        PatientRecord patient = savePatient(seed, admissionTime);
        long pid = patient.getId();
        saveDiagnosis(pid, t0, "感染性休克");
        saveBloodPressure(pid, t0);
        saveLactate(patient, t0.plusMinutes(30), "R-LAC-1-" + pid, "4.5 mmol/L");
        saveLactate(patient, t0.plusHours(4), "R-LAC-2-" + pid, "2.8");
        saveLisItem(patient, t0.plusMinutes(20), "R-CULTURE-" + pid, "血培养+需氧瓶");
        saveMedication(pid, patient.getHisPatientId(), "美罗培南", t0.plusMinutes(35));
        saveMedication(pid, patient.getHisPatientId(), "0.9%氯化钠注射液", t0.plusHours(2));
        saveMedication(pid, patient.getHisPatientId(), "去甲肾上腺素", t0.plusHours(5));

        List<SepsisAndSepticShockCasePB> cases = service.buildSepticShockCases(List.of(pid));

        assertThat(cases).hasSize(1);
        SepsisAndSepticShockCasePB septicShockCase = cases.get(0);
        SepsisAndSepticShockInfoPB info = septicShockCase.getInfo();
        SepsisAndSepticShockBundlePB bundle = septicShockCase.getBundle();

        assertThat(info.getBloodPressureList()).hasSize(2);
        SepsisBloodPressureTimePointPB firstBp = info.getBloodPressure(0);
        assertThat(firstBp.getSelectedSource()).isEqualTo("ibp");
        assertThat(firstBp.getSelectedSbpMmhg()).isEqualTo(84.0);
        assertThat(firstBp.getSelectedDbpMmhg()).isEqualTo(48.0);
        assertThat(firstBp.getSelectedMapMmhg()).isEqualTo(60.0);
        assertThat(firstBp.getSelectedMapDerived()).isTrue();
        assertThat(firstBp.getParamValueList()).extracting(SepsisMonitoringParamValuePB::getParamCode)
            .contains("nibp_s", "nibp_d", "ibp_s", "ibp_d");

        assertThat(info.getLactateList()).hasSize(2);
        assertThat(info.getLactate(0).getLactateNumericParsed()).isTrue();
        assertThat(info.getLactate(0).getLactateMmolL()).isEqualTo(4.5);
        assertThat(info.getCultureLisItemsList()).hasSize(1);
        assertThat(info.getCultureLisItems(0).getLisItemName()).contains("血培养");

        assertThat(info.getAllMedicationsList()).hasSize(3);
        assertThat(info.getAbxHistoryList()).extracting(SepsisMedicationEvidencePB::getMedicationDisplayName)
            .containsExactly("美罗培南");
        assertThat(info.getFluidHistoryList()).extracting(SepsisMedicationEvidencePB::getMedicationDisplayName)
            .containsExactly("0.9%氯化钠注射液");
        assertThat(info.getVasopressorHistoryList()).extracting(SepsisMedicationEvidencePB::getMedicationDisplayName)
            .containsExactly("去甲肾上腺素");

        assertThat(bundle.getNeedBundle()).isTrue();
        assertThat(bundle.getH1LactateInitial()).isTrue();
        assertThat(bundle.getH1CultureBeforeAbx()).isTrue();
        assertThat(bundle.getH1AbxBroad()).isTrue();
        assertThat(bundle.getFluidQualified()).isTrue();
        assertThat(bundle.getFluid30Mlkg()).isTrue();
        assertThat(bundle.getFluid30MlkgNote()).isEqualTo("未核算 30 ml/kg");
        assertThat(bundle.getVasopressorQualified()).isTrue();
        assertThat(bundle.getVasopressor()).isTrue();
        assertThat(bundle.getRelactateQualified()).isTrue();
        assertThat(bundle.getRelactate()).isTrue();

        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(pid).orElseThrow();
        assertThat(entity.getNeedBundle()).isTrue();
        assertThat(entity.getH1LactateInitial()).isTrue();
        assertThat(entity.getFluid30mlkg()).isTrue();
        assertThat(entity.getVasopressor()).isTrue();
        assertThat(entity.getRelactate()).isTrue();
    }

    @Test
    void buildCasesUsesOrderGroupMedicationNameWhenExecutionDosageGroupIsBlank() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig().toBuilder()
            .addAbxBoardKeyword("哌拉西林")
            .build());
        long seed = 890006L;
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 15, 8, 0);
        LocalDateTime t0 = admissionTime;

        PatientRecord patient = savePatient(seed, admissionTime);
        long pid = patient.getId();
        saveDiagnosis(pid, t0, "感染性休克");
        saveMedication(pid, patient.getHisPatientId(), "哌拉西林", t0.plusMinutes(25), false);

        SepsisAndSepticShockCasePB septicShockCase = service.buildSepticShockCases(List.of(pid)).get(0);

        assertThat(septicShockCase.getInfo().getAllMedicationsList())
            .extracting(SepsisMedicationEvidencePB::getMedicationDisplayName)
            .containsExactly("哌拉西林");
        assertThat(septicShockCase.getInfo().getAbxHistoryList())
            .extracting(SepsisMedicationEvidencePB::getMedicationDisplayName)
            .containsExactly("哌拉西林");
        assertThat(septicShockCase.getBundle().getH1AbxBroad()).isTrue();
        assertThat(septicShockCase.getBundle().getH1AbxBroadIso8601()).isNotBlank();
    }

    @Test
    void buildCasesKeepsPersistedManualValuesOverAutoValues() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        long seed = 890002L;
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 11, 8, 0);
        LocalDateTime t0 = admissionTime.plusMinutes(10);

        PatientRecord patient = savePatient(seed, admissionTime);
        long pid = patient.getId();
        saveDiagnosis(pid, t0, "感染性休克");
        saveBloodPressure(pid, t0);
        saveLactate(patient, t0.plusMinutes(30), "R-LAC-1-" + pid, "4.2");
        saveLisItem(patient, t0.plusMinutes(20), "R-CULTURE-" + pid, "血培养+厌氧瓶");
        saveMedication(pid, patient.getHisPatientId(), "美罗培南", t0.plusMinutes(35));

        bundleRepo.save(SepsisAndSepticShockBundle.builder()
            .pid(pid)
            .t0(t0)
            .needBundle(true)
            .h1LactateInitial(false)
            .h1LactateInitialNote("人工判定乳酸不计入")
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy("tester")
            .build());

        SepsisAndSepticShockCasePB septicShockCase = service.buildSepticShockCases(List.of(pid)).get(0);

        assertThat(septicShockCase.getInfo().getLactateList()).hasSize(1);
        assertThat(septicShockCase.getBundle().getH1LactateInitial()).isFalse();
        assertThat(septicShockCase.getBundle().getH1LactateInitialNote()).isEqualTo("人工判定乳酸不计入");

        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(pid).orElseThrow();
        assertThat(entity.getH1LactateInitial()).isFalse();
        assertThat(entity.getH1LactateInitialNote()).isEqualTo("人工判定乳酸不计入");
    }

    @Test
    void buildCasesKeepsManualNeedBundleTrueWhenAutoEligibilityIsFalse() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        long seed = 890003L;
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 12, 8, 0);
        LocalDateTime manualT0 = admissionTime.plusHours(1);

        PatientRecord patient = savePatient(seed, admissionTime);
        long pid = patient.getId();
        saveDiagnosis(pid, admissionTime.plusMinutes(10), "重症肺炎");
        saveLactate(patient, manualT0.plusMinutes(30), "R-LAC-MANUAL-" + pid, "3.1");

        bundleRepo.save(SepsisAndSepticShockBundle.builder()
            .pid(pid)
            .t0(manualT0)
            .needBundle(true)
            .noBundleReason("人工纳入")
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy("tester")
            .build());

        SepsisAndSepticShockCasePB septicShockCase = service.buildSepticShockCases(List.of(pid)).get(0);

        assertThat(septicShockCase.getBundle().getNeedBundle()).isTrue();
        assertThat(septicShockCase.getBundle().getT0Iso8601()).isNotBlank();
        assertThat(septicShockCase.getBundle().getNoBundleReason()).isEqualTo("人工纳入");
        assertThat(septicShockCase.getInfo().getLactateList()).hasSize(1);

        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(pid).orElseThrow();
        assertThat(entity.getNeedBundle()).isTrue();
        assertThat(entity.getT0()).isEqualTo(manualT0);
    }

    @Test
    void saveCaseRejectsCompletedTaskWithoutTime() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 13, 8, 0);
        PatientRecord patient = savePatient(890004L, admissionTime);
        authenticateAsAdmin();

        SaveSepticShockCaseResp resp = service.saveSepticShockCase(String.format("""
            {"bundlePatch":{"bundle":{"pid":"%d","h1LactateInitial":true},"updateMask":"h1LactateInitial"}}
            """, patient.getId()));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.INVALID_TIME_FORMAT.getNumber());
        assertThat(resp.getRt().getMsg()).contains("初始乳酸测定完成时间不能为空");
        assertThat(bundleRepo.findByPid(patient.getId())).isEmpty();
    }

    @Test
    void saveCaseAllowsCompletedTaskWhenPersistedTimeExists() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 14, 8, 0);
        LocalDateTime t0 = admissionTime.plusMinutes(10);
        LocalDateTime lactateTime = t0.plusMinutes(20);
        PatientRecord patient = savePatient(890005L, admissionTime);
        bundleRepo.save(SepsisAndSepticShockBundle.builder()
            .pid(patient.getId())
            .t0(t0)
            .needBundle(true)
            .h1LactateInitial(false)
            .h1LactateInitialTime(lactateTime)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy("tester")
            .build());
        authenticateAsAdmin();

        SaveSepticShockCaseResp resp = service.saveSepticShockCase(String.format("""
            {"bundlePatch":{"bundle":{"pid":"%d","h1LactateInitial":true},"updateMask":"h1LactateInitial"}}
            """, patient.getId()));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(patient.getId()).orElseThrow();
        assertThat(entity.getH1LactateInitial()).isTrue();
        assertThat(entity.getH1LactateInitialTime()).isEqualTo(lactateTime);
    }

    @Test
    void saveCaseAcceptsFluid30mlkgJsonNameAndMask() {
        service.setDiagnosisConfigOverrideForTest(testDiagnosisConfig());
        LocalDateTime admissionTime = LocalDateTime.of(2026, 1, 16, 8, 0);
        LocalDateTime t0 = admissionTime;
        LocalDateTime fluidTime = t0.plusHours(2).plusMinutes(8);
        PatientRecord patient = savePatient(890007L, admissionTime);
        bundleRepo.save(SepsisAndSepticShockBundle.builder()
            .pid(patient.getId())
            .t0(t0)
            .needBundle(true)
            .fluidQualified(true)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy("tester")
            .build());
        authenticateAsAdmin();

        SaveSepticShockCaseResp resp = service.saveSepticShockCase(String.format("""
            {"bundlePatch":{"bundle":{"pid":"%d","fluid30mlkg":true,"fluid30mlkgIso8601":"%s"},"updateMask":"fluid30mlkg,fluid30mlkgIso8601"}}
            """, patient.getId(), TimeUtils.toIso8601String(fluidTime, "UTC")));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(patient.getId()).orElseThrow();
        assertThat(entity.getFluid30mlkg()).isTrue();
        assertThat(entity.getFluid30mlkgTime()).isEqualTo(fluidTime);
    }

    private SepsisSepticShockDiagnosisConfigPB testDiagnosisConfig() {
        return SepsisSepticShockDiagnosisConfigPB.newBuilder()
            .setAgeLowerBound(18)
            .setGracePeriodHoursFromAdmissionTime(24)
            .setDiagnosisLookbackHoursBeforeAdmissionTime(0)
            .addDiagnosisKeyword("感染性休克")
            .setHypotensionCriteria(HypotensionCriteriaPB.newBuilder()
                .setMbpThreshold(65)
                .setSbpThreshold(90)
                .build())
            .addBloodCultureKeyword("血培养")
            .addAbxBoardKeyword("美罗培南")
            .addFluidKeyword("氯化钠")
            .addVasopressorKeyword("去甲肾上腺素")
            .addLacExternalParamCode("LAC_TEST")
            .build();
    }

    private PatientRecord savePatient(long pid, LocalDateTime admissionTime) {
        PatientRecord patient = PatientTestUtils.newPatientRecord(pid, 1, "sepsis-test");
        patient.setId(null);
        patient.setIcuDateOfBirth(LocalDateTime.of(1970, 1, 1, 0, 0));
        patient.setAdmissionTime(admissionTime);
        patient.setDischargeTime(null);
        patient.setHisPatientId("his-sepsis-" + pid);
        patient.setDiagnosis("感染性休克");
        return patientRecordRepo.save(patient);
    }

    private void saveDiagnosis(long pid, LocalDateTime diagnosisTime, String diagnosis) {
        diagnosisHistoryRepo.save(DiagnosisHistory.builder()
            .patientId(pid)
            .diagnosis(diagnosis)
            .diagnosisTime(diagnosisTime)
            .diagnosisAccountId("tester")
            .isDeleted(false)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy("tester")
            .build());
    }

    private void saveBloodPressure(long pid, LocalDateTime t0) {
        LocalDateTime firstTime = t0.plusMinutes(15);
        saveMonitoring(pid, firstTime, "nibp_s", 82);
        saveMonitoring(pid, firstTime, "nibp_d", 50);
        saveMonitoring(pid, firstTime, "ibp_s", 84);
        saveMonitoring(pid, firstTime, "ibp_d", 48);
        saveMonitoring(pid, t0.plusHours(5).plusMinutes(30), "ibp_m", 60);
        saveMonitoring(pid, t0.plusHours(7), "ibp_m", 50);
    }

    private void saveMonitoring(long pid, LocalDateTime effectiveTime, String paramCode, double value) {
        patientMonitoringRecordRepo.save(PatientMonitoringRecord.builder()
            .pid(pid)
            .deptId("sepsis-test")
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValue(monitoringValue(value))
            .paramValueStr(String.valueOf(value))
            .unit("mmHg")
            .source("test")
            .modifiedBy("tester")
            .modifiedAt(TimeUtils.getNowUtc())
            .isDeleted(false)
            .build());
    }

    private String monitoringValue(double value) {
        return ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
            .setValue(GenericValuePB.newBuilder().setFloatVal((float) value).build())
            .build());
    }

    private void saveLactate(PatientRecord patient, LocalDateTime authTime, String reportId, String resultStr) {
        saveLisItem(patient, authTime, reportId, "乳酸");
        patientLisResultRepo.save(PatientLisResult.builder()
            .reportId(reportId)
            .externalParamCode("LAC_TEST")
            .externalParamName("乳酸")
            .resultStr(resultStr)
            .unit("mmol/L")
            .authTime(authTime)
            .isDeleted(false)
            .modifiedAt(TimeUtils.getNowUtc())
            .build());
    }

    private void saveLisItem(PatientRecord patient, LocalDateTime authTime, String reportId, String itemName) {
        patientLisItemRepo.save(PatientLisItem.builder()
            .reportId(reportId)
            .mrn(patient.getHisMrn())
            .hisPid(patient.getHisPatientId())
            .lisItemName(itemName)
            .authTime(authTime)
            .build());
    }

    private void saveMedication(long pid, String hisPatientId, String displayName, LocalDateTime startTime) {
        saveMedication(pid, hisPatientId, displayName, startTime, true);
    }

    private void saveMedication(
        long pid,
        String hisPatientId,
        String displayName,
        LocalDateTime startTime,
        boolean copyDosageGroupToExecutionRecord
    ) {
        MedicationDosageGroupPB dosageGroup = MedicationDosageGroupPB.newBuilder()
            .setDisplayName(displayName)
            .addMd(MedicationDosagePB.newBuilder().setName(displayName).build())
            .build();
        String encodedDosageGroup = ProtoUtils.encodeDosageGroup(dosageGroup);
        String groupId = "group-" + pid + "-" + displayName;
        MedicationOrderGroup orderGroup = medicationOrderGroupRepo.save(MedicationOrderGroup.builder()
            .hisPatientId(hisPatientId)
            .patientId(pid)
            .groupId(groupId)
            .deptId("sepsis-test")
            .orderType("西药")
            .status("正常")
            .orderTime(startTime.minusMinutes(10))
            .orderValidity(1)
            .medicationDosageGroup(encodedDosageGroup)
            .orderDurationType(1)
            .freqCode("once")
            .planTime(startTime)
            .administrationRouteCode("iv")
            .administrationRouteName("静滴")
            .createdAt(TimeUtils.getNowUtc())
            .build());

        medicationExecutionRecordRepo.save(MedicationExecutionRecord.builder()
            .medicationOrderGroupId(orderGroup.getId())
            .hisOrderGroupId(groupId)
            .patientId(pid)
            .planTime(startTime)
            .startTime(startTime)
            .isDeleted(false)
            .userTouched(false)
            .medicationDosageGroup(copyDosageGroupToExecutionRecord ? encodedDosageGroup : "")
            .isContinuous(false)
            .createAccountId("tester")
            .createdAt(TimeUtils.getNowUtc())
            .build());
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("admin", null, Collections.emptyList()));
    }

    private final SepsisAndSepticShockBundleService service;
    private final PatientRecordRepository patientRecordRepo;
    private final DiagnosisHistoryRepository diagnosisHistoryRepo;
    private final PatientMonitoringRecordRepository patientMonitoringRecordRepo;
    private final PatientLisItemRepository patientLisItemRepo;
    private final PatientLisResultRepository patientLisResultRepo;
    private final MedicationOrderGroupRepository medicationOrderGroupRepo;
    private final MedicationExecutionRecordRepository medicationExecutionRecordRepo;
    private final SepsisAndSepticShockBundleRepository bundleRepo;
}
