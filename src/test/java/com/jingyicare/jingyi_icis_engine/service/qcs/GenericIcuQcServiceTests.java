package com.jingyicare.jingyi_icis_engine.service.qcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSepticShock.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.therapies.SepsisAndSepticShockBundleService;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class GenericIcuQcServiceTests {
    @Test
    void septicShockBundleCompletionRateUsesAdmissionTimeAndQcAlarmFilter() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        IcuQcConfigService icuQcConfigService = mock(IcuQcConfigService.class);
        PatientService patientService = mock(PatientService.class);
        PatientConfig patientConfig = mock(PatientConfig.class);
        SepsisAndSepticShockBundleService septicShockBundleService =
            mock(SepsisAndSepticShockBundleService.class);
        DepartmentRepository deptRepo = mock(DepartmentRepository.class);

        when(protoService.getConfig()).thenReturn(testConfig());
        when(icuQcConfigService.getIcuQcConfigPb()).thenReturn(testQcConfig());
        when(deptRepo.findByDeptIdAndIsDeletedFalse("99999")).thenReturn(Optional.of(dept("99999")));
        when(patientConfig.getDischargeTypeDeadId()).thenReturn(-1);
        when(patientConfig.getBedConfigMap("99999")).thenReturn(Map.of(
            "H-900001", bedConfig("99999", "H-900001", "B1"),
            "H-900002", bedConfig("99999", "H-900002", "B2"),
            "H-900004", bedConfig("99999", "H-900004", "B4")));

        PatientRecord beforePeriod = patient(900000L, "99999", LocalDateTime.of(2024, 12, 31, 23, 0));
        PatientRecord h1MissingInJan = patient(900001L, "99999", LocalDateTime.of(2025, 1, 10, 8, 0));
        PatientRecord completeH1InJan = patient(900002L, "99999", LocalDateTime.of(2025, 1, 20, 8, 0));
        PatientRecord needBundleFalseInJan = patient(900003L, "99999", LocalDateTime.of(2025, 1, 25, 8, 0));
        PatientRecord h1MissingInFeb = patient(900004L, "99999", LocalDateTime.of(2025, 2, 5, 8, 0));
        PatientRecord atQueryEnd = patient(900005L, "99999", LocalDateTime.of(2025, 3, 1, 0, 0));
        List<PatientRecord> patients = List.of(
            beforePeriod,
            h1MissingInJan,
            completeH1InJan,
            needBundleFalseInJan,
            h1MissingInFeb,
            atQueryEnd);
        when(patientService.getPatientRecords(eq("99999"), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(patients);
        when(septicShockBundleService.buildSepticShockCasesReadOnly(List.of(
            900000L, 900001L, 900002L, 900003L, 900004L, 900005L)))
            .thenReturn(List.of(
                septicShockCase(900000L, h1UnfinishedBundle(900000L)),
                septicShockCase(900001L, h1UnfinishedBundle(900001L)),
                septicShockCase(900002L, h1CompleteBundle(900002L)),
                septicShockCase(900003L, SepsisAndSepticShockBundlePB.newBuilder()
                    .setPid(900003L)
                    .setNeedBundle(false)
                    .build()),
                septicShockCase(900004L, h1UnfinishedBundle(900004L)),
                septicShockCase(900005L, h1UnfinishedBundle(900005L))));

        GenericIcuQcService service = new GenericIcuQcService(
            protoService,
            icuQcConfigService,
            patientService,
            patientConfig,
            septicShockBundleService,
            mock(BedUtilizationCalc.class),
            deptRepo,
            mock(DepartmentAccountRepository.class),
            mock(AccountRepository.class),
            mock(RbacRoleRepository.class),
            mock(BedCountRepository.class),
            mock(ApacheIIScoreRepository.class),
            mock(VTECapriniScoreRepository.class),
            mock(VTEPaduaScoreRepository.class),
            mock(PatientScoreRepository.class));

        GetGenericIcuQcReq req = GetGenericIcuQcReq.newBuilder()
            .setDeptId("99999")
            .setQueryStartIso8601("2025-01-01T00:00:00.000Z")
            .setQueryEndIso8601("2025-03-01T00:00:00.000Z")
            .addItemCode(Consts.ICU_5_SEPTIC_SHOCK_BUNDLE_COMPLETION_RATE)
            .build();

        GetGenericIcuQcResp resp = service.getGenericIcuQc(ProtoUtils.protoToJson(req));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        IcuQcSepticShockBundleCompletionRatePB metric = resp.getSepticShockBundleCompletionRate();
        assertThat(metric.getId().getImplemented()).isTrue();
        assertThat(metric.getMonthItemList()).hasSize(2);
        assertMetric(metric.getMonthItem(0), 1, 2, 0.5);
        assertMetric(metric.getMonthItem(1), 0, 1, 0.0);
        assertMetric(metric.getTotalItem(), 1, 3, 1.0 / 3.0);

        IcuQcSepticShockBundleCompletionRateItemPB janItem = metric.getMonthItem(0);
        assertThat(janItem.getDetailList()).hasSize(2);
        assertThat(janItem.getDetail(0).getDisplayBedNumber()).isEqualTo("B1");
        assertThat(janItem.getDetail(0).getPatient().getPatientName()).isEqualTo("patient-900001");
        assertThat(janItem.getDetail(0).getPatient().getAdmissionTimeIso8601()).isEqualTo("2025-01-10T08:00Z");
        assertThat(janItem.getDetail(0).getBundleCompleted()).isFalse();
        assertThat(janItem.getDetail(0).getH1Completed()).isFalse();
        assertThat(janItem.getDetail(0).getH3Completed()).isTrue();
        assertThat(janItem.getDetail(0).getH6Completed()).isTrue();
        assertThat(janItem.getDetail(1).getDisplayBedNumber()).isEqualTo("B2");
        assertThat(janItem.getDetail(1).getBundleCompleted()).isTrue();
        assertThat(janItem.getDetail(1).getIsInNumerator()).isTrue();

        verify(septicShockBundleService).buildSepticShockCasesReadOnly(List.of(
            900000L, 900001L, 900002L, 900003L, 900004L, 900005L));
        verify(septicShockBundleService, never()).buildSepticShockCases(any());
    }

    private Config testConfig() {
        return Config.newBuilder()
            .setZoneId("UTC")
            .setText(Text.newBuilder()
                .addAllStatusCodeMsg(Collections.nCopies(300, "status"))
                .build())
            .build();
    }

    private IcuQcConfigPB testQcConfig() {
        return IcuQcConfigPB.newBuilder()
            .addItem(QcItemPB.newBuilder()
                .setCode(Consts.ICU_5_SEPTIC_SHOCK_BUNDLE_COMPLETION_RATE)
                .setName("感染性休克患者集束化治疗（bundle）完成率")
                .setCalcFormula("bundle完成率")
                .build())
            .addStatsDeptId("99999")
            .setSepsisSepticShockDiagnosis(SepsisSepticShockDiagnosisConfigPB.newBuilder()
                .setAlarmFilter(SepsisSepticShockAlarmFilterPB.SEPSIS_SEPTIC_SHOCK_ALARM_FILTER_UNLIMITED)
                .build())
            .build();
    }

    private Department dept(String deptId) {
        Department dept = new Department();
        dept.setDeptId(deptId);
        dept.setName("ICU");
        dept.setAbbreviation("ICU");
        dept.setHospitalName("hospital");
        dept.setIsDeleted(false);
        return dept;
    }

    private PatientRecord patient(long pid, String deptId, LocalDateTime admissionTime) {
        PatientRecord patient = new PatientRecord();
        patient.setId(pid);
        patient.setDeptId(deptId);
        patient.setHisMrn("MRN-" + pid);
        patient.setIcuName("patient-" + pid);
        patient.setHisBedNumber("H-" + pid);
        patient.setAdmissionTime(admissionTime);
        return patient;
    }

    private BedConfig bedConfig(String deptId, String hisBedNumber, String displayBedNumber) {
        BedConfig bedConfig = new BedConfig();
        bedConfig.setDepartmentId(deptId);
        bedConfig.setHisBedNumber(hisBedNumber);
        bedConfig.setDisplayBedNumber(displayBedNumber);
        return bedConfig;
    }

    private SepsisAndSepticShockCasePB septicShockCase(long pid, SepsisAndSepticShockBundlePB bundle) {
        return SepsisAndSepticShockCasePB.newBuilder()
            .setBundle(bundle.toBuilder().setPid(pid))
            .build();
    }

    private SepsisAndSepticShockBundlePB h1UnfinishedBundle(long pid) {
        return SepsisAndSepticShockBundlePB.newBuilder()
            .setPid(pid)
            .setNeedBundle(true)
            .setH1LactateInitial(false)
            .setH1CultureBeforeAbx(true)
            .setH1AbxBroad(true)
            .setFluidQualified(true)
            .setPerfusionReassessmentDetails(PerfusionReassessmentPB.newBuilder()
                .setAssessmentTimeIso8601("2025-01-10T12:00:00Z")
                .build())
            .build();
    }

    private SepsisAndSepticShockBundlePB h1CompleteBundle(long pid) {
        return SepsisAndSepticShockBundlePB.newBuilder()
            .setPid(pid)
            .setNeedBundle(true)
            .setH1LactateInitial(true)
            .setH1CultureBeforeAbx(true)
            .setH1AbxBroad(true)
            .setFluidQualified(true)
            .setPerfusionReassessmentDetails(PerfusionReassessmentPB.newBuilder()
                .setAssessmentTimeIso8601("2025-01-20T12:00:00Z")
                .build())
            .build();
    }

    private void assertMetric(IcuQcSepticShockBundleCompletionRateItemPB item, double numerator, double denominator, double ratio) {
        assertThat(item.getNumerator()).isEqualTo(numerator);
        assertThat(item.getDenominator()).isEqualTo(denominator);
        assertThat(item.getRatio()).isEqualTo(ratio);
    }
}
