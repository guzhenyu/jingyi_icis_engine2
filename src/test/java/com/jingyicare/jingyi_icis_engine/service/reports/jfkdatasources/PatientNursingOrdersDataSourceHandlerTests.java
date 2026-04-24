package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository.NursingExecutionRecordReportRow;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class PatientNursingOrdersDataSourceHandlerTests {
    @Test
    public void handleReturnsNursingExecutionRows() {
        TestContext ctx = new TestContext();
        NursingExecutionRecord record1 = executionRecord(
            21L, LocalDateTime.of(2026, 4, 17, 0, 20), "101", ""
        );
        NursingExecutionRecord record2 = executionRecord(
            22L, LocalDateTime.of(2026, 4, 17, 0, 30), "102", "痰液较多"
        );
        NursingExecutionRecordReportRow reportRow2 = executionReportRow(record2, "吸痰护理");
        NursingExecutionRecordReportRow reportRow1 = executionReportRow(record1, "翻身护理");
        when(ctx.nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(reportRow2, reportRow1));
        when(ctx.accountRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(
            account(101L, "张护士", SIGNATURE_PNG),
            account(102L, "李护士", "")
        ));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).containsExactly(
            List.of("2026-04-17 08:20"),
            List.of("2026-04-17 08:30")
        );
        assertThat(output.get("content")).containsExactly(
            List.of("翻身护理"),
            List.of("吸痰护理(备注: 痰液较多)")
        );
        assertThat(output.get("recorded_by")).containsExactly(
            List.of(SIGNATURE_PNG),
            List.of("李护士")
        );
    }

    private static JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_ORDERS, "table-260-3"))
            .setMetaId(JfkDataSourceIds.PATIENT_NURSING_ORDERS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-17T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-18T00:00Z"))
            .addInputData(strInput("table_id", "table-260-3"))
            .addInputData(doubleArrayInput("col_widths", List.of(66d, 683.5d, 60d)))
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
        private final NursingExecutionRecordRepository nursingExecutionRecordRepo =
            mock(NursingExecutionRecordRepository.class);
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
            when(nursingExecutionRecordRepo.findReportNursingExecutionRecords(10001L, MON_START_UTC, MON_END_UTC))
                .thenReturn(List.of());
        }

        private PatientNursingOrdersDataSourceHandler handler() {
            ReportProperties properties = new ReportProperties();
            properties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            return new PatientNursingOrdersDataSourceHandler(
                support,
                monitoringWindowResolver,
                nursingExecutionRecordRepo,
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
