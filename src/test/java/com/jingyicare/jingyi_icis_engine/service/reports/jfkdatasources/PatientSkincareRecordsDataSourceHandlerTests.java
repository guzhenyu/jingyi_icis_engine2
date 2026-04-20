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
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlan;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlanAttr;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecord;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecordAttr;
import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareType;
import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareTypeAttribute;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincarePlanAttrRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincarePlanRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincareRecordAttrRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincareRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.SkincareTypeAttributeRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.SkincareTypeRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class PatientSkincareRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsSkincareRowsWithWhitelistAndAccountName() {
        TestContext ctx = new TestContext();
        PatientSkincareRecord record = PatientSkincareRecord.builder()
            .id(30L)
            .deptId("patient-dept")
            .pid(10001L)
            .patientSkincarePlanId(20L)
            .createdAt(LocalDateTime.of(2026, 4, 17, 0, 20))
            .modifiedBy("201")
            .isDeleted(false)
            .build();
        when(ctx.skincareRecordRepo.findReportSkincareRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(record));
        when(ctx.skincarePlanRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(plan(20L, 5)));
        when(ctx.skincareTypeRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(type(5, "压疮护理")));
        when(ctx.skincarePlanAttrRepo.findByPatientSkincarePlanIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(
                planAttr(101L, 20L, 1001, "骶尾部"),
                planAttr(102L, 20L, 1002, "应被白名单过滤")
            ));
        when(ctx.skincareRecordAttrRepo.findByPatientSkincareRecordIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(recordAttr(201L, 30L, 1003, "翻身")));
        when(ctx.skincareTypeAttrRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(
                attr(1001, "site", "部位", 2),
                attr(1002, "ignored", "忽略项", 1),
                attr(1003, "record", "处理", 3)
            ));
        when(ctx.accountRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(account(201L, "张护士", SIGNATURE_PNG)));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).containsExactly(List.of("2026-04-17 08:20"));
        assertThat(output.get("content")).containsExactly(List.of("压疮护理 部位: 骶尾部; 处理: 翻身"));
        assertThat(output.get("recorded_by")).containsExactly(List.of(SIGNATURE_PNG));

        verify(ctx.skincareRecordRepo).findReportSkincareRecords(10001L, MON_START_UTC, MON_END_UTC);
    }

    @Test
    public void handleFallsBackToRawModifiedByWhenAccountIsMissing() {
        TestContext ctx = new TestContext();
        PatientSkincareRecord record = PatientSkincareRecord.builder()
            .id(31L)
            .deptId("patient-dept")
            .pid(10001L)
            .patientSkincarePlanId(20L)
            .createdAt(LocalDateTime.of(2026, 4, 17, 0, 30))
            .modifiedBy("raw-user")
            .isDeleted(false)
            .build();
        when(ctx.skincareRecordRepo.findReportSkincareRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of(record));
        when(ctx.skincarePlanRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(plan(20L, 5)));
        when(ctx.skincareTypeRepo.findByIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of(type(5, "皮肤护理")));
        when(ctx.skincarePlanAttrRepo.findByPatientSkincarePlanIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of());
        when(ctx.skincareRecordAttrRepo.findByPatientSkincareRecordIdInAndIsDeletedFalse(any()))
            .thenReturn(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("content")).containsExactly(List.of("皮肤护理"));
        assertThat(output.get("recorded_by")).containsExactly(List.of("raw-user"));
    }

    @Test
    public void handleReturnsEmptyRowsWhenNoRecordsExist() {
        TestContext ctx = new TestContext();
        when(ctx.skincareRecordRepo.findReportSkincareRecords(10001L, MON_START_UTC, MON_END_UTC))
            .thenReturn(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("record_time")).isEmpty();
        assertThat(output.get("content")).isEmpty();
        assertThat(output.get("recorded_by")).isEmpty();
    }

    private static JfkDataSourcePB input() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_SKINCARE_RECORDS, "table-259"))
            .setMetaId(JfkDataSourceIds.PATIENT_SKINCARE_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-17T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-18T00:00Z"))
            .addInputData(strInput("table_id", "table-259"))
            .addInputData(doubleArrayInput("col_widths", List.of(300d, 1000d, 300d)))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static PatientSkincarePlan plan(Long id, Integer typeId) {
        return PatientSkincarePlan.builder()
            .id(id)
            .deptId("patient-dept")
            .pid(10001L)
            .skincareTypeId(typeId)
            .createdAt(LocalDateTime.of(2026, 4, 16, 10, 0))
            .isDeleted(false)
            .build();
    }

    private static SkincareType type(Integer id, String name) {
        return SkincareType.builder()
            .id(id)
            .deptId("patient-dept")
            .type("skin")
            .name(name)
            .isDeleted(false)
            .build();
    }

    private static PatientSkincarePlanAttr planAttr(Long id, Long planId, Integer attrId, String value) {
        return PatientSkincarePlanAttr.builder()
            .id(id)
            .patientSkincarePlanId(planId)
            .skincareAttrId(attrId)
            .value(encodedStringValue(value))
            .isDeleted(false)
            .build();
    }

    private static PatientSkincareRecordAttr recordAttr(Long id, Long recordId, Integer attrId, String value) {
        return PatientSkincareRecordAttr.builder()
            .id(id)
            .patientSkincareRecordId(recordId)
            .skincareAttrId(attrId)
            .value(encodedStringValue(value))
            .isDeleted(false)
            .build();
    }

    private static SkincareTypeAttribute attr(Integer id, String code, String name, int displayOrder) {
        return SkincareTypeAttribute.builder()
            .id(id)
            .skincareTypeId(5)
            .attrCode(code)
            .attrName(name)
            .displayOrder(displayOrder)
            .attrTypePb(ProtoUtils.encodeValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.STRING)
                .build()))
            .isInitial(false)
            .isMaintenance(true)
            .showInTable(false)
            .isDeleted(false)
            .build();
    }

    private static String encodedStringValue(String value) {
        return ProtoUtils.encodeGenericValue(GenericValuePB.newBuilder().setStrVal(value).build());
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
        private final PatientSkincareRecordRepository skincareRecordRepo = mock(PatientSkincareRecordRepository.class);
        private final PatientSkincarePlanRepository skincarePlanRepo = mock(PatientSkincarePlanRepository.class);
        private final PatientSkincarePlanAttrRepository skincarePlanAttrRepo = mock(PatientSkincarePlanAttrRepository.class);
        private final PatientSkincareRecordAttrRepository skincareRecordAttrRepo = mock(PatientSkincareRecordAttrRepository.class);
        private final SkincareTypeRepository skincareTypeRepo = mock(SkincareTypeRepository.class);
        private final SkincareTypeAttributeRepository skincareTypeAttrRepo = mock(SkincareTypeAttributeRepository.class);
        private final AccountRepository accountRepo = mock(AccountRepository.class);
        private final ReportProperties reportProperties = new ReportProperties();

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
            reportProperties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            reportProperties.getCompact().getSkincare().setAttrCodeWhitelist("site,record");
        }

        private PatientSkincareRecordsDataSourceHandler handler() {
            return new PatientSkincareRecordsDataSourceHandler(
                support,
                monitoringWindowResolver,
                skincareRecordRepo,
                skincarePlanRepo,
                skincarePlanAttrRepo,
                skincareRecordAttrRepo,
                skincareTypeRepo,
                skincareTypeAttrRepo,
                accountRepo,
                reportProperties,
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
