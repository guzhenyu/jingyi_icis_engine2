package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDischargedPatientsV2Req;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDischargedPatientsV2Resp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetInlinePatientsV2Req;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetInlinePatientsV2Resp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.PatientTableHeaderColumnPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.PatientTablePB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.DisplayFieldSettingsPB;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class PatientDisplaySettingsTests extends TestsBase {
    @BeforeEach
    void prepareSettings() {
        clearSettings();
    }

    @AfterEach
    void clearSettings() {
        jdbc.update("DELETE FROM dept_system_settings WHERE dept_id = ?", DEPT);
        jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "dept_system_settings:" + DEPT + ":%");
    }

    @Test
    void missingSettingsUseDefaultHeadersForAllFourPatientListsWithoutWritingDefaults() {
        Map<Integer, PatientTablePB> tables = readPatientTables();

        for (int functionId : FUNCTION_IDS) {
            DisplayFieldSettingsPB defaults = config.getConfig().getPatient()
                .getDefaultDisplayFieldSettingsList().stream()
                .filter(settings -> settings.getFunctionIdValue() == functionId)
                .findFirst().orElseThrow();
            List<String> expectedIds = defaults.getTableList().stream()
                .flatMap(table -> table.getColIdToDisplayList().stream()).toList();
            List<String> expectedNames = defaults.getTableList().stream()
                .flatMap(table -> table.getColIdToDisplayList().stream().map(columnId ->
                    Stream.concat(table.getRequiredColList().stream(),
                        table.getOptionalColList().stream())
                        .filter(column -> column.getColumnId().equals(columnId))
                        .findFirst().orElseThrow().getColumnName()))
                .toList();
            DisplayFieldSettingsPB settings = getPatientSettings(functionId);
            assertThat(settings.getTableList().stream()
                .flatMap(table -> table.getColIdToDisplayList().stream()).toList())
                .containsExactlyElementsOf(expectedIds);
            assertThat(settings.getTableList().stream()
                .flatMap(table -> table.getColNameToDisplayList().stream()).toList())
                .containsExactlyElementsOf(expectedNames);
            assertThat(tables.get(functionId).getColList())
                .extracting(PatientTableHeaderColumnPB::getColId)
                .containsExactlyElementsOf(expectedIds);
            assertThat(tables.get(functionId).getColList())
                .extracting(PatientTableHeaderColumnPB::getName)
                .containsExactlyElementsOf(expectedNames);
        }
        for (int functionId : FUNCTION_IDS) {
            assertThat(store.hasDeptSettings(DEPT, functionId)).isFalse();
        }
        assertThat(savedSettings()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void storedSettingsKeepEachListsColumnOrderAndRebuildDerivedNamesWithoutWriting(
        boolean hasStoredNames
    ) {
        locks.execute(FUNCTION_IDS.stream()
            .map(id -> LockKeys.deptSystemSettings(DEPT, id)).toList(), () -> {
                for (int functionId : FUNCTION_IDS) {
                    saveSettings(customSettings(functionId, hasStoredNames));
                }
                return null;
            });
        LocalDateTime marker = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbc.update("UPDATE dept_system_settings SET modified_at = ? WHERE dept_id = ?",
            marker, DEPT);
        List<Map<String, Object>> before = savedSettings();

        Map<Integer, PatientTablePB> tables = readPatientTables();

        for (int functionId : FUNCTION_IDS) {
            List<String> expectedIds = functionId % 2 == 0
                ? List.of("bed_number_str", "icu_name") : List.of("icu_name", "bed_number_str");
            List<String> expectedNames = functionId % 2 == 0
                ? List.of("床号" + functionId, "姓名" + functionId)
                : List.of("姓名" + functionId, "床号" + functionId);
            DisplayFieldSettingsPB settings = getPatientSettings(functionId);
            assertThat(settings.getTableList().stream()
                .flatMap(table -> table.getColIdToDisplayList().stream()).toList())
                .containsExactlyElementsOf(expectedIds);
            assertThat(settings.getTableList().stream()
                .flatMap(table -> table.getColNameToDisplayList().stream()).toList())
                .containsExactlyElementsOf(expectedNames);
            assertThat(tables.get(functionId).getColList())
                .extracting(PatientTableHeaderColumnPB::getColId)
                .containsExactlyElementsOf(expectedIds);
            assertThat(tables.get(functionId).getColList())
                .extracting(PatientTableHeaderColumnPB::getName)
                .containsExactlyElementsOf(expectedNames);
        }
        assertThat(readPatientTables()).isEqualTo(tables);
        assertThat(savedSettings()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"decode", "missingRequired", "unknownColumn"})
    void invalidStoredSettingsKeepTheOriginalFailurePolicy(String failure) {
        DisplayFieldSettingsPB settings = customSettings(SettingsStore.GET_PATIENTS_IN_ICU, false);
        String encoded;
        if (failure.equals("decode")) {
            encoded = "broken!";
        } else {
            DisplayFieldSettingsPB.Builder builder = settings.toBuilder();
            builder.getTableBuilder(0).clearColIdToDisplay().addColIdToDisplay(
                failure.equals("missingRequired") ? "bed_number_str" : "unknown_column");
            encoded = ProtoUtils.encodeDisplayFieldSettings(builder.build());
        }
        jdbc.update("""
            INSERT INTO dept_system_settings
                (dept_id, function_id, settings_pb, modified_at, modified_by)
            VALUES (?, ?, ?, ?, ?)
            """, DEPT, SettingsStore.GET_PATIENTS_IN_ICU, encoded,
            LocalDateTime.of(2020, 1, 1, 0, 0), "original-user");
        List<Map<String, Object>> before = savedSettings();

        // 截断退出调用以验证错误策略，不关闭真实测试上下文或 JVM。
        try (MockedStatic<LogUtils> logUtils = mockStatic(LogUtils.class)) {
            logUtils.when(() -> LogUtils.flushAndQuit(any(ConfigurableApplicationContext.class)))
                .thenThrow(new IllegalStateException("模拟终止无效配置读取"));
            assertThatThrownBy(() -> getPatientSettings(SettingsStore.GET_PATIENTS_IN_ICU))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟终止无效配置读取");
            logUtils.verify(() -> LogUtils.flushAndQuit(any(ConfigurableApplicationContext.class)));
        }
        assertThat(savedSettings()).isEqualTo(before);
    }

    private DisplayFieldSettingsPB getPatientSettings(int functionId) {
        return switch (functionId) {
            case SettingsStore.GET_PATIENTS_IN_ICU -> store.getPatientsInIcuSettingsPB(DEPT);
            case SettingsStore.GET_PATIENTS_PENDING_ADMISSION ->
                store.getPatientsPendingAdmissionSettingsPB(DEPT);
            case SettingsStore.GET_PATIENTS_PENDING_DISCHARGED ->
                store.getPatientsPendingDischargedSettingsPB(DEPT);
            case SettingsStore.GET_PATIENTS_DISCHARGED ->
                store.getPatientsDischargedSettingsPB(DEPT);
            default -> throw new IllegalArgumentException("无效测试功能编号");
        };
    }

    private Map<Integer, PatientTablePB> readPatientTables() {
        GetInlinePatientsV2Resp inline = patients.getInlinePatientsV2(ProtoUtils.protoToJson(
            GetInlinePatientsV2Req.newBuilder().setDeptId(DEPT).build()));
        GetDischargedPatientsV2Resp discharged = patients.getDischargedPatientsV2(
            ProtoUtils.protoToJson(GetDischargedPatientsV2Req.newBuilder()
                .setDeptId(DEPT).build()));
        assertThat(inline.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(discharged.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        return Map.of(
            SettingsStore.GET_PATIENTS_IN_ICU, inline.getInIcu(),
            SettingsStore.GET_PATIENTS_PENDING_ADMISSION, inline.getPendingAdmission(),
            SettingsStore.GET_PATIENTS_PENDING_DISCHARGED, inline.getPendingDischarged(),
            SettingsStore.GET_PATIENTS_DISCHARGED, discharged.getPatient());
    }

    private DisplayFieldSettingsPB customSettings(int functionId, boolean hasStoredNames) {
        DisplayFieldSettingsPB.Table.Builder table = DisplayFieldSettingsPB.Table.newBuilder()
            .setTableName("patient_records")
            .addRequiredCol(DisplayFieldSettingsPB.HeaderColumn.newBuilder()
                .setColumnId("icu_name").setColumnName("姓名" + functionId))
            .addOptionalCol(DisplayFieldSettingsPB.HeaderColumn.newBuilder()
                .setColumnId("bed_number_str").setColumnName("床号" + functionId));
        table.addAllColIdToDisplay(functionId % 2 == 0
            ? List.of("bed_number_str", "icu_name") : List.of("icu_name", "bed_number_str"));
        if (hasStoredNames) table.addAllColNameToDisplay(List.of("过期列名", "重复列名", "多余列名"));
        return DisplayFieldSettingsPB.newBuilder().setFunctionIdValue(functionId)
            .setFunctionName("患者列表" + functionId).addTable(table).build();
    }

    private void saveSettings(DisplayFieldSettingsPB settings) {
        switch (settings.getFunctionIdValue()) {
            case SettingsStore.GET_PATIENTS_IN_ICU ->
                store.savePatientsInIcuSettingsPB(DEPT, settings, "original-user");
            case SettingsStore.GET_PATIENTS_PENDING_ADMISSION ->
                store.savePatientsPendingAdmissionSettingsPB(DEPT, settings, "original-user");
            case SettingsStore.GET_PATIENTS_PENDING_DISCHARGED ->
                store.savePatientsPendingDischargedSettingsPB(DEPT, settings, "original-user");
            case SettingsStore.GET_PATIENTS_DISCHARGED ->
                store.savePatientsDischargedSettingsPB(DEPT, settings, "original-user");
            default -> throw new IllegalArgumentException("无效测试功能编号");
        }
    }

    private List<Map<String, Object>> savedSettings() {
        return jdbc.queryForList("""
            SELECT function_id, settings_pb, modified_at, modified_by
            FROM dept_system_settings WHERE dept_id = ? ORDER BY function_id
            """, DEPT);
    }

    private static final String DEPT = "patient-display-settings-tests";
    private static final List<Integer> FUNCTION_IDS = List.of(
        SettingsStore.GET_PATIENTS_IN_ICU, SettingsStore.GET_PATIENTS_PENDING_ADMISSION,
        SettingsStore.GET_PATIENTS_PENDING_DISCHARGED, SettingsStore.GET_PATIENTS_DISCHARGED);
    @Autowired private PatientService patients;
    @Autowired private ProtoConfig config;
    @Autowired private SettingsStore store;
    @Autowired private LockExecutor locks;
    @Autowired private JdbcTemplate jdbc;
}
