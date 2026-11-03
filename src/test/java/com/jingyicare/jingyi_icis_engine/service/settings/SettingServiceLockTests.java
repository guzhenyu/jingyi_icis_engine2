package com.jingyicare.jingyi_icis_engine.service.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettingsId;
import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkPagePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.DeptMonitoringSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.repository.reports.DragableFormTemplateRepository;
import com.jingyicare.jingyi_icis_engine.repository.settings.DeptSystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreConfig;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class SettingServiceLockTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanDepartment();
        jdbcTemplate.update(
            "INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)", DEPT_ID, DEPT_ID);
        jdbcTemplate.update(
            "INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)", ACCOUNT, ACCOUNT);
        previousLogo = systemSettingsRepo.findById(LOGO_FUNCTION_ID).orElse(null);
        logoLockExisted = lockCount(LockKeys.systemSettings(LOGO_FUNCTION_ID)) > 0;
        authenticate();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        cleanDepartment();
        systemSettingsRepo.deleteById(LOGO_FUNCTION_ID);
        if (previousLogo != null) systemSettingsRepo.save(previousLogo);
        if (!logoLockExisted) {
            jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                LockKeys.systemSettings(LOGO_FUNCTION_ID));
        }
    }

    @Test
    void updatesAllRequestedSettingsInOneTransaction() {
        var settings = AppSettingsPB.newBuilder().setEnableMedicationSpeed(true)
            .setHeadCustomTimeGraceMinutes(7).setTailCustomTimeGraceMinutes(13)
            .setNursingRecordOverwriteCreatedBy(true).setScoreAllowEditRecordedBy(true)
            .setJfkUseNativePrint(true).setPrintAgentIpPort("10.0.0.8:9123")
            .setCheckFutureTime(true).setEnableCa(true).build();

        assertOk(updateApp(settings, AppSettingTypeEnum.AST_ENABLE_MEDICATION_SPEED,
            AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES,
            AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES,
            AppSettingTypeEnum.AST_NURSING_RECORD_OVERWRITE_CREATED_BY,
            AppSettingTypeEnum.AST_SCORE_ALLOW_EDIT_RECORDED_BY,
            AppSettingTypeEnum.AST_GENERAL, AppSettingTypeEnum.AST_ENABLE_CA));

        var actual = service.getAppSettingsForService(DEPT_ID);
        assertThat(actual).isEqualTo(settings);
        assertThat(jdbcTemplate.queryForList("""
            SELECT function_id FROM dept_system_settings WHERE dept_id = ? ORDER BY function_id
            """, Integer.class, DEPT_ID)).containsExactly(6, 8, 9, 10, 11);
        for (int functionId : List.of(6, 8, 9, 10, 11)) {
            assertThat(lockCount(LockKeys.deptSystemSettings(DEPT_ID, functionId))).isEqualTo(1);
        }
    }

    @Test
    void nursingSettingsReturnDefaultsAndPersistUpdatesWithMetadata() {
        assertThat(service.getNursingRecordSettings(DEPT_ID))
            .isEqualTo(NursingRecordSettingsPB.getDefaultInstance());
        var type = AppSettingTypeEnum.AST_NURSING_RECORD_OVERWRITE_CREATED_BY;
        assertOk(updateApp(AppSettingsPB.newBuilder()
            .setNursingRecordOverwriteCreatedBy(true).build(), type));
        assertThat(service.getNursingRecordSettings(DEPT_ID).getEnableUpdatingCreatedBy()).isTrue();

        var id = new DeptSystemSettingsId(
            DEPT_ID, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS_VALUE);
        var created = deptSettingsRepo.findById(id).orElseThrow();
        assertThat(created.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(created.getModifiedAt()).isNotNull();

        LocalDateTime oldTime = LocalDateTime.of(2020, 1, 1, 0, 0);
        created.setModifiedAt(oldTime);
        created.setModifiedBy("previous-user");
        deptSettingsRepo.save(created);
        assertOk(updateApp(AppSettingsPB.getDefaultInstance(), type));

        assertThat(service.getNursingRecordSettings(DEPT_ID).getEnableUpdatingCreatedBy())
            .isFalse();
        var updated = deptSettingsRepo.findById(id).orElseThrow();
        assertThat(updated.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(updated.getModifiedAt()).isAfter(oldTime);
        assertThat(departmentSettingsCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentGeneralAndCaUpdatesPreserveBothChanges(boolean settingsExist) throws Exception {
        if (settingsExist) {
            assertOk(updateApp(AppSettingsPB.getDefaultInstance(), AppSettingTypeEnum.AST_GENERAL));
        }
        var general = AppSettingsPB.newBuilder().setJfkUseNativePrint(true)
            .setPrintAgentIpPort("10.0.0.8:9123").setCheckFutureTime(true).build();
        var ca = AppSettingsPB.newBuilder().setEnableCa(true).build();

        runContendingUpdates(LockKeys.deptSystemSettings(DEPT_ID, 11),
            () -> updateApp(general, AppSettingTypeEnum.AST_GENERAL),
            () -> updateApp(ca, AppSettingTypeEnum.AST_ENABLE_CA));

        var actual = service.getAppSettingsForService(DEPT_ID);
        assertThat(actual.getJfkUseNativePrint()).isTrue();
        assertThat(actual.getPrintAgentIpPort()).isEqualTo("10.0.0.8:9123");
        assertThat(actual.getCheckFutureTime()).isTrue();
        assertThat(actual.getEnableCa()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentMonitoringUpdatesPreserveBothChanges(boolean settingsExist) throws Exception {
        if (settingsExist) {
            assertOk(updateApp(AppSettingsPB.getDefaultInstance(),
                AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES));
        }
        var head = AppSettingsPB.newBuilder().setHeadCustomTimeGraceMinutes(7).build();
        var tail = AppSettingsPB.newBuilder().setTailCustomTimeGraceMinutes(13).build();

        runContendingUpdates(LockKeys.deptSystemSettings(DEPT_ID, 8),
            () -> updateApp(head, AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES),
            () -> updateApp(tail, AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES));

        var actual = service.getAppSettingsForService(DEPT_ID);
        assertThat(actual.getHeadCustomTimeGraceMinutes()).isEqualTo(7);
        assertThat(actual.getTailCustomTimeGraceMinutes()).isEqualTo(13);
    }

    @Test
    void concurrentFirstArchiveUpdatesBothSucceed() throws Exception {
        var template = templates.save(DragableFormTemplate.builder().deptId(DEPT_ID)
            .name("病室报告").isDeleted(false).templatePb(ProtoUtils.encodeJfkTemplate(
                JfkTemplatePB.newBuilder().addPages(JfkPagePB.newBuilder()).build())).build());
        var request = ProtoUtils.protoToJson(UpdateArchiveConfigReq.newBuilder()
            .setSettings(DeptArchiveSettings.newBuilder().setDeptId(DEPT_ID)
                .setWardReportTemplateId(template.getId())).build());

        runContendingUpdates(LockKeys.deptSystemSettings(DEPT_ID, 14),
            () -> service.updateArchiveConfig(request), () -> service.updateArchiveConfig(request));

        var requestToRead = GetArchiveConfigReq.newBuilder().setDeptId(DEPT_ID).build();
        var actual = service.getArchiveConfig(ProtoUtils.protoToJson(requestToRead));
        assertThat(actual.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        assertThat(actual.getSettings().getWardReportTemplateId()).isEqualTo(template.getId());
        assertThat(departmentSettingsCount()).isEqualTo(1);
    }

    @Test
    void concurrentFirstLogoUpdatesBothSucceed() throws Exception {
        systemSettingsRepo.deleteById(LOGO_FUNCTION_ID);
        var first = ProtoUtils.protoToJson(UpdateLogoReq.newBuilder()
            .setCustomLogoB64("  Zmlyc3Q=  ").build());
        var second = ProtoUtils.protoToJson(UpdateLogoReq.newBuilder()
            .setCustomLogoB64("  c2Vjb25k  ").build());

        runContendingUpdates(LockKeys.systemSettings(LOGO_FUNCTION_ID),
            () -> service.updateLogo(first), () -> service.updateLogo(second));

        var actual = systemSettingsRepo.findById(LOGO_FUNCTION_ID).orElseThrow();
        assertThat(actual.getSettingsPb()).isIn("Zmlyc3Q=", "c2Vjb25k");
        assertThat(actual.getModifiedBy()).isEqualTo(ACCOUNT);
        assertThat(actual.getFunctionName()).isEqualTo("GET_APP_LOGO");
    }

    @Test
    void failedLaterSettingRollsBackEarlierSettingAndNewLocks() {
        MonitoringConfig failingConfig = mock(MonitoringConfig.class);
        when(failingConfig.getDeptMonitoringSettings(DEPT_ID))
            .thenReturn(DeptMonitoringSettingsPB.getDefaultInstance());
        doThrow(new IllegalStateException("保存观察项设置失败")).when(failingConfig)
            .setDeptMonitoringSettings(eq(DEPT_ID), any(), eq(ACCOUNT));
        var failingService = new SettingService(protoService, users, medConfig, failingConfig,
            scoreConfig, deptSettingsRepo, systemSettingsRepo, templates, executor);
        var request = UpdateAppSettingsReq.newBuilder().setDeptId(DEPT_ID)
            .addChangedType(AppSettingTypeEnum.AST_ENABLE_MEDICATION_SPEED)
            .addChangedType(AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES)
            .setSettings(AppSettingsPB.newBuilder().setEnableMedicationSpeed(true)).build();

        assertThrows(IllegalStateException.class,
            () -> failingService.updateAppSettings(ProtoUtils.protoToJson(request)));

        assertThat(departmentSettingsCount()).isZero();
        assertThat(lockCount(LockKeys.deptSystemSettings(DEPT_ID, 6))).isZero();
        assertThat(lockCount(LockKeys.deptSystemSettings(DEPT_ID, 8))).isZero();
    }

    private void runContendingUpdates(
        String key, Supplier<GenericResp> first, Supplier<GenericResp> second
    ) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(List.of(key), () -> {
                locked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放业务锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得业务锁");
            Future<GenericResp> firstResult = pool.submit(() -> authenticated(first));
            Future<GenericResp> secondResult = pool.submit(() -> authenticated(second));
            // 两个请求都停在业务锁处，释放前不会更新配置行。
            assertTrue(waitForTwoBlockedRequests(), "两个请求应同时等待配置业务锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertOk(firstResult.get(15, TimeUnit.SECONDS));
            assertOk(secondResult.get(15, TimeUnit.SECONDS));
            assertThat(lockCount(key)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private boolean waitForTwoBlockedRequests() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting == 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private GenericResp authenticated(Supplier<GenericResp> action) {
        authenticate();
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ACCOUNT, "", List.of()));
    }

    private GenericResp updateApp(AppSettingsPB settings, AppSettingTypeEnum... types) {
        return service.updateAppSettings(ProtoUtils.protoToJson(UpdateAppSettingsReq.newBuilder()
            .setDeptId(DEPT_ID).setSettings(settings).addAllChangedType(List.of(types)).build()));
    }

    private void assertOk(GenericResp response) {
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
    }

    private int lockCount(String key) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, key);
    }

    private int departmentSettingsCount() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM dept_system_settings WHERE dept_id = ?", Integer.class, DEPT_ID);
    }

    private void cleanDepartment() {
        jdbcTemplate.update("DELETE FROM dept_system_settings WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("DELETE FROM dragable_form_templates WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", DEPT_ID);
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
            "dept_system_settings:" + DEPT_ID + ":%");
    }

    private static final String DEPT_ID = "setting-lock-tests";
    private static final String ACCOUNT = "setting-lock-tests-user";
    private static final int LOGO_FUNCTION_ID = SystemSettingFunctionId.GET_APP_LOGO_VALUE;
    private SystemSettings previousLogo;
    private boolean logoLockExisted;

    @Autowired private SettingService service;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ConfigProtoService protoService;
    @Autowired private UserService users;
    @Autowired private MedicationConfig medConfig;
    @Autowired private ScoreConfig scoreConfig;
    @Autowired private DeptSystemSettingsRepository deptSettingsRepo;
    @Autowired private SystemSettingsRepository systemSettingsRepo;
    @Autowired private DragableFormTemplateRepository templates;
}
