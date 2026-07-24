package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.PatientShiftRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.PatientShiftRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class PatientInfoExtendedDataSourceHandlerTests {
    @Test
    public void handleBuildsExtendedPatientInfoAndDoesNotRequireQueryEnd() {
        TestContext ctx = new TestContext();
        PatientInfoExtendedDataSourceHandler handler = ctx.handler();

        PatientRecord patient = patient();
        when(ctx.patientService.getPatientRecord(10001L)).thenReturn(patient);
        when(ctx.deptRepo.findByDeptId("patient-dept"))
            .thenReturn(Optional.of(new RbacDepartment("patient-dept", "重症医学科")));

        LocalDateTime queryUtcEnd = LocalDateTime.of(2026, 4, 17, 0, 0);
        when(ctx.balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept", queryUtcEnd))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("patient-dept")
                .startHour(7)
                .monStartHour(null)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        LocalDateTime monStartUtc = LocalDateTime.of(2026, 4, 15, 23, 0);
        LocalDateTime monEndUtc = LocalDateTime.of(2026, 4, 16, 23, 0);
        when(ctx.support.getBedNumber(patient, monEndUtc)).thenReturn("12");

        when(ctx.surgeryHistoryRepo.findByPatientId(10001L)).thenReturn(List.of(
            surgery("手术A", LocalDateTime.of(2026, 4, 10, 23, 0)),
            surgery("手术B", LocalDateTime.of(2026, 4, 11, 23, 0))
        ));
        when(ctx.patientShiftRecordRepo.findByPidAndContainedInTimeRange(10001L, monStartUtc, monEndUtc))
            .thenReturn(List.of(
                shift("白班", "张护士", monStartUtc),
                shift("夜班", "李护士", monStartUtc.plusHours(8))
            ));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId(JfkDataSourceIds.PATIENT_INFO_EXTENDED)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, String> output = toOutputMap(result.getSecond());
        assertThat(output.get("dept_name")).isEqualTo("重症医学科");
        assertThat(output.get("bed_no")).isEqualTo("12");
        assertThat(output.get("patient_name")).isEqualTo("张三");
        assertThat(output.get("gender")).isEqualTo("男");
        assertThat(output.get("age")).isEqualTo("45岁");
        assertThat(output.get("mrn")).isEqualTo("MRN001");
        assertThat(output.get("diagnosis")).isEqualTo("ICU诊断");
        assertThat(output.get("height")).isEqualTo("179cm");
        assertThat(output.get("weight")).isEqualTo("70kg");
        assertThat(output.get("admission_time_yyyymmdd")).isEqualTo("20260324");
        assertThat(output.get("illness_severity_level")).isEqualTo("4");
        assertThat(output.get("days_after_surgery")).isEqualTo("5天");
        assertThat(output.get("mon_record_day_range")).isEqualTo("20260416-20260417");
        assertThat(output.get("diagnosis_and_surgery")).isEqualTo("ICU诊断; 手术: 手术B + 手术A");
        assertThat(output.get("patient_shift_info")).isEqualTo("白班: 张护士  夜班: 李护士");

        verify(ctx.balanceStatsShiftRepo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept", queryUtcEnd);
        verify(ctx.patientShiftRecordRepo).findByPidAndContainedInTimeRange(10001L, monStartUtc, monEndUtc);
    }

    @Test
    public void handleReturnsBalanceStatsShiftNotFoundWhenMonitoringWindowCannotBeResolved() {
        TestContext ctx = new TestContext();
        PatientInfoExtendedDataSourceHandler handler = ctx.handler();

        PatientRecord patient = patient();
        when(ctx.patientService.getPatientRecord(10001L)).thenReturn(patient);
        when(ctx.balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                eq("patient-dept"), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId(JfkDataSourceIds.PATIENT_INFO_EXTENDED)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleReturnsRawIllnessSeverityLevel() {
        TestContext ctx = new TestContext();
        PatientInfoExtendedDataSourceHandler handler = ctx.handler();

        PatientRecord patient = patient();
        patient.setIllnessSeverityLevel("99");
        when(ctx.patientService.getPatientRecord(10001L)).thenReturn(patient);
        when(ctx.deptRepo.findByDeptId("patient-dept"))
            .thenReturn(Optional.of(new RbacDepartment("patient-dept", "重症医学科")));
        when(ctx.balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                eq("patient-dept"), any(LocalDateTime.class)))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("patient-dept")
                .startHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));
        when(ctx.support.getBedNumber(eq(patient), any(LocalDateTime.class))).thenReturn("12");
        when(ctx.surgeryHistoryRepo.findByPatientId(10001L)).thenReturn(List.of(
            surgery("未来手术", LocalDateTime.of(2026, 4, 17, 1, 0))
        ));
        when(ctx.patientShiftRecordRepo.findByPidAndContainedInTimeRange(
            eq(10001L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId(JfkDataSourceIds.PATIENT_INFO_EXTENDED)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, String> output = toOutputMap(result.getSecond());
        assertThat(output.get("illness_severity_level")).isEqualTo("99");
        assertThat(output.get("days_after_surgery")).isEqualTo("0天");
    }

    private PatientRecord patient() {
        PatientRecord patient = new PatientRecord();
        patient.setId(10001L);
        patient.setDeptId("patient-dept");
        patient.setIcuName("张三");
        patient.setIcuGender(1);
        patient.setIcuDateOfBirth(LocalDateTime.of(1980, 4, 18, 0, 0));
        patient.setHisMrn("MRN001");
        patient.setDiagnosis("ICU诊断");
        patient.setHeight(179.4f);
        patient.setWeight(70.2f);
        patient.setAdmissionTime(LocalDateTime.of(2026, 3, 23, 16, 30));
        patient.setIllnessSeverityLevel("4");
        return patient;
    }

    private SurgeryHistory surgery(String name, LocalDateTime endTime) {
        return SurgeryHistory.builder()
            .patientId(10001L)
            .name(name)
            .endTime(endTime)
            .build();
    }

    private PatientShiftRecord shift(String shiftName, String nurseName, LocalDateTime shiftStart) {
        return PatientShiftRecord.builder()
            .pid(10001L)
            .shiftName(shiftName)
            .shiftNurseName(nurseName)
            .shiftStart(shiftStart)
            .shiftEnd(shiftStart.plusHours(8))
            .isDeleted(false)
            .build();
    }

    private JfkFieldDataPB strInput(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value).build())
            .build();
    }

    private JfkFieldDataPB int64Input(String id, long value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setInt64Val(value).build())
            .build();
    }

    private Map<String, String> toOutputMap(JfkDataSourcePB output) {
        return output.getOutputDataList().stream()
            .collect(Collectors.toMap(
                JfkFieldDataPB::getId,
                field -> field.getVal().getStrVal()
            ));
    }

    private class TestContext {
        private final JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        private final PatientService patientService = mock(PatientService.class);
        private final RbacDepartmentRepository deptRepo = mock(RbacDepartmentRepository.class);
        private final BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        private final SurgeryHistoryRepository surgeryHistoryRepo = mock(SurgeryHistoryRepository.class);
        private final PatientShiftRecordRepository patientShiftRecordRepo = mock(PatientShiftRecordRepository.class);
        private TestContext() {
            when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
            when(support.getZoneId()).thenReturn("Asia/Shanghai");
            when(support.getPatientService()).thenReturn(patientService);
            when(support.getDeptRepo()).thenReturn(deptRepo);
            when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
                JfkDataSourcePB input = invocation.getArgument(0);
                return JfkDataSourcePB.newBuilder()
                    .setId(input.getId())
                    .setMetaId(input.getMetaId())
                    .addAllInputData(input.getInputDataList());
            });
            doAnswer(invocation -> {
                JfkDataSourcePB.Builder builder = invocation.getArgument(0);
                String id = invocation.getArgument(1);
                String value = invocation.getArgument(2);
                builder.addOutputData(JfkFieldDataPB.newBuilder()
                    .setId(id)
                    .setVal(JfkValPB.newBuilder().setStrVal(value == null ? "" : value).build())
                    .build());
                return null;
            }).when(support).addStrOutput(any(JfkDataSourcePB.Builder.class), any(String.class), any(String.class));
        }

        private PatientInfoExtendedDataSourceHandler handler() {
            return new PatientInfoExtendedDataSourceHandler(
                support,
                new MonitoringWindowResolver(support, balanceStatsShiftRepo),
                surgeryHistoryRepo,
                patientShiftRecordRepo
            );
        }
    }
}
