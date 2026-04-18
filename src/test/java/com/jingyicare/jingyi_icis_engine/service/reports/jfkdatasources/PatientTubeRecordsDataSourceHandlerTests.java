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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeRecord;
import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeStatusRecord;
import com.jingyicare.jingyi_icis_engine.entity.tubes.TubeTypeStatus;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.tubes.PatientTubeRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.PatientTubeStatusRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.TubeTypeStatusRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class PatientTubeRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsTubeRowsWithRootDurationAndShiftStatus() {
        TestContext ctx = new TestContext(true);

        PatientTubeRecord current = tubeRecord(
            20L, 10L,
            LocalDateTime.of(2026, 4, 17, 0, 0),
            null
        );
        PatientTubeRecord root = tubeRecord(
            10L, 10L,
            LocalDateTime.of(2026, 4, 16, 22, 40),
            null
        );
        ctx.withTubeRecords(List.of(current));
        when(ctx.tubeRecordRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(root));
        when(ctx.tubeRecordRepo.findByRootTubeRecordIdInAndIsDeletedFalse(any())).thenReturn(List.of(root, current));
        ctx.withStatusRecords(List.of(
            statusRecord(104L, 20L, 3, LocalDateTime.of(2026, 4, 17, 8, 38), "顺畅"),
            statusRecord(101L, 20L, 2, LocalDateTime.of(2026, 4, 17, 0, 10), "24cm"),
            statusRecord(102L, 20L, 1, LocalDateTime.of(2026, 4, 17, 0, 10), "正常"),
            statusRecord(103L, 20L, 3, LocalDateTime.of(2026, 4, 17, 2, 10), "较晚记录")
        ));
        ctx.withStatusMetas(
            statusMeta(1, "部位情况", 1),
            statusMeta(2, "置入长度", 2),
            statusMeta(3, "导管护理", 3)
        );

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("tube_name")).containsExactly(List.of("胃管"));
        assertThat(output.get("inserted_at")).containsExactly(List.of("2026-04-17 06:40"));
        assertThat(output.get("duration_days")).containsExactly(List.of("2天"));
        assertThat(output.get("maintenance_status")).containsExactly(List.of(
            "A班 2026-04-17 08:10 部位情况: 正常, 置入长度: 24cm",
            "P班 2026-04-17 16:38 导管护理: 顺畅"
        ));
        verify(ctx.tubeRecordRepo).findReportTubeRecords(10001L, MON_START_UTC, MON_END_UTC);
    }

    @Test
    public void handleUsesLatestStatusGroupWhenConfigured() {
        TestContext ctx = new TestContext(false);

        PatientTubeRecord current = tubeRecord(
            20L, 20L,
            LocalDateTime.of(2026, 4, 16, 23, 30),
            LocalDateTime.of(2026, 4, 17, 3, 0)
        );
        ctx.withTubeRecords(List.of(current));
        ctx.withStatusRecords(List.of(
            statusRecord(101L, 20L, 1, LocalDateTime.of(2026, 4, 17, 0, 10), "早"),
            statusRecord(102L, 20L, 1, LocalDateTime.of(2026, 4, 17, 2, 10), "晚")
        ));
        ctx.withStatusMetas(statusMeta(1, "部位情况", 1));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("inserted_at")).containsExactly(List.of("2026-04-17 07:30 ~ 2026-04-17 11:00"));
        assertThat(output.get("duration_days")).containsExactly(List.of("1天"));
        assertThat(output.get("maintenance_status")).containsExactly(List.of(
            "A班 2026-04-17 10:10 部位情况: 晚"
        ));
    }

    @Test
    public void handleCalculatesDurationDaysAtWindowStart() {
        TestContext ctx = new TestContext(true);

        PatientTubeRecord current = tubeRecord(
            20L, 20L,
            LocalDateTime.of(2026, 4, 13, 6, 36),
            null
        );
        ctx.withTubeRecords(List.of(current));
        ctx.withStatusRecords(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("inserted_at")).containsExactly(List.of("2026-04-13 14:36"));
        assertThat(output.get("duration_days")).containsExactly(List.of("5天"));
    }

    @Test
    public void handleReturnsEmptyRowsWhenNoTubeRecords() {
        TestContext ctx = new TestContext(true);
        ctx.withTubeRecords(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("tube_name")).isEmpty();
        assertThat(output.get("inserted_at")).isEmpty();
        assertThat(output.get("duration_days")).isEmpty();
        assertThat(output.get("maintenance_status")).isEmpty();
    }

    @Test
    public void handleReturnsNoShiftSettingWhenShiftIsMissing() {
        TestContext ctx = new TestContext(true);
        ctx.withTubeRecords(List.of(tubeRecord(
            20L, 20L,
            LocalDateTime.of(2026, 4, 16, 23, 30),
            null
        )));
        when(ctx.configShiftUtils.getShiftByDeptId("patient-dept")).thenReturn(ShiftSettingsPB.getDefaultInstance());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.NO_SHIFT_SETTING.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    private static JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_TUBE_RECORDS, "table-253"))
            .setMetaId(JfkDataSourceIds.PATIENT_TUBE_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-17T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-18T00:00Z"))
            .addInputData(strInput("table_id", "table-253"))
            .addInputData(doubleArrayInput("col_widths", List.of(300d, 200d, 120d, 1000d)))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static PatientTubeRecord tubeRecord(
        Long id,
        Long rootId,
        LocalDateTime insertedAt,
        LocalDateTime removedAt
    ) {
        return PatientTubeRecord.builder()
            .id(id)
            .pid(10001L)
            .tubeTypeId(1)
            .tubeName("胃管")
            .insertedAt(insertedAt)
            .removedAt(removedAt)
            .rootTubeRecordId(rootId)
            .isDeleted(false)
            .build();
    }

    private static PatientTubeStatusRecord statusRecord(
        Long id,
        Long recordId,
        Integer statusId,
        LocalDateTime recordedAt,
        String value
    ) {
        return PatientTubeStatusRecord.builder()
            .id(id)
            .patientTubeRecordId(recordId)
            .tubeStatusId(statusId)
            .recordedAt(recordedAt)
            .value(value)
            .isDeleted(false)
            .build();
    }

    private static TubeTypeStatus statusMeta(Integer id, String name, Integer displayOrder) {
        return TubeTypeStatus.builder()
            .id(id)
            .tubeTypeId(1)
            .name(name)
            .displayOrder(displayOrder)
            .isDeleted(false)
            .build();
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
                    .map(val -> List.copyOf(val.getStrsValList()))
                    .toList()
            ));
    }

    private static class TestContext {
        private final JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        private final MonitoringWindowResolver monitoringWindowResolver = mock(MonitoringWindowResolver.class);
        private final PatientTubeRecordRepository tubeRecordRepo = mock(PatientTubeRecordRepository.class);
        private final PatientTubeStatusRecordRepository tubeStatusRecordRepo = mock(PatientTubeStatusRecordRepository.class);
        private final TubeTypeStatusRepository tubeTypeStatusRepo = mock(TubeTypeStatusRepository.class);
        private final ConfigShiftUtils configShiftUtils = mock(ConfigShiftUtils.class);

        private TestContext(boolean firstRecordInShift) {
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
            when(configShiftUtils.getShiftByDeptId("patient-dept")).thenReturn(shiftSettings());
            this.firstRecordInShift = firstRecordInShift;
        }

        private PatientTubeRecordsDataSourceHandler handler() {
            ReportProperties properties = new ReportProperties();
            properties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            properties.getCompact().getTubePolicy().setFirstRecordInShift(firstRecordInShift);
            return new PatientTubeRecordsDataSourceHandler(
                support,
                monitoringWindowResolver,
                tubeRecordRepo,
                tubeStatusRecordRepo,
                tubeTypeStatusRepo,
                configShiftUtils,
                properties,
                new DefaultResourceLoader()
            );
        }

        private void withTubeRecords(List<PatientTubeRecord> records) {
            when(tubeRecordRepo.findReportTubeRecords(10001L, MON_START_UTC, MON_END_UTC)).thenReturn(records);
            when(tubeRecordRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of());
            when(tubeRecordRepo.findByRootTubeRecordIdInAndIsDeletedFalse(any())).thenReturn(List.of());
        }

        private void withStatusRecords(List<PatientTubeStatusRecord> records) {
            when(tubeStatusRecordRepo.findReportStatusRecords(any(), any(), any())).thenReturn(records);
        }

        private void withStatusMetas(TubeTypeStatus... statuses) {
            when(tubeTypeStatusRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(statuses));
        }

        private final boolean firstRecordInShift;
    }

    private static ShiftSettingsPB shiftSettings() {
        return ShiftSettingsPB.newBuilder()
            .addShift(shift("全天", 0, 24))
            .addShift(shift("A班", 7, 8))
            .addShift(shift("P班", 15, 8))
            .addShift(shift("N班", 23, 8))
            .build();
    }

    private static ShiftSettingsPB.Shift shift(String name, int startHour, int hours) {
        return ShiftSettingsPB.Shift.newBuilder()
            .setName(name)
            .setDefaultShift(ShiftSettingsPB.TimeRange.newBuilder()
                .setStartHour(startHour)
                .setHours(hours)
                .build())
            .build();
    }

    private static final LocalDateTime MON_START_UTC = LocalDateTime.of(2026, 4, 16, 23, 0);
    private static final LocalDateTime MON_END_UTC = LocalDateTime.of(2026, 4, 17, 23, 0);
    private static final LocalDateTime MON_START_LOCAL = LocalDateTime.of(2026, 4, 17, 7, 0);
    private static final LocalDateTime MON_END_LOCAL = LocalDateTime.of(2026, 4, 18, 7, 0);
}
