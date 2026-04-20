package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class PatientMonitoringRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsTwentyFourHourRowsAndSkipsUnconfiguredParams() {
        JfkDataSourceSupport support = mockSupport();
        PatientMonitoringRecordRepository recordRepo = mock(PatientMonitoringRecordRepository.class);
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MonitoringConfig monitoringConfig = mock(MonitoringConfig.class);
        PatientMonitoringRecordsDataSourceHandler handler = new PatientMonitoringRecordsDataSourceHandler(
            support,
            recordRepo,
            balanceStatsShiftRepo,
            monitoringConfig,
            new ReportProperties(),
            new DefaultResourceLoader()
        );

        LocalDateTime utcEnd = LocalDateTime.of(2026, 4, 17, 0, 0);
        when(balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept", utcEnd))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("patient-dept")
                .startHour(6)
                .monStartHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        when(monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of(
            "temperature", param("temperature", "体温", valueMeta(TypeEnumPB.FLOAT, "℃")),
            "pr", param("pr", "脉搏", valueMeta(TypeEnumPB.INT32, "次/分"))
        ));

        LocalDateTime monStart = LocalDateTime.of(2026, 4, 16, 7, 0);
        when(recordRepo.findByPidAndParamCodesAndEffectiveTimeRange(
            10001L, List.of("temperature", "pr"), monStart, monStart.plusHours(24)))
            .thenReturn(List.of(
                record("temperature", monStart, "36.5", null, LocalDateTime.of(2026, 4, 16, 7, 5)),
                record("temperature", monStart, "36.7", null, LocalDateTime.of(2026, 4, 16, 7, 10)),
                record("temperature", monStart.plusMinutes(30), "ignored", null, LocalDateTime.of(2026, 4, 16, 7, 30)),
                record("pr", monStart.plusHours(1), "", encodedIntValue(88), LocalDateTime.of(2026, 4, 16, 8, 5))
            ));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, "table-28"))
            .setMetaId(JfkDataSourceIds.PATIENT_MONITORING_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("table_id", "table-28"))
            .addInputData(strArrayInput("monitoring_param_codes", List.of("temperature", "missing", "pr")))
            .addInputData(doubleArrayInput("col_widths", Collections.nCopies(26, 40d)))
            .addInputData(doubleInput("font_size", 8d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("param_name")).containsExactly(List.of("体温"), List.of("脉搏"));
        assertThat(output.get("param_unit")).containsExactly(List.of("℃"), List.of("次/分"));
        assertThat(output.get("hour1")).containsExactly(List.of("36.7"), List.of(""));
        assertThat(output.get("hour2")).containsExactly(List.of(""), List.of("88"));
        assertThat(output.get("hour24")).hasSize(2);

        verify(recordRepo).findByPidAndParamCodesAndEffectiveTimeRange(
            10001L, List.of("temperature", "pr"), monStart, monStart.plusHours(24));
    }

    @Test
    public void handleFiltersParamsWithoutRecordsWhenConfigured() {
        JfkDataSourceSupport support = mockSupport();
        PatientMonitoringRecordRepository recordRepo = mock(PatientMonitoringRecordRepository.class);
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MonitoringConfig monitoringConfig = mock(MonitoringConfig.class);
        ReportProperties reportProperties = new ReportProperties();
        reportProperties.getCompact().getPatientMonitoringRecords().setFilterEmptyParams(true);
        PatientMonitoringRecordsDataSourceHandler handler = new PatientMonitoringRecordsDataSourceHandler(
            support,
            recordRepo,
            balanceStatsShiftRepo,
            monitoringConfig,
            reportProperties,
            new DefaultResourceLoader()
        );

        LocalDateTime utcEnd = LocalDateTime.of(2026, 4, 17, 0, 0);
        when(balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept", utcEnd))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("patient-dept")
                .startHour(6)
                .monStartHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        when(monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of(
            "temperature", param("temperature", "体温", valueMeta(TypeEnumPB.FLOAT, "℃")),
            "pr", param("pr", "脉搏", valueMeta(TypeEnumPB.INT32, "次/分"))
        ));

        LocalDateTime monStart = LocalDateTime.of(2026, 4, 16, 7, 0);
        when(recordRepo.findByPidAndParamCodesAndEffectiveTimeRange(
            10001L, List.of("temperature", "pr"), monStart, monStart.plusHours(24)))
            .thenReturn(List.of(
                record("pr", monStart.plusHours(1), "88", null, LocalDateTime.of(2026, 4, 16, 8, 5))
            ));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, "table-28"))
            .setMetaId(JfkDataSourceIds.PATIENT_MONITORING_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "patient-dept"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("table_id", "table-28"))
            .addInputData(strArrayInput("monitoring_param_codes", List.of("temperature", "pr")))
            .addInputData(doubleArrayInput("col_widths", Collections.nCopies(26, 40d)))
            .addInputData(doubleInput("font_size", 8d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("param_name")).containsExactly(List.of("脉搏"));
        assertThat(output.get("param_unit")).containsExactly(List.of("次/分"));
        assertThat(output.get("hour1")).containsExactly(List.of(""));
        assertThat(output.get("hour2")).containsExactly(List.of("88"));
        assertThat(output.get("hour24")).hasSize(1);
    }

    private JfkDataSourceSupport mockSupport() {
        PatientService patientService = mock(PatientService.class);
        PatientRecord patient = new PatientRecord();
        patient.setId(10001L);
        patient.setDeptId("patient-dept");
        when(patientService.getPatientRecord(10001L)).thenReturn(patient);

        JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
        when(support.getZoneId()).thenReturn("UTC");
        when(support.getPatientService()).thenReturn(patientService);
        when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
            JfkDataSourcePB input = invocation.getArgument(0);
            return JfkDataSourcePB.newBuilder()
                .setId(input.getId())
                .setMetaId(input.getMetaId())
                .addAllInputData(input.getInputDataList());
        });
        return support;
    }

    private MonitoringParamPB param(String code, String name, ValueMetaPB valueMeta) {
        return MonitoringParamPB.newBuilder()
            .setCode(code)
            .setName(name)
            .setValueMeta(valueMeta)
            .build();
    }

    private ValueMetaPB valueMeta(TypeEnumPB type, String unit) {
        return ValueMetaPB.newBuilder()
            .setValueType(type)
            .setUnit(unit)
            .build();
    }

    private PatientMonitoringRecord record(
        String paramCode,
        LocalDateTime effectiveTime,
        String paramValueStr,
        String paramValue,
        LocalDateTime modifiedAt
    ) {
        return PatientMonitoringRecord.builder()
            .pid(10001L)
            .deptId("patient-dept")
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValueStr(paramValueStr)
            .paramValue(paramValue == null ? "" : paramValue)
            .source("")
            .modifiedAt(modifiedAt)
            .isDeleted(false)
            .build();
    }

    private String encodedIntValue(int value) {
        return ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
            .setValue(GenericValuePB.newBuilder().setInt32Val(value).build())
            .build());
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

    private JfkFieldDataPB doubleInput(String id, double value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setDoubleVal(value).build())
            .build();
    }

    private JfkFieldDataPB strArrayInput(String id, List<String> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setStrVal(value).build())
                .toList())
            .build();
    }

    private JfkFieldDataPB doubleArrayInput(String id, List<Double> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setDoubleVal(value).build())
                .toList())
            .build();
    }

    private Map<String, List<List<String>>> toOutputMap(JfkDataSourcePB output) {
        return output.getOutputDataList().stream()
            .collect(Collectors.toMap(
                JfkFieldDataPB::getId,
                field -> field.getValsList().stream()
                    .map(val -> List.copyOf(val.getStrsValList()))
                    .toList()
            ));
    }
}
