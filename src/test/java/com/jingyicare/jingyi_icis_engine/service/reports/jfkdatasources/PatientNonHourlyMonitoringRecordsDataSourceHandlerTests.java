package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
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
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class PatientNonHourlyMonitoringRecordsDataSourceHandlerTests {
    @Test
    public void handleMergesNonHourlyRowsAndUsesLatestModifiedRecordForRecordedBy() {
        TestContext ctx = new TestContext();
        when(ctx.monitoringRecordRepo.findByPidAndEffectiveTimeRange(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(
                monitoringRecord(
                    1L, "temperature", LocalDateTime.of(2026, 4, 17, 0, 10),
                    "36.8", "", "101", LocalDateTime.of(2026, 4, 17, 0, 11)
                ),
                monitoringRecord(
                    2L, "hr", LocalDateTime.of(2026, 4, 17, 0, 10),
                    "", encodedIntValue(88), "102", LocalDateTime.of(2026, 4, 17, 0, 12)
                ),
                monitoringRecord(
                    3L, "temperature", LocalDateTime.of(2026, 4, 17, 1, 0),
                    "ignored", "", "103", LocalDateTime.of(2026, 4, 17, 1, 1)
                ),
                monitoringRecord(
                    4L, "missing", LocalDateTime.of(2026, 4, 17, 0, 10),
                    "ignored", "", "102", LocalDateTime.of(2026, 4, 17, 0, 13)
                )
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
        assertThat(output.get("record_time")).containsExactly(List.of("2026-04-17 08:10"));
        assertThat(output.get("content")).containsExactly(List.of("体温: 36.8 ℃; 心率: 88 次/分"));
        assertThat(output.get("recorded_by")).containsExactly(List.of(SIGNATURE_PNG));
    }

    private static JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(
                JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS, "table-40-2"))
            .setMetaId(JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-17T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-18T00:00Z"))
            .addInputData(strInput("table_id", "table-40-2"))
            .addInputData(doubleArrayInput("col_widths", List.of(66d, 683.5d, 60d)))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static PatientMonitoringRecord monitoringRecord(
        Long id,
        String paramCode,
        LocalDateTime effectiveTime,
        String paramValueStr,
        String paramValue,
        String modifiedBy,
        LocalDateTime modifiedAt
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
            .modifiedAt(modifiedAt)
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

        private PatientNonHourlyMonitoringRecordsDataSourceHandler handler() {
            ReportProperties properties = new ReportProperties();
            properties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            return new PatientNonHourlyMonitoringRecordsDataSourceHandler(
                support,
                monitoringWindowResolver,
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
