package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.DeptMonitoringSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.DisplayFieldSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppGeneralSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.DeptArchiveSettings;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuItem;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IntStrKvPB;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.menu.MenuMeta;
import com.jingyicare.jingyi_icis_engine.service.users.menu.MenuService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class SettingsStoreTests extends TestsBase {
    @BeforeEach
    void prepare() {
        clearDepartment();
        SecurityContextHolder.clearContext();
        previousLogo = systemSettingsRepo.findById(SettingsStore.GET_APP_LOGO).orElse(null);
        logoLockExisted = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?", Integer.class,
            LockKeys.systemSettings(SettingsStore.GET_APP_LOGO)) > 0;
        logoChanged = false;
    }

    @AfterEach
    void cleanUp() {
        clearDepartment();
        SecurityContextHolder.clearContext();
        if (logoChanged) {
            systemSettingsRepo.deleteById(SettingsStore.GET_APP_LOGO);
            if (previousLogo != null) systemSettingsRepo.save(previousLogo);
            if (!logoLockExisted) {
                jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                    LockKeys.systemSettings(SettingsStore.GET_APP_LOGO));
            }
        }
    }

    @Test
    void roundTripsPatientSettingsWithoutMixingFunctionsOrDepartments() {
        List<DisplayFieldSettingsPB> settings = PATIENT_FUNCTION_IDS.stream()
            .map(functionId -> DisplayFieldSettingsPB.newBuilder()
                .setFunctionIdValue(functionId).setFunctionName("患者列表" + functionId)
                .addTable(DisplayFieldSettingsPB.Table.newBuilder().setTableName("患者信息")
                    .addRequiredCol(DisplayFieldSettingsPB.HeaderColumn.newBuilder()
                        .setColumnId("icu_name").setColumnName("姓名"))
                    .addColIdToDisplay("icu_name"))
                .build())
            .toList();
        List<String> keys = PATIENT_FUNCTION_IDS.stream()
            .map(id -> LockKeys.deptSystemSettings(DEPT, id)).toList();
        lockExecutor.execute(keys, () -> {
            for (DisplayFieldSettingsPB value : settings) {
                savePatientSettings(DEPT, value, "first-user");
            }
            return null;
        });

        assertThat(jdbcTemplate.queryForList("""
            SELECT function_id FROM dept_system_settings WHERE dept_id = ?
            """, Integer.class, DEPT)).containsExactlyInAnyOrderElementsOf(PATIENT_FUNCTION_IDS);
        for (DisplayFieldSettingsPB value : settings) {
            int functionId = value.getFunctionIdValue();
            DisplayFieldSettingsPB expected = value.toBuilder()
                .setTable(0, value.getTable(0).toBuilder().addColNameToDisplay("姓名")).build();
            assertThat(getPatientSettings(DEPT, functionId)).isEqualTo(expected);
            assertThat(store.hasDeptSettings(DEPT, functionId)).isTrue();
            assertThat(storedPatientSettings(functionId)).isEqualTo(value);
            DisplayFieldSettingsPB other = getPatientSettings(OTHER_DEPT, functionId);
            assertThat(other.getFunctionIdValue()).isEqualTo(functionId);
            assertThat(other).isNotEqualTo(expected);
            assertThat(store.hasDeptSettings(OTHER_DEPT, functionId)).isFalse();
        }
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM dept_system_settings
            WHERE dept_id = ? AND modified_by = ? AND modified_at IS NOT NULL
            """, Integer.class, DEPT, "first-user")).isEqualTo(4);

        LocalDateTime marker = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbcTemplate.update("UPDATE dept_system_settings SET modified_at = ? WHERE dept_id = ?",
            marker, DEPT);
        for (DisplayFieldSettingsPB value : settings) {
            int functionId = value.getFunctionIdValue();
            DisplayFieldSettingsPB updated = value.toBuilder()
                .setFunctionName("更新的患者列表" + functionId).build();
            lockExecutor.execute(List.of(LockKeys.deptSystemSettings(DEPT, functionId)), () -> {
                savePatientSettings(DEPT, updated, "second-user");
                return null;
            });
            DisplayFieldSettingsPB expected = updated.toBuilder()
                .setTable(0, updated.getTable(0).toBuilder().addColNameToDisplay("姓名")).build();
            assertThat(getPatientSettings(DEPT, functionId)).isEqualTo(expected);
            assertThat(storedPatientSettings(functionId)).isEqualTo(updated);
            assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dept_system_settings
                WHERE dept_id = ? AND function_id = ? AND modified_by = ? AND modified_at > ?
                """, Integer.class, DEPT, functionId, "second-user", marker)).isEqualTo(1);
        }
        assertThat(departmentSettingCount()).isEqualTo(4);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void patientSettingsDistinguishMissingRowsFromInvalidBase64AndInvalidProtobuf(int functionId) {
        assertThat(getPatientSettings(DEPT, functionId).getFunctionIdValue())
            .isEqualTo(functionId);
        assertThat(store.hasDeptSettings(DEPT, functionId)).isFalse();
        assertThat(departmentSettingCount()).isZero();
        jdbcTemplate.update("""
            INSERT INTO dept_system_settings
                (dept_id, function_id, settings_pb, modified_at, modified_by)
            VALUES (?, ?, ?, ?, ?)
            """, DEPT, functionId, "broken!", LocalDateTime.now(), "previous-user");
        assertThat(store.hasDeptSettings(DEPT, functionId)).isTrue();
        assertPatientSettingsFailure(functionId);
        // 合法 Base64 中包含截断的 protobuf 字符串字段。
        jdbcTemplate.update("""
            UPDATE dept_system_settings SET settings_pb = ?
            WHERE dept_id = ? AND function_id = ?
            """, "EgE=", DEPT, functionId);
        assertThat(store.hasDeptSettings(DEPT, functionId)).isTrue();
        assertPatientSettingsFailure(functionId);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT settings_pb FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, functionId)).isEqualTo("EgE=");
    }

    @Test
    void roundTripsTypedSettingsAndKeepsDepartmentAndFunctionKeysSeparate() {
        MenuConfigPB menu = MenuConfigPB.newBuilder().setDeptId(DEPT).build();
        MedOrderGroupSettingsPB medication = MedOrderGroupSettingsPB.newBuilder()
            .setEnableMedicationSpeed(true).build();
        DeptMonitoringSettingsPB monitoring = DeptMonitoringSettingsPB.newBuilder()
            .setHeadCustomTimePointGraceMinutes(7).build();
        NursingRecordSettingsPB nursing = NursingRecordSettingsPB.newBuilder()
            .setEnableUpdatingCreatedBy(true).build();
        ScoreSettingsPB score = ScoreSettingsPB.newBuilder().setAllowEditRecordedBy(true).build();
        AppGeneralSettingsPB general = AppGeneralSettingsPB.newBuilder()
            .setPrintAgentIpPort("10.0.0.8:9123").setEnableCa(true).build();
        DeptArchiveSettings archive = DeptArchiveSettings.newBuilder()
            .setDeptId(DEPT).setWardReportTemplateId(42).build();

        lockExecutor.execute(lockKeys(), () -> {
            store.saveMenuConfigPB(DEPT, menu, "first-user");
            store.saveMedOrderGroupSettingsPB(DEPT, medication, "first-user");
            store.saveDeptMonitoringSettingsPB(DEPT, monitoring, "first-user");
            store.saveNursingRecordSettingsPB(DEPT, nursing, "first-user");
            store.saveScoreSettingsPB(DEPT, score, "first-user");
            store.saveAppGeneralSettingsPB(DEPT, general, "first-user");
            store.saveArchiveSettings(DEPT, archive, "first-user");
            return null;
        });

        assertThat(store.getMenuConfigPB(DEPT)).isEqualTo(menu);
        assertThat(store.getMedOrderGroupSettingsPB(DEPT)).isEqualTo(medication);
        assertThat(store.getDeptMonitoringSettingsPB(DEPT)).isEqualTo(monitoring);
        assertThat(store.getNursingRecordSettingsPB(DEPT)).isEqualTo(nursing);
        assertThat(store.getScoreSettingsPB(DEPT)).isEqualTo(score);
        assertThat(store.getAppGeneralSettingsPB(DEPT)).isEqualTo(general);
        assertThat(store.getArchiveSettings(DEPT)).isEqualTo(archive);
        assertThat(store.getMenuConfigPB(OTHER_DEPT)).isNull();
        assertThat(jdbcTemplate.queryForList(
            "SELECT function_id FROM dept_system_settings WHERE dept_id = ?",
            Integer.class, DEPT)).containsExactlyInAnyOrderElementsOf(FUNCTION_IDS);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM dept_system_settings
            WHERE dept_id = ? AND modified_by = ? AND modified_at IS NOT NULL
            """, Integer.class, DEPT, "first-user")).isEqualTo(7);

        jdbcTemplate.update("""
            UPDATE dept_system_settings SET modified_at = ? WHERE dept_id = ?
            """, LocalDateTime.of(2020, 1, 1, 0, 0), DEPT);
        AppGeneralSettingsPB updated = general.toBuilder().setEnableCa(false).build();
        lockExecutor.execute(lockKeys(), () -> {
            store.saveAppGeneralSettingsPB(DEPT, updated, "second-user");
            return null;
        });
        assertThat(store.getAppGeneralSettingsPB(DEPT)).isEqualTo(updated);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM dept_system_settings
            WHERE dept_id = ? AND function_id = ? AND modified_by = ? AND modified_at > ?
            """, Integer.class, DEPT, SettingsStore.GET_DEPT_APP_SETTINGS, "second-user",
            LocalDateTime.of(2020, 1, 1, 0, 0))).isEqualTo(1);
        assertThat(departmentSettingCount()).isEqualTo(7);
    }

    @Test
    void distinguishesMissingSettingsFromStoredDecodeFailures() {
        assertThat(store.getMenuConfigPB(DEPT)).isNull();
        assertThat(store.getMedOrderGroupSettingsPB(DEPT)).isNull();
        assertThat(store.getDeptMonitoringSettingsPB(DEPT)).isNull();
        assertThat(store.getNursingRecordSettingsPB(DEPT)).isNull();
        assertThat(store.getScoreSettingsPB(DEPT)).isNull();
        assertThat(store.getAppGeneralSettingsPB(DEPT)).isNull();
        assertThat(store.getArchiveSettings(DEPT)).isNull();
        insertBrokenSettings();
        assertThatThrownBy(() -> store.getMenuConfigPB(DEPT))
            .isInstanceOf(SettingsStore.DecodeException.class);
        assertThatThrownBy(() -> store.getArchiveSettings(DEPT))
            .isInstanceOf(SettingsStore.DecodeException.class);
        assertThatThrownBy(() -> store.getAppGeneralSettingsPB(DEPT))
            .isInstanceOf(SettingsStore.DecodeException.class);
    }

    @Test
    void businessServicesKeepTheirOriginalFallbackDefaultsAfterDecodeFailures() {
        insertBrokenSettings();
        AppSettingsPB actual = settingService.getAppSettingsForService(DEPT);
        assertThat(actual.getEnableMedicationSpeed()).isEqualTo(
            config.getMedication().getOrderGroupSettings().getEnableMedicationSpeed());
        assertThat(actual.getHeadCustomTimeGraceMinutes()).isZero();
        assertThat(actual.getTailCustomTimeGraceMinutes()).isZero();
        assertThat(actual.getNursingRecordOverwriteCreatedBy()).isFalse();
        assertThat(actual.getScoreAllowEditRecordedBy()).isFalse();
        assertThat(actual.getPrintAgentIpPort()).isEqualTo("127.0.0.1:9123");
        assertThat(actual.getEnableCa()).isFalse();

        MenuConfigPB menu = menuService.loadAndNormalizeMenuConfig(DEPT);
        assertThat(menu.getDeptId()).isEqualTo(DEPT);
        assertThat(store.getMenuConfigPB(DEPT)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_by FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS)).isEmpty();
    }

    @Test
    void menuNormalizationPersistsRepairsAndOnlyAssignsActiveDepartmentTemplates() {
        int firstId = insertTemplate(DEPT, "模板一", false);
        int secondId = insertTemplate(DEPT, "模板二", false);
        int freeId = insertTemplate(DEPT, "未分配模板", false);
        int deletedId = insertTemplate(DEPT, "已删除模板", true);
        int foreignId = insertTemplate(OTHER_DEPT, "其他科室模板", false);
        MenuConfigPB defaults = menuMeta.getConfig();
        int groupId = defaults.getMenuItem(0).getGroupId();
        MenuItem separated = MenuItem.newBuilder().setFunctionId(10000).setType(1)
            .setGroupId(groupId).setSeparatedFormTemplate(template(firstId, "旧模板名")).build();
        MenuItem aggregated = MenuItem.newBuilder().setFunctionId(10001).setType(2)
            .setGroupId(groupId)
            .addAggregatedFormTemplate(template(secondId, "模板二"))
            .addAggregatedFormTemplate(template(deletedId, "已删除模板"))
            .addAggregatedFormTemplate(template(foreignId, "其他科室模板"))
            .build();
        MenuItem invalidFunction = MenuItem.newBuilder().setFunctionId(9999).setType(1)
            .setGroupId(groupId).setSeparatedFormTemplate(template(freeId, "未分配模板")).build();
        store.saveMenuConfigPB(DEPT, defaults.toBuilder().setDeptId(DEPT)
            .addMenuItem(separated).addMenuItem(aggregated).addMenuItem(invalidFunction)
            .addUnassignedFormTemplate(template(firstId, "过期未分配项")).build(), "previous-user");

        MenuConfigPB actual = menuService.loadAndNormalizeMenuConfig(DEPT);

        assertThat(actual.getMenuItemCount()).isEqualTo(defaults.getMenuItemCount() + 2);
        assertThat(actual.getMenuItem(defaults.getMenuItemCount()).getSeparatedFormTemplate())
            .isEqualTo(template(firstId, "模板一"));
        assertThat(actual.getMenuItem(defaults.getMenuItemCount() + 1)
            .getAggregatedFormTemplateList()).containsExactly(template(secondId, "模板二"));
        assertThat(actual.getUnassignedFormTemplateList())
            .containsExactly(template(freeId, "未分配模板"));
        MenuConfigPB saved = store.getMenuConfigPB(DEPT);
        assertThat(saved.getMenuItemList()).isEqualTo(actual.getMenuItemList());
        assertThat(saved.getUnassignedFormTemplateList()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_by FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS)).isEmpty();

        // 再次读取合法配置不应写库，也不应刷新审计字段。
        LocalDateTime marker = LocalDateTime.of(2001, 1, 1, 0, 0);
        jdbcTemplate.update("""
            UPDATE dept_system_settings SET modified_at = ?, modified_by = ?
            WHERE dept_id = ? AND function_id = ?
            """, marker, "unchanged-marker", DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS);
        assertThat(menuService.loadAndNormalizeMenuConfig(DEPT)).isEqualTo(actual);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_at FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, LocalDateTime.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))
            .isEqualTo(marker);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_by FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS))
            .isEqualTo("unchanged-marker");
    }

    @Test
    void participatesInCallerTransactionAndRollsBackSettingsAndLocksTogether() {
        assertThatThrownBy(() -> lockExecutor.execute(lockKeys(), () -> {
            store.saveMenuConfigPB(DEPT, MenuConfigPB.newBuilder().setDeptId(DEPT).build(), "user");
            store.saveAppGeneralSettingsPB(DEPT,
                AppGeneralSettingsPB.newBuilder().setEnableCa(true).build(), "user");
            throw new IllegalStateException("模拟整批设置失败");
        })).isInstanceOf(IllegalStateException.class).hasMessage("模拟整批设置失败");
        assertThat(departmentSettingCount()).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key LIKE ?
            """, Integer.class, "dept_system_settings:" + DEPT + ":%")).isZero();
    }

    @Test
    void storesLogoAsRawBase64AndUpdatesItsAuditMetadata() {
        logoChanged = true;
        systemSettingsRepo.deleteById(SettingsStore.GET_APP_LOGO);
        assertThat(store.getLogoB64()).isNull();
        for (String value : List.of("Zmlyc3Q=", "c2Vjb25k", "")) {
            lockExecutor.execute(List.of(LockKeys.systemSettings(SettingsStore.GET_APP_LOGO)),
                () -> {
                    store.saveLogoB64(value, "logo-user");
                    return null;
                });
            assertThat(store.getLogoB64()).isEqualTo(value);
            SystemSettings saved = systemSettingsRepo.findById(SettingsStore.GET_APP_LOGO)
                .orElseThrow();
            assertThat(saved.getFunctionName()).isEqualTo("GET_APP_LOGO");
            assertThat(saved.getModifiedBy()).isEqualTo("logo-user");
            assertThat(saved.getModifiedAt()).isNotNull();
        }
    }

    private void assertPatientSettingsFailure(int functionId) {
        // 截断原有退出策略，确保损坏配置不会被默认值静默替代。
        try (MockedStatic<LogUtils> logUtils = mockStatic(LogUtils.class)) {
            logUtils.when(() -> LogUtils.flushAndQuit(any(ConfigurableApplicationContext.class)))
                .thenThrow(new IllegalStateException("模拟终止无效配置读取"));
            assertThatThrownBy(() -> getPatientSettings(DEPT, functionId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("模拟终止无效配置读取");
            logUtils.verify(() -> LogUtils.flushAndQuit(any(ConfigurableApplicationContext.class)));
        }
    }

    private DisplayFieldSettingsPB storedPatientSettings(int functionId) {
        String encoded = jdbcTemplate.queryForObject("""
            SELECT settings_pb FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, functionId);
        return ProtoUtils.decodeDisplayFieldSettings(encoded);
    }

    private DisplayFieldSettingsPB getPatientSettings(String deptId, int functionId) {
        return switch (functionId) {
            case SettingsStore.GET_PATIENTS_IN_ICU -> store.getPatientsInIcuSettingsPB(deptId);
            case SettingsStore.GET_PATIENTS_PENDING_ADMISSION ->
                store.getPatientsPendingAdmissionSettingsPB(deptId);
            case SettingsStore.GET_PATIENTS_PENDING_DISCHARGED ->
                store.getPatientsPendingDischargedSettingsPB(deptId);
            case SettingsStore.GET_PATIENTS_DISCHARGED ->
                store.getPatientsDischargedSettingsPB(deptId);
            default -> throw new IllegalArgumentException("无效患者列表功能编号：" + functionId);
        };
    }

    private void savePatientSettings(
        String deptId, DisplayFieldSettingsPB settings, String modifiedBy
    ) {
        switch (settings.getFunctionIdValue()) {
            case SettingsStore.GET_PATIENTS_IN_ICU ->
                store.savePatientsInIcuSettingsPB(deptId, settings, modifiedBy);
            case SettingsStore.GET_PATIENTS_PENDING_ADMISSION ->
                store.savePatientsPendingAdmissionSettingsPB(deptId, settings, modifiedBy);
            case SettingsStore.GET_PATIENTS_PENDING_DISCHARGED ->
                store.savePatientsPendingDischargedSettingsPB(deptId, settings, modifiedBy);
            case SettingsStore.GET_PATIENTS_DISCHARGED ->
                store.savePatientsDischargedSettingsPB(deptId, settings, modifiedBy);
            default -> throw new IllegalArgumentException(
                "无效患者列表功能编号：" + settings.getFunctionIdValue());
        }
    }

    private int insertTemplate(String deptId, String name, boolean deleted) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO dragable_form_templates(dept_id, name, template_pb, is_deleted)
            VALUES (?, ?, '', ?) RETURNING id
            """, Integer.class, deptId, name, deleted);
    }

    private IntStrKvPB template(int id, String name) {
        return IntStrKvPB.newBuilder().setKey(id).setVal(name).build();
    }

    private void insertBrokenSettings() {
        for (int functionId : FUNCTION_IDS) {
            jdbcTemplate.update("""
                INSERT INTO dept_system_settings
                    (dept_id, function_id, settings_pb, modified_at, modified_by)
                VALUES (?, ?, ?, ?, ?)
                """, DEPT, functionId, "broken!", LocalDateTime.now(), "previous-user");
        }
    }

    private List<String> lockKeys() {
        return FUNCTION_IDS.stream().map(id -> LockKeys.deptSystemSettings(DEPT, id)).toList();
    }

    private int departmentSettingCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dept_system_settings WHERE dept_id = ?", Integer.class, DEPT);
    }

    private void clearDepartment() {
        jdbcTemplate.update("DELETE FROM dept_system_settings WHERE dept_id IN (?, ?)",
            DEPT, OTHER_DEPT);
        jdbcTemplate.update("DELETE FROM dragable_form_templates WHERE dept_id IN (?, ?)",
            DEPT, OTHER_DEPT);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "dept_system_settings:" + DEPT + ":%");
    }

    private static final String DEPT = "settings-store-tests";
    private static final String OTHER_DEPT = "settings-store-other-tests";
    private static final List<Integer> PATIENT_FUNCTION_IDS = List.of(
        SettingsStore.GET_PATIENTS_IN_ICU, SettingsStore.GET_PATIENTS_PENDING_ADMISSION,
        SettingsStore.GET_PATIENTS_PENDING_DISCHARGED, SettingsStore.GET_PATIENTS_DISCHARGED);
    private static final List<Integer> FUNCTION_IDS = List.of(
        SettingsStore.GET_MEDICAL_ORDER_SETTINGS, SettingsStore.GET_DEPT_MENU_SETTINGS,
        SettingsStore.GET_DEPT_MONITORING_SETTINGS, SettingsStore.GET_DEPT_NURSING_RECORD_SETTINGS,
        SettingsStore.GET_DEPT_NURSING_SCORE_SETTINGS, SettingsStore.GET_DEPT_APP_SETTINGS,
        SettingsStore.GET_ARCHIVE_CONFIG);

    private SystemSettings previousLogo;
    private boolean logoLockExisted;
    private boolean logoChanged;

    @Autowired private SettingsStore store;
    @Autowired private SettingService settingService;
    @Autowired private MenuService menuService;
    @Autowired private MenuMeta menuMeta;
    @Autowired private LockExecutor lockExecutor;
    @Autowired private SystemSettingsRepository systemSettingsRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Config config;
}
