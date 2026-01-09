package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class BalanceCalculatorTests extends TestsBase {
    public BalanceCalculatorTests(
        @Autowired ConfigProtoService protoService,
        @Autowired BalanceCalculator balanceCalculator
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.monitoringMeta = protoService.getConfig().getMonitoring();
        this.SUMMARY_MONOTORING_CODE = monitoringMeta.getParamCodeSummary();
        this.IN_GROUP_NAME = monitoringMeta.getBalanceInGroupName();
        this.OUT_GROUP_NAME = monitoringMeta.getBalanceOutGroupName();
        this.NET_GROUP_NAME = monitoringMeta.getBalanceNetGroupName();
        this.BALANCE_TYPE_NAN_ID = monitoringMeta.getEnums().getBalanceNan().getId();
        this.BALANCE_TYPE_IN_ID = monitoringMeta.getEnums().getBalanceIn().getId();
        this.BALANCE_TYPE_OUT_ID = monitoringMeta.getEnums().getBalanceOut().getId();
        this.BALANCE_TYPE_NET_ID = monitoringMeta.getEnums().getBalanceNet().getId();
        this.GROUP_TYPE_BALANCE = monitoringMeta.getEnums().getGroupTypeBalance().getId();
        this.GROUP_TYPE_MONITORING = monitoringMeta.getEnums().getGroupTypeMonitoring().getId();
        this.HOURLY_NET_CODE = monitoringMeta.getNetHourlyNetCode();

        this.deptId = "10039";
        this.pid = 2101L;

        this.balanceCalculator = balanceCalculator;
    }

    @Test
    public void testGetCodeRecordsBalanceTypeIn() {
        MonitoringParamPB monParamPB = MonitoringParamPB.newBuilder()
            .setId(1)
            .setCode("code1")
            .setName("name1")
            .setValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(3)
                .build())
            .setBalanceType(BALANCE_TYPE_IN_ID)
            .build();
        List<PatientMonitoringRecord> records = List.of(
            PatientMonitoringRecord.builder()
                .id(1L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.23456f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.23")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(2L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 9, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(2.0f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("2.0")
                .unit("unit1")
                .source("")
                .build()
        );

        // 测试正常汇总
        ValueMetaPB groupSumValueMeta = ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.FLOAT)
            .setUnit("unit1")
            .setDecimalPlaces(4)
            .build();
        Map<LocalDateTime, GenericValuePB> groupSumTimeValMap = new HashMap<>();

        MonitoringCodeRecordsPB codeRecords = balanceCalculator.getCodeRecords(
            monParamPB, records, groupSumValueMeta, groupSumTimeValMap
        );
        assertThat(groupSumTimeValMap).hasSize(2);
        assertThat(codeRecords.getParamCode()).isEqualTo("code1");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getId()).isEqualTo(1L);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.23456f);
        assertThat(codeRecords.getRecordValue(0).getValueStr()).isEqualTo("1.23");
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));
        assertThat(groupSumTimeValMap.get(effectiveTime).getFloatVal()).isEqualTo(1.23456f);

        assertThat(codeRecords.getRecordValue(1).getId()).isEqualTo(2L);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(2.0f);
        assertThat(codeRecords.getRecordValue(1).getValueStr()).isEqualTo("2.0");
        effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 9, 0));
        assertThat(groupSumTimeValMap.get(effectiveTime).getFloatVal()).isEqualTo(2.0f);

        assertThat(codeRecords.getRecordValue(2).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isEqualTo(3.23456f);
        assertThat(codeRecords.getRecordValue(2).getValueStr()).isEqualTo("3.235");
        assertThat(codeRecords.getRecordValue(2).getRecordedAtIso8601()).isEmpty();

        // 测试类型不同的汇总
        groupSumValueMeta = ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.INT64)
            .setUnit("unit1")
            .build();
        groupSumTimeValMap = new HashMap<>();
        codeRecords = balanceCalculator.getCodeRecords(
            monParamPB, records, groupSumValueMeta, groupSumTimeValMap
        );
        assertThat(groupSumTimeValMap).hasSize(2);
        assertThat(codeRecords.getParamCode()).isEqualTo("code1");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getId()).isEqualTo(1L);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.23456f);
        effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));
        assertThat(groupSumTimeValMap.get(effectiveTime).getInt64Val()).isEqualTo(1L);

        assertThat(codeRecords.getRecordValue(1).getId()).isEqualTo(2L);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(2.0f);
        effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 9, 0));
        assertThat(groupSumTimeValMap.get(effectiveTime).getInt64Val()).isEqualTo(2L);
    }

    @Test
    public void testGetCodeRecordsBalanceTypeOut() {
        MonitoringParamPB monParamPB = MonitoringParamPB.newBuilder()
            .setId(1)
            .setCode("code1")
            .setName("name1")
            .setValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(3)
                .build())
            .setBalanceType(BALANCE_TYPE_OUT_ID)
            .build();
        List<PatientMonitoringRecord> records = List.of(
            PatientMonitoringRecord.builder()
                .id(1L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.23456f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.23")
                .unit("unit1")
                .source("")
                .build()
        );

        // 测试正常汇总
        ValueMetaPB groupSumValueMeta = ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.FLOAT)
            .setUnit("unit1")
            .setDecimalPlaces(4)
            .build();
        Map<LocalDateTime, GenericValuePB> groupSumTimeValMap = new HashMap<>();
        MonitoringCodeRecordsPB codeRecords = balanceCalculator.getCodeRecords(
            monParamPB, records, groupSumValueMeta, groupSumTimeValMap
        );
        assertThat(groupSumTimeValMap).hasSize(1);
        assertThat(codeRecords.getParamCode()).isEqualTo("code1");
        assertThat(codeRecords.getRecordValueList()).hasSize(2);
        assertThat(codeRecords.getRecordValue(0).getId()).isEqualTo(1L);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.23456f);
        assertThat(codeRecords.getRecordValue(0).getValueStr()).isEqualTo("1.23");
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));
        assertThat(groupSumTimeValMap.get(effectiveTime).getFloatVal()).isEqualTo(1.23456f);

        assertThat(codeRecords.getRecordValue(1).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(1.23456f);
        assertThat(codeRecords.getRecordValue(1).getValueStr()).isEqualTo("1.235");
        assertThat(codeRecords.getRecordValue(1).getRecordedAtIso8601()).isEmpty();
    }

    @Test
    public void testGetCodeRecordsBalanceTypeNan() {
        MonitoringParamPB monParamPB = MonitoringParamPB.newBuilder()
            .setId(1)
            .setCode("code1")
            .setName("name1")
            .setValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(3)
                .build())
            .setBalanceType(BALANCE_TYPE_NAN_ID)
            .build();
        List<PatientMonitoringRecord> records = List.of(
            PatientMonitoringRecord.builder()
                .id(1L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.23456f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.23")
                .unit("unit1")
                .source("")
                .build()
        );

        // 测试正常汇总
        ValueMetaPB groupSumValueMeta = ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.FLOAT)
            .setUnit("unit1")
            .setDecimalPlaces(4)
            .build();
        Map<LocalDateTime, GenericValuePB> groupSumTimeValMap = new HashMap<>();
        MonitoringCodeRecordsPB codeRecords = balanceCalculator.getCodeRecords(
            monParamPB, records, groupSumValueMeta, groupSumTimeValMap
        );
        assertThat(groupSumTimeValMap).hasSize(0);
        assertThat(codeRecords.getParamCode()).isEqualTo("code1");
        assertThat(codeRecords.getRecordValueList()).hasSize(1);
        assertThat(codeRecords.getRecordValue(0).getId()).isEqualTo(1L);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.23456f);
        assertThat(codeRecords.getRecordValue(0).getValueStr()).isEqualTo("1.23");
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
            codeRecords.getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));
    }

    @Test
    public void testGetGroupRecords() {
        final LocalDateTime startTime = LocalDateTime.of(2024, 11, 20, 0, 0);
        final LocalDateTime endTime = LocalDateTime.of(2024, 11, 20, 23, 59);
        String modifiedBy = "testUser";
        MonitoringGroupBetaPB groupMeta = MonitoringGroupBetaPB.newBuilder()
            .setId(101)
            .setName(IN_GROUP_NAME)
            .setGroupType(GROUP_TYPE_BALANCE)
            .setSumValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(5)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(1)
                .setCode("code1")
                .setName("name1")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_IN_ID)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(2)
                .setCode("code2")
                .setName("name2")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.INT64)
                    .setUnit("unit1")
                    .build())
                .setBalanceType(BALANCE_TYPE_IN_ID)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(3)
                .setCode("code3")
                .setName("name3")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_NAN_ID)
                .build())
            .build();

        /*
         *            8:00      9:00      10:00
         * code1      1.23456             1.1
         * code2      10        20
         * code3      100
         * code4      1000
         * 
         * code3(NAN)不计入合计，code4不存在不计入合计
         */
        Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap = new HashMap<>();
        recordsMap.put("code1", List.of(
            PatientMonitoringRecord.builder()
                .id(1L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.23456f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.23")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(2L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 2, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.1f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.1")
                .unit("unit1")
                .source("")
                .build()
        ));
        recordsMap.put("code2", List.of(
            PatientMonitoringRecord.builder()
                .id(3L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code2")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setInt64Val(10L)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("10")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(4L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code2")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 1, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setInt64Val(20L)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("20")
                .unit("unit1")
                .source("")
                .build()
        ));
        recordsMap.put("code3", List.of(
            PatientMonitoringRecord.builder()
                .id(5L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code3")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(100.0f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("100")
                .unit("unit1")
                .source("")
                .build()
        ));
        recordsMap.put("code4", List.of(
            PatientMonitoringRecord.builder()
                .id(6L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code4")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1000.0f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1000")
                .unit("unit1")
                .source("")
                .build()
        ));

        MonitoringGroupRecordsPB groupRecords = balanceCalculator.getGroupRecords(
            pid, deptId, startTime, endTime, modifiedBy, groupMeta, recordsMap
        );
        assertThat(groupRecords.getDeptMonitoringGroupId()).isEqualTo(101);
        assertThat(groupRecords.getCodeRecordsList()).hasSize(4);

        // 观察参数1
        assertThat(groupRecords.getCodeRecords(0).getParamCode()).isEqualTo("code1");
        assertThat(groupRecords.getCodeRecords(0).getRecordValueList()).hasSize(3);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(0).getId()).isEqualTo(1L);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.23456f);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(0).getValueStr()).isEqualTo("1.23");
        LocalDateTime effectiveTime1 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(0).getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime1).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));

        assertThat(groupRecords.getCodeRecords(0).getRecordValue(1).getId()).isEqualTo(2L);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(1).getValue().getFloatVal()).isEqualTo(1.1f);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(1).getValueStr()).isEqualTo("1.1");
        LocalDateTime effectiveTime2 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(0).getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime2).isEqualTo(LocalDateTime.of(2024, 11, 20, 2, 0));

        assertThat(groupRecords.getCodeRecords(0).getRecordValue(2).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(2).getValue().getFloatVal()).isEqualTo(2.33456f);
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(2).getValueStr()).isEqualTo("2.335");
        assertThat(groupRecords.getCodeRecords(0).getRecordValue(2).getRecordedAtIso8601()).isEmpty();

        // 观察参数2
        assertThat(groupRecords.getCodeRecords(1).getParamCode()).isEqualTo("code2");
        assertThat(groupRecords.getCodeRecords(1).getRecordValueList()).hasSize(3);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(0).getId()).isEqualTo(3L);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(0).getValue().getInt64Val()).isEqualTo(10L);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(0).getValueStr()).isEqualTo("10");
        LocalDateTime effectiveTime3 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(1).getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime3).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));

        assertThat(groupRecords.getCodeRecords(1).getRecordValue(1).getId()).isEqualTo(4L);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(1).getValue().getInt64Val()).isEqualTo(20L);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(1).getValueStr()).isEqualTo("20");
        LocalDateTime effectiveTime4 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(1).getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime4).isEqualTo(LocalDateTime.of(2024, 11, 20, 1, 0));

        assertThat(groupRecords.getCodeRecords(1).getRecordValue(2).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(2).getValue().getInt64Val()).isEqualTo(30L);
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(2).getValueStr()).isEqualTo("30");
        assertThat(groupRecords.getCodeRecords(1).getRecordValue(2).getRecordedAtIso8601()).isEmpty();

        // 观察参数3
        assertThat(groupRecords.getCodeRecords(2).getParamCode()).isEqualTo("code3");
        assertThat(groupRecords.getCodeRecords(2).getRecordValueList()).hasSize(1);
        assertThat(groupRecords.getCodeRecords(2).getRecordValue(0).getId()).isEqualTo(5L);
        assertThat(groupRecords.getCodeRecords(2).getRecordValue(0).getValue().getFloatVal()).isEqualTo(100.0f);
        assertThat(groupRecords.getCodeRecords(2).getRecordValue(0).getValueStr()).isEqualTo("100");
        LocalDateTime effectiveTime5 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(2).getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime5).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));

        // 统计值
        assertThat(groupRecords.getCodeRecords(3).getParamCode()).isEqualTo(SUMMARY_MONOTORING_CODE);
        assertThat(groupRecords.getCodeRecords(3).getRecordValueList()).hasSize(4);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(0).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(0).getValue().getFloatVal()).isEqualTo(11.23456f);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(0).getValueStr()).isEqualTo("11.235");
        LocalDateTime effectiveTime6 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(3).getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime6).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));

        assertThat(groupRecords.getCodeRecords(3).getRecordValue(1).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(1).getValue().getFloatVal()).isEqualTo(20f);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(1).getValueStr()).isEqualTo("20");
        LocalDateTime effectiveTime7 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(3).getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime7).isEqualTo(LocalDateTime.of(2024, 11, 20, 1, 0));

        assertThat(groupRecords.getCodeRecords(3).getRecordValue(2).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(2).getValue().getFloatVal()).isEqualTo(1.1f);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(2).getValueStr()).isEqualTo("1.1");
        LocalDateTime effectiveTime8 = TimeUtils.fromIso8601String(
            groupRecords.getCodeRecords(3).getRecordValue(2).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime8).isEqualTo(LocalDateTime.of(2024, 11, 20, 2, 0));

        assertThat(groupRecords.getCodeRecords(3).getRecordValue(3).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(3).getValue().getFloatVal()).isEqualTo(32.33456f);
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(3).getValueStr()).isEqualTo("32.335");
        assertThat(groupRecords.getCodeRecords(3).getRecordValue(3).getRecordedAtIso8601()).isEmpty();
    }

    @Test
    public void testGetNetGroupRecords() {
        MonitoringGroupBetaPB inGroupMeta = MonitoringGroupBetaPB.newBuilder()
            .setId(101)
            .setName(IN_GROUP_NAME)
            .setGroupType(GROUP_TYPE_BALANCE)
            .setSumValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(5)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(1)
                .setCode("code1")
                .setName("name1")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_IN_ID)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(2)
                .setCode("code2")
                .setName("name2")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_IN_ID)
                .build())
            .build();
        MonitoringGroupBetaPB outGroupMeta = MonitoringGroupBetaPB.newBuilder()
            .setId(102)
            .setName(OUT_GROUP_NAME)
            .setGroupType(GROUP_TYPE_BALANCE)
            .setSumValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(5)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(3)
                .setCode("code3")
                .setName("name3")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_OUT_ID)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(4)
                .setCode("code4")
                .setName("name4")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(3)
                    .build())
                .setBalanceType(BALANCE_TYPE_OUT_ID)
                .build())
            .build();
        MonitoringGroupBetaPB netGroupMeta = MonitoringGroupBetaPB.newBuilder()
            .setId(103)
            .setName(NET_GROUP_NAME)
            .setGroupType(GROUP_TYPE_BALANCE)
            .setSumValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setUnit("unit1")
                .setDecimalPlaces(5)
                .build())
            .addParam(MonitoringParamPB.newBuilder()
                .setId(5)
                .setCode(HOURLY_NET_CODE)
                .setName("每小时平衡量")
                .setValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(5)
                    .build())
                .setBalanceType(BALANCE_TYPE_NET_ID)
                .build())
            .build();

        MonitoringGroupRecordsPB inGroupRecords = MonitoringGroupRecordsPB.newBuilder()
            .setDeptMonitoringGroupId(101)
            .addCodeRecords(MonitoringCodeRecordsPB.newBuilder()
                .setParamCode(SUMMARY_MONOTORING_CODE)
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(Consts.VIRTUAL_RECORD_ID)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(1.23456f)
                        .build())
                    .setValueStr("1.23")
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 20, 0, 0), ZONE_ID))
                    .build())
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(Consts.VIRTUAL_RECORD_ID)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(1.1f)
                        .build())
                    .setValueStr("1.1")
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 20, 1, 0), ZONE_ID))
                    .build())
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(Consts.VIRTUAL_RECORD_ID)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(2.335f)
                        .build())
                    .setValueStr("2.33")
                    .setRecordedAtIso8601("")
                    .build())
                .build())
            .build();
        MonitoringGroupRecordsPB outGroupRecords = MonitoringGroupRecordsPB.newBuilder()
            .setDeptMonitoringGroupId(102)
            .addCodeRecords(MonitoringCodeRecordsPB.newBuilder()
                .setParamCode(SUMMARY_MONOTORING_CODE)
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(1)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(10f)
                        .build())
                    .setValueStr("10.0")
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 20, 0, 0), ZONE_ID))
                    .build())
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(2)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(20f)
                        .build())
                    .setValueStr("20.0")
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 20, 2, 0), ZONE_ID))
                    .build())
                .addRecordValue(MonitoringRecordValPB.newBuilder()
                    .setId(Consts.VIRTUAL_RECORD_ID)
                    .setValue(GenericValuePB.newBuilder()
                        .setFloatVal(30f)
                        .build())
                    .setValueStr("30")
                    .setRecordedAtIso8601("")
                    .build())
                .build())
            .build();
        MonitoringGroupRecordsPB netGroupRecords = balanceCalculator.getNetGroupRecords(
            null, inGroupMeta, outGroupMeta, netGroupMeta, inGroupRecords, outGroupRecords
        );
        assertThat(netGroupRecords.getDeptMonitoringGroupId()).isEqualTo(103);
        assertThat(netGroupRecords.getCodeRecordsList()).hasSize(1);
        assertThat(netGroupRecords.getCodeRecords(0).getParamCode()).isEqualTo(HOURLY_NET_CODE);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValueList()).hasSize(4);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(0).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(0).getValue().getFloatVal()).isEqualTo(-8.76544f); // 1.23456 - 10
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(0).getValueStr()).isEqualTo("-8.76544");
        LocalDateTime effectiveTime1 = TimeUtils.fromIso8601String(
            netGroupRecords.getCodeRecords(0).getRecordValue(0).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime1).isEqualTo(LocalDateTime.of(2024, 11, 20, 0, 0));

        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(1).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(1).getValue().getFloatVal()).isEqualTo(1.1f);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(1).getValueStr()).isEqualTo("1.1");
        LocalDateTime effectiveTime2 = TimeUtils.fromIso8601String(
            netGroupRecords.getCodeRecords(0).getRecordValue(1).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime2).isEqualTo(LocalDateTime.of(2024, 11, 20, 1, 0));

        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(2).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(2).getValue().getFloatVal()).isEqualTo(-20f);  // 0 - 20
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(2).getValueStr()).isEqualTo("-20");
        LocalDateTime effectiveTime3 = TimeUtils.fromIso8601String(
            netGroupRecords.getCodeRecords(0).getRecordValue(2).getRecordedAtIso8601(), "UTC");
        assertThat(effectiveTime3).isEqualTo(LocalDateTime.of(2024, 11, 20, 2, 0));

        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(3).getId()).isEqualTo(Consts.VIRTUAL_RECORD_ID);
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(3).getValue().getFloatVal()).isEqualTo(-27.66544f);  // 1.23456 + 1.1 - 10 - 20
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(3).getValueStr()).isEqualTo("-27.66544");
        assertThat(netGroupRecords.getCodeRecords(0).getRecordValue(3).getRecordedAtIso8601()).isEmpty();
    }

    @Test
    public void testGetGroupRecordsList() {
        List<MonitoringGroupBetaPB> groupBetaList = List.of(
            MonitoringGroupBetaPB.newBuilder()
                .setId(101)
                .setName(IN_GROUP_NAME)
                .setGroupType(GROUP_TYPE_BALANCE)
                .setSumValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(5)
                    .build())
                .addParam(MonitoringParamPB.newBuilder()
                    .setId(1)
                    .setCode("code1")
                    .setName("name1")
                    .setValueMeta(ValueMetaPB.newBuilder()
                        .setValueType(TypeEnumPB.FLOAT)
                        .setUnit("unit1")
                        .setDecimalPlaces(3)
                        .build())
                    .setBalanceType(BALANCE_TYPE_IN_ID)
                    .build())
                .addParam(MonitoringParamPB.newBuilder()
                    .setId(2)
                    .setCode("code2")
                    .setName("name2")
                    .setValueMeta(ValueMetaPB.newBuilder()
                        .setValueType(TypeEnumPB.FLOAT)
                        .setUnit("unit1")
                        .setDecimalPlaces(3)
                        .build())
                    .setBalanceType(BALANCE_TYPE_IN_ID)
                    .build())
                .build(),
            MonitoringGroupBetaPB.newBuilder()
                .setId(102)
                .setName(OUT_GROUP_NAME)
                .setGroupType(GROUP_TYPE_BALANCE)
                .setSumValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(5)
                    .build())
                .addParam(MonitoringParamPB.newBuilder()
                    .setId(3)
                    .setCode("code3")
                    .setName("name3")
                    .setValueMeta(ValueMetaPB.newBuilder()
                        .setValueType(TypeEnumPB.FLOAT)
                        .setUnit("unit1")
                        .setDecimalPlaces(3)
                        .build())
                    .setBalanceType(BALANCE_TYPE_OUT_ID)
                    .build())
                .addParam(MonitoringParamPB.newBuilder()
                    .setId(4)
                    .setCode("code4")
                    .setName("name4")
                    .setValueMeta(ValueMetaPB.newBuilder()
                        .setValueType(TypeEnumPB.FLOAT)
                        .setUnit("unit1")
                        .setDecimalPlaces(3)
                        .build())
                    .setBalanceType(BALANCE_TYPE_OUT_ID)
                    .build())
                .build(),
            MonitoringGroupBetaPB.newBuilder()
                .setId(103)
                .setName(NET_GROUP_NAME)
                .setGroupType(GROUP_TYPE_BALANCE)
                .setSumValueMeta(ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setUnit("unit1")
                    .setDecimalPlaces(5)
                    .build())
                .addParam(MonitoringParamPB.newBuilder()
                    .setId(5)
                    .setCode(HOURLY_NET_CODE)
                    .setName("每小时平衡量")
                    .setValueMeta(ValueMetaPB.newBuilder()
                        .setValueType(TypeEnumPB.FLOAT)
                        .setUnit("unit1")
                        .setDecimalPlaces(5)
                        .build())
                    .setBalanceType(BALANCE_TYPE_NET_ID)
                    .build())
                .build());
        Map<String, MonitoringParamPB> paramMap = new HashMap<>();
        for (MonitoringGroupBetaPB groupMeta : groupBetaList) {
            for (MonitoringParamPB param : groupMeta.getParamList()) {
                paramMap.put(param.getCode(), param);
            }
        }

        /*            8:00      9:00      10:00      11:00
         * code1(I)   1.1                 2.2
         * code2(I)   3.3       4.4
         * code3(O)   5.5                            6.6
         * code4(O)   7.7                 8.8
         */
        List<PatientMonitoringRecord> recordList = List.of(
            PatientMonitoringRecord.builder()
                .id(5L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code2")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 1, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(4.4f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("4.4")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(6L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 2, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(2.2f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("2.2")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(7L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code4")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 2, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(8.8f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("8.8")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(8L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code3")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 3, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(6.6f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("6.6")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(1L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code1")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(1.1f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("1.1")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(2L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code2")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(3.3f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("3.3")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(3L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code3")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(5.5f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("5.5")
                .unit("unit1")
                .source("")
                .build(),
            PatientMonitoringRecord.builder()
                .id(4L)
                .pid(1L)
                .deptId("dept1")
                .monitoringParamCode("code4")
                .effectiveTime(LocalDateTime.of(2024, 11, 20, 0, 0))
                .paramValue(
                    ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                        .setValue(
                            GenericValuePB.newBuilder()
                                .setFloatVal(7.7f)
                                .build()
                        )
                        .build()
                    )
                )
                .paramValueStr("7.7")
                .unit("unit1")
                .source("")
                .build()
        );

        final LocalDateTime startTime = LocalDateTime.of(2024, 11, 20, 0, 0);
        final LocalDateTime endTime = LocalDateTime.of(2024, 11, 20, 23, 59);
        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId, startTime, endTime, new ArrayList<>(), paramMap, groupBetaList, recordList, "testUser"
        );
        List<MonitoringGroupRecordsPB> groupRecordsList = balanceCalculator.getGroupRecordsList(args);

        assertThat(groupRecordsList).hasSize(3);
        assertThat(groupRecordsList.get(0).getDeptMonitoringGroupId()).isEqualTo(101);
        assertThat(groupRecordsList.get(1).getDeptMonitoringGroupId()).isEqualTo(102);
        assertThat(groupRecordsList.get(2).getDeptMonitoringGroupId()).isEqualTo(103);

        assertThat(groupRecordsList.get(0).getCodeRecordsList()).hasSize(3);
        MonitoringCodeRecordsPB codeRecords = groupRecordsList.get(0).getCodeRecords(0);
        assertThat(codeRecords.getParamCode()).isEqualTo("code1");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(1.1f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(2.2f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isCloseTo(3.3f, within(0.0001f));
        codeRecords = groupRecordsList.get(0).getCodeRecords(1);
        assertThat(codeRecords.getParamCode()).isEqualTo("code2");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(3.3f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(4.4f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isCloseTo(7.7f, within(0.0001f));
        codeRecords = groupRecordsList.get(0).getCodeRecords(2);
        assertThat(codeRecords.getParamCode()).isEqualTo(SUMMARY_MONOTORING_CODE);
        assertThat(codeRecords.getRecordValueList()).hasSize(4);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(4.4f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(4.4f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isEqualTo(2.2f);
        assertThat(codeRecords.getRecordValue(3).getValue().getFloatVal()).isCloseTo(11f, within(0.0001f));

        assertThat(groupRecordsList.get(1).getCodeRecordsList()).hasSize(3);
        codeRecords = groupRecordsList.get(1).getCodeRecords(0);
        assertThat(codeRecords.getParamCode()).isEqualTo("code3");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(5.5f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(6.6f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isCloseTo(12.1f, within(0.0001f));
        codeRecords = groupRecordsList.get(1).getCodeRecords(1);
        assertThat(codeRecords.getParamCode()).isEqualTo("code4");
        assertThat(codeRecords.getRecordValueList()).hasSize(3);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(7.7f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(8.8f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isCloseTo(16.5f, within(0.0001f));
        codeRecords = groupRecordsList.get(1).getCodeRecords(2);
        assertThat(codeRecords.getParamCode()).isEqualTo(SUMMARY_MONOTORING_CODE);
        assertThat(codeRecords.getRecordValueList()).hasSize(4);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isEqualTo(13.2f);
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isEqualTo(8.8f);
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isEqualTo(6.6f);
        assertThat(codeRecords.getRecordValue(3).getValue().getFloatVal()).isCloseTo(28.6f, within(0.0001f));

        assertThat(groupRecordsList.get(2).getCodeRecordsList()).hasSize(1);
        codeRecords = groupRecordsList.get(2).getCodeRecords(0);
        assertThat(codeRecords.getParamCode()).isEqualTo(HOURLY_NET_CODE);
        assertThat(codeRecords.getRecordValueList()).hasSize(5);
        assertThat(codeRecords.getRecordValue(0).getValue().getFloatVal()).isCloseTo(-8.8f, within(0.0001f));
        assertThat(codeRecords.getRecordValue(1).getValue().getFloatVal()).isCloseTo(4.4f, within(0.0001f));
        assertThat(codeRecords.getRecordValue(2).getValue().getFloatVal()).isCloseTo(-6.6f, within(0.0001f));
        assertThat(codeRecords.getRecordValue(3).getValue().getFloatVal()).isCloseTo(-6.6f, within(0.0001f));
        assertThat(codeRecords.getRecordValue(4).getValue().getFloatVal()).isCloseTo(-17.6f, within(0.0001f));
    }

    private final String ZONE_ID;
    private final String SUMMARY_MONOTORING_CODE;
    private final String IN_GROUP_NAME;
    private final String OUT_GROUP_NAME;
    private final String NET_GROUP_NAME;
    private final Integer BALANCE_TYPE_NAN_ID;
    private final Integer BALANCE_TYPE_IN_ID;
    private final Integer BALANCE_TYPE_OUT_ID;
    private final Integer BALANCE_TYPE_NET_ID;
    private final Integer GROUP_TYPE_BALANCE;
    private final Integer GROUP_TYPE_MONITORING;
    private final String HOURLY_NET_CODE;

    private final String deptId;
    private final Long pid;

    private final MonitoringPB monitoringMeta;
    private final BalanceCalculator balanceCalculator;
}