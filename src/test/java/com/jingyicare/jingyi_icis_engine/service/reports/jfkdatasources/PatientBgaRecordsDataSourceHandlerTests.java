package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecord;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecordDetail;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.BgaConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.BgaEnums;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordDetailRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class PatientBgaRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsRowsFromPatientBgaRecords() {
        TestContext ctx = new TestContext();
        PatientBgaRecordsDataSourceHandler handler = ctx.handler();

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
        PatientBgaRecord record = record(101L, 9, "备用血气", LocalDateTime.of(2026, 4, 16, 0, 20));
        when(ctx.recordRepo
            .findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
                10001L, monStartUtc, monEndUtc))
            .thenReturn(List.of(record));
        when(ctx.detailRepo.findByRecordIdInAndIsDeletedFalse(List.of(101L))).thenReturn(List.of(
            detail(10L, 101L, "bga_ph", "7.35", ""),
            detail(11L, 101L, "bga_po2", "", encodedIntValue(86)),
            detail(12L, 101L, "missing", "ignored", "")
        ));
        when(ctx.bgaParamRepo.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder("patient-dept")).thenReturn(List.of(
            bgaParam("bga_po2", 1),
            bgaParam("bga_ph", 2)
        ));
        when(ctx.monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of(
            "bga_ph", param("bga_ph", "pH", valueMeta(TypeEnumPB.STRING)),
            "bga_po2", param("bga_po2", "氧分压", valueMeta(TypeEnumPB.INT32))
        ));
        when(ctx.accountRepo.findByIdInAndIsDeletedFalse(any())).thenReturn(List.of(
            account(101L, "张护士", SIGNATURE_PNG),
            account(102L, "李医生", SIGNATURE_PNG)
        ));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("date_str")).containsExactly(List.of("2026-04-16 8:20"));
        assertThat(output.get("category_name")).containsExactly(List.of("备用血气"));
        assertThat(output.get("bga_details")).containsExactly(List.of("氧分压: 86; pH: 7.35"));
        assertThat(output.get("recorded_by")).containsExactly(List.of(SIGNATURE_PNG));
        assertThat(output.get("reviewed_by")).containsExactly(List.of(SIGNATURE_PNG));

        verify(ctx.recordRepo)
            .findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
                10001L, monStartUtc, monEndUtc);
    }

    @Test
    public void handleReturnsEmptyColumnsWhenNoBgaRecordsExist() {
        TestContext ctx = new TestContext();
        PatientBgaRecordsDataSourceHandler handler = ctx.handler();

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
        when(ctx.recordRepo
            .findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
                eq(10001L), any(LocalDateTime.class), any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(ctx.bgaParamRepo.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder("patient-dept")).thenReturn(List.of());
        when(ctx.monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("date_str")).isEmpty();
        assertThat(output.get("category_name")).isEmpty();
        assertThat(output.get("bga_details")).isEmpty();
        assertThat(output.get("recorded_by")).isEmpty();
        assertThat(output.get("reviewed_by")).isEmpty();
    }

    private JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BGA_RECORDS, "table-38-2"))
            .setMetaId(JfkDataSourceIds.PATIENT_BGA_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("table_id", "table-38-2"))
            .addInputData(doubleArrayInput("col_widths", List.of(74d, 60d, 561.5d, 60d, 60d)))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private PatientBgaRecord record(Long id, Integer categoryId, String categoryName, LocalDateTime effectiveTime) {
        return PatientBgaRecord.builder()
            .id(id)
            .pid(10001L)
            .deptId("patient-dept")
            .bgaCategoryId(categoryId)
            .bgaCategoryName(categoryName)
            .effectiveTime(effectiveTime)
            .recordedBy("101")
            .recordedByAccountName("张护士")
            .reviewedBy("102")
            .reviewedByAccountName("李医生")
            .isDeleted(false)
            .modifiedAt(LocalDateTime.of(2026, 4, 16, 1, 0))
            .build();
    }

    private PatientBgaRecordDetail detail(Long id, Long recordId, String code, String valueStr, String value) {
        return PatientBgaRecordDetail.builder()
            .id(id)
            .recordId(recordId)
            .monitoringParamCode(code)
            .paramValueStr(valueStr)
            .paramValue(value)
            .isDeleted(false)
            .modifiedAt(LocalDateTime.of(2026, 4, 16, 1, 0))
            .build();
    }

    private BgaParam bgaParam(String code, Integer displayOrder) {
        return BgaParam.builder()
            .deptId("patient-dept")
            .monitoringParamCode(code)
            .displayOrder(displayOrder)
            .enabled(true)
            .isDeleted(false)
            .modifiedAt(LocalDateTime.of(2026, 4, 16, 1, 0))
            .build();
    }

    private MonitoringParamPB param(String code, String name, ValueMetaPB valueMeta) {
        return MonitoringParamPB.newBuilder()
            .setCode(code)
            .setName(name)
            .setValueMeta(valueMeta)
            .build();
    }

    private ValueMetaPB valueMeta(TypeEnumPB type) {
        return ValueMetaPB.newBuilder()
            .setValueType(type)
            .build();
    }

    private String encodedIntValue(int value) {
        return ProtoUtils.encodeGenericValue(GenericValuePB.newBuilder()
            .setInt32Val(value)
            .build());
    }

    private Account account(Long id, String name, String signPic) {
        Account account = new Account();
        account.setId(id);
        account.setAccountId("account-" + id);
        account.setName(name);
        account.setSignPic(signPic);
        account.setIsDeleted(false);
        return account;
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
                    .map(val -> val.getStrsValCount() > 0
                        ? List.copyOf(val.getStrsValList())
                        : List.of(val.getStrVal()))
                    .toList()
            ));
    }

    private class TestContext {
        private final JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        private final PatientService patientService = mock(PatientService.class);
        private final BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        private final PatientBgaRecordRepository recordRepo = mock(PatientBgaRecordRepository.class);
        private final PatientBgaRecordDetailRepository detailRepo = mock(PatientBgaRecordDetailRepository.class);
        private final BgaParamRepository bgaParamRepo = mock(BgaParamRepository.class);
        private final MonitoringConfig monitoringConfig = mock(MonitoringConfig.class);
        private final ConfigProtoService configProtoService = mock(ConfigProtoService.class);
        private final AccountRepository accountRepo = mock(AccountRepository.class);

        private TestContext() {
            PatientRecord patient = new PatientRecord();
            patient.setId(10001L);
            patient.setDeptId("patient-dept");
            when(patientService.getPatientRecord(10001L)).thenReturn(patient);

            when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
            when(support.getZoneId()).thenReturn("Asia/Shanghai");
            when(support.getPatientService()).thenReturn(patientService);
            when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
                JfkDataSourcePB input = invocation.getArgument(0);
                return JfkDataSourcePB.newBuilder()
                    .setId(input.getId())
                    .setMetaId(input.getMetaId())
                    .addAllInputData(input.getInputDataList());
            });
            when(configProtoService.getConfig()).thenReturn(Config.newBuilder()
                .setBga(BgaConfigPB.newBuilder()
                    .setEnums(BgaEnums.newBuilder()
                        .addBgaCategory(EnumValue.newBuilder().setId(1).setName("动脉血").build())
                        .build())
                    .build())
                .build());
        }

        private PatientBgaRecordsDataSourceHandler handler() {
            return new PatientBgaRecordsDataSourceHandler(
                support,
                new MonitoringWindowResolver(support, balanceStatsShiftRepo),
                recordRepo,
                detailRepo,
                bgaParamRepo,
                monitoringConfig,
                configProtoService,
                accountRepo,
                new ReportProperties(),
                new DefaultResourceLoader()
            );
        }
    }

    private static final String SIGNATURE_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
}
