package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringEnums;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository.NursingExecutionRecordReportRow;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class PatientNursingRecordsDataSourceHandlerTests {
    @Test
    public void handleMergesNursingExecutionAndNonHourlyMonitoringRows() {
        TestContext ctx = new TestContext();
        NursingRecord nursingRecord = NursingRecord.builder()
            .id(10L)
            .patientId(10001L)
            .effectiveTime(LocalDateTime.of(2026, 4, 17, 0, 10))
            .content("护理第一行\n护理第二行")
            .createdBy("101")
            .createdByAccountName("张护士")
            .reviewedBy("102")
            .reviewedByAccountName("李护士")
            .isDeleted(false)
            .build();
        when(ctx.nursingRecordRepo.findReportNursingRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(nursingRecord));
        NursingExecutionRecord executionRecord = executionRecord(
            20L, LocalDateTime.of(2026, 4, 17, 0, 10), "101", "痰液较多");
        NursingExecutionRecordReportRow executionReportRow = executionReportRow(executionRecord, "吸痰护理");
        when(ctx.nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(executionReportRow));
        when(ctx.monitoringRecordRepo.findByPidAndEffectiveTimeRange(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(
                monitoringRecord(2L, "hr", LocalDateTime.of(2026, 4, 17, 0, 10), "", encodedIntValue(88), "102"),
                monitoringRecord(1L, "temperature", LocalDateTime.of(2026, 4, 17, 0, 10), "36.8", "", "101"),
                monitoringRecord(3L, "temperature", LocalDateTime.of(2026, 4, 17, 1, 0), "ignored", "", "103"),
                monitoringRecord(4L, "missing", LocalDateTime.of(2026, 4, 17, 0, 10), "ignored", "", "104")
            ));
        when(ctx.accountRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(
            account(101L, "体温记录人", SIGNATURE_PNG),
            account(102L, "心率记录人", SIGNATURE_PNG)
        ));
        when(ctx.monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of(
            "temperature", param("temperature", "体温", 10, valueMeta(TypeEnumPB.FLOAT, "℃")),
            "hr", param("hr", "心率", 20, valueMeta(TypeEnumPB.INT32, "次/分"))
        ));
        when(ctx.monitoringConfig.getMonitoringGroups(any(), any(), anyInt(), any(), any())).thenReturn(List.of(
            MonitoringGroupBetaPB.newBuilder()
                .setId(1)
                .setDisplayOrder(1)
                .addParam(param("temperature", "体温", 1, valueMeta(TypeEnumPB.FLOAT, "℃")))
                .addParam(param("hr", "心率", 2, valueMeta(TypeEnumPB.INT32, "次/分")))
                .build()
        ));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).containsExactly(
            List.of("2026-04-17 08:10"),
            List.of("2026-04-17 08:10"),
            List.of("2026-04-17 08:10")
        );
        assertThat(output.get("content")).containsExactly(
            List.of("护理第一行", "护理第二行"),
            List.of("吸痰护理(备注: 痰液较多)"),
            List.of("体温: 36.8 ℃; 心率: 88 次/分")
        );
        assertThat(output.get("recorded_by")).containsExactly(
            List.of(SIGNATURE_PNG),
            List.of(SIGNATURE_PNG),
            List.of(SIGNATURE_PNG)
        );
        assertThat(output.get("reviewed_by")).containsExactly(List.of(SIGNATURE_PNG), List.of(""), List.of(""));

        verify(ctx.nursingRecordRepo).findReportNursingRecords(10001L, MON_START_UTC, MON_END_UTC);
        verify(ctx.nursingExecutionRecordRepo).findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC);
        verify(ctx.monitoringRecordRepo).findByPidAndEffectiveTimeRange(10001L, MON_START_UTC, MON_END_UTC);
    }

    @Test
    public void handleUsesOrderNameOnlyWhenNursingExecutionNoteIsEmpty() {
        TestContext ctx = new TestContext();
        NursingExecutionRecord executionRecord = executionRecord(
            21L, LocalDateTime.of(2026, 4, 17, 0, 30), "103", "");
        NursingExecutionRecordReportRow executionReportRow = executionReportRow(executionRecord, "吸痰护理");
        when(ctx.nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(executionReportRow));
        when(ctx.accountRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(account(103L, "王护士", "")));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).containsExactly(List.of("2026-04-17 08:30"));
        assertThat(output.get("content")).containsExactly(List.of("吸痰护理"));
        assertThat(output.get("recorded_by")).containsExactly(List.of("王护士"));
        assertThat(output.get("reviewed_by")).containsExactly(List.of(""));
    }

    @Test
    public void handleReturnsEmptyRowsWhenNoRecordsExist() {
        TestContext ctx = new TestContext();
        when(ctx.nursingRecordRepo.findReportNursingRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of());
        when(ctx.monitoringRecordRepo.findByPidAndEffectiveTimeRange(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of());
        when(ctx.nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).isEmpty();
        assertThat(output.get("content")).isEmpty();
        assertThat(output.get("recorded_by")).isEmpty();
        assertThat(output.get("reviewed_by")).isEmpty();
    }

    private static JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_RECORDS, "table-256"))
            .setMetaId(JfkDataSourceIds.PATIENT_NURSING_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-17T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-18T00:00Z"))
            .addInputData(strInput("table_id", "table-256"))
            .addInputData(doubleArrayInput("col_widths", List.of(300d, 1000d, 300d, 300d)))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static NursingExecutionRecord executionRecord(
        Long id,
        LocalDateTime completedTime,
        String completedBy,
        String note
    ) {
        return NursingExecutionRecord.builder()
            .id(id)
            .pid(10001L)
            .nursingOrderId(1000L + id)
            .planTime(completedTime.minusMinutes(10))
            .completedBy(completedBy)
            .completedTime(completedTime)
            .note(note)
            .isDeleted(false)
            .build();
    }

    private static NursingExecutionRecordReportRow executionReportRow(
        NursingExecutionRecord executionRecord,
        String orderName
    ) {
        NursingExecutionRecordReportRow row = mock(NursingExecutionRecordReportRow.class);
        when(row.getExecutionRecord()).thenReturn(executionRecord);
        when(row.getOrderName()).thenReturn(orderName);
        return row;
    }

    private static PatientMonitoringRecord monitoringRecord(
        Long id,
        String paramCode,
        LocalDateTime effectiveTime,
        String paramValueStr,
        String paramValue,
        String modifiedBy
    ) {
        return PatientMonitoringRecord.builder()
            .id(id)
            .pid(10001L)
            .deptId("patient-dept")
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValueStr(paramValueStr)
            .paramValue(paramValue)
            .source("")
            .modifiedBy(modifiedBy)
            .modifiedAt(effectiveTime)
            .isDeleted(false)
            .build();
    }

    private static MonitoringParamPB param(String code, String name, int displayOrder, ValueMetaPB valueMeta) {
        return MonitoringParamPB.newBuilder()
            .setCode(code)
            .setName(name)
            .setDisplayOrder(displayOrder)
            .setValueMeta(valueMeta)
            .build();
    }

    private static ValueMetaPB valueMeta(TypeEnumPB type, String unit) {
        return ValueMetaPB.newBuilder()
            .setValueType(type)
            .setUnit(unit)
            .build();
    }

    private static String encodedIntValue(int value) {
        return ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
            .setValue(GenericValuePB.newBuilder().setInt32Val(value).build())
            .build());
    }

    private static Account account(Long id, String name, String signPic) {
        Account account = new Account();
        account.setId(id);
        account.setAccountId("account-" + id);
        account.setName(name);
        account.setSignPic(signPic);
        account.setIsDeleted(false);
        return account;
    }

    private static JfkFieldDataPB strInput(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value).build())
            .build();
    }

    private static JfkFieldDataPB int64Input(String id, long value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setInt64Val(value).build())
            .build();
    }

    private static JfkFieldDataPB doubleInput(String id, double value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setDoubleVal(value).build())
            .build();
    }

    private static JfkFieldDataPB doubleArrayInput(String id, List<Double> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setDoubleVal(value).build())
                .toList())
            .build();
    }

    private static Map<String, List<List<String>>> toOutputMap(JfkDataSourcePB output) {
        return output.getOutputDataList().stream()
            .collect(Collectors.toMap(
                JfkFieldDataPB::getId,
                field -> field.getValsList().stream()
                    .map(val -> val.getStrsValCount() > 0
                        ? List.copyOf(val.getStrsValList())
                        : List.of(val.getStrVal()))
                    .toList()
            ));
    }

    private static class TestContext {
        private final JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        private final MonitoringWindowResolver monitoringWindowResolver = mock(MonitoringWindowResolver.class);
        private final NursingRecordRepository nursingRecordRepo = mock(NursingRecordRepository.class);
        private final NursingExecutionRecordRepository nursingExecutionRecordRepo =
            mock(NursingExecutionRecordRepository.class);
        private final PatientMonitoringRecordRepository monitoringRecordRepo = mock(PatientMonitoringRecordRepository.class);
        private final MonitoringConfig monitoringConfig = mock(MonitoringConfig.class);
        private final ConfigProtoService configProtoService = mock(ConfigProtoService.class);
        private final UserService userService = mock(UserService.class);
        private final AccountRepository accountRepo = mock(AccountRepository.class);

        private TestContext() {
            PatientRecord patient = new PatientRecord();
            patient.setId(10001L);
            patient.setDeptId("patient-dept");
            when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
            when(support.getZoneId()).thenReturn("Asia/Shanghai");
            when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
                JfkDataSourcePB input = invocation.getArgument(0);
                return JfkDataSourcePB.newBuilder()
                    .setId(input.getId())
                    .setMetaId(input.getMetaId())
                    .addAllInputData(input.getInputDataList());
            });
            when(monitoringWindowResolver.resolve(10001L, "2026-04-17T00:00Z"))
                .thenReturn(new Pair<>(
                    ReturnCode.newBuilder().setCode(StatusCode.OK.ordinal()).build(),
                    new MonitoringWindow(
                        patient, "patient-dept", MON_START_UTC, MON_END_UTC,
                        MON_START_LOCAL, MON_END_LOCAL, 7
                    )
                ));
            when(nursingRecordRepo.findReportNursingRecords(10001L, MON_START_UTC, MON_END_UTC))
                .thenReturn(List.of());
            when(nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
                .thenReturn(List.of());
            when(monitoringRecordRepo.findByPidAndEffectiveTimeRange(10001L, MON_START_UTC, MON_END_UTC))
                .thenReturn(List.of());
            when(configProtoService.getConfig()).thenReturn(Config.newBuilder()
                .setMonitoring(MonitoringPB.newBuilder()
                    .setEnums(MonitoringEnums.newBuilder()
                        .setGroupTypeMonitoring(EnumValue.newBuilder().setId(2).setName("观察项").build())
                        .build())
                    .build())
                .build());
            when(userService.getAccountWithAutoId()).thenReturn(new Pair<>("account-1", "当前用户"));
        }

        private PatientNursingRecordsDataSourceHandler handler() {
            ReportProperties properties = new ReportProperties();
            properties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            return new PatientNursingRecordsDataSourceHandler(
                support,
                monitoringWindowResolver,
                nursingRecordRepo,
                nursingExecutionRecordRepo,
                monitoringRecordRepo,
                monitoringConfig,
                configProtoService,
                userService,
                accountRepo,
                properties,
                new DefaultResourceLoader()
            );
        }
    }

    private static final LocalDateTime MON_START_UTC = LocalDateTime.of(2026, 4, 16, 23, 0);
    private static final LocalDateTime MON_END_UTC = LocalDateTime.of(2026, 4, 17, 23, 0);
    private static final LocalDateTime MON_START_LOCAL = LocalDateTime.of(2026, 4, 17, 7, 0);
    private static final LocalDateTime MON_END_LOCAL = LocalDateTime.of(2026, 4, 18, 7, 0);
    private static final String SIGNATURE_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
}
