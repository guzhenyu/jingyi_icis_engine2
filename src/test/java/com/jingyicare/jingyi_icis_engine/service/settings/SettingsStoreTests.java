package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.DeptMonitoringSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppGeneralSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.DeptArchiveSettings;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.MenuService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

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

        MenuConfigPB menu = menuService.getNormalizedMenuConfig(DEPT);
        assertThat(menu.getDeptId()).isEqualTo(DEPT);
        assertThat(store.getMenuConfigPB(DEPT)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT modified_by FROM dept_system_settings WHERE dept_id = ? AND function_id = ?
            """, String.class, DEPT, SettingsStore.GET_DEPT_MENU_SETTINGS)).isEmpty();
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
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "dept_system_settings:" + DEPT + ":%");
    }

    private static final String DEPT = "settings-store-tests";
    private static final String OTHER_DEPT = "settings-store-other-tests";
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
    @Autowired private LockExecutor lockExecutor;
    @Autowired private SystemSettingsRepository systemSettingsRepo;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Config config;
}
