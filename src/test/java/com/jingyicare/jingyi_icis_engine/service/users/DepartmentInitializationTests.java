package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecordTemplate;
import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.DisplayFieldSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BgaMetaService;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingOrderTemplateService;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreMetaService;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class DepartmentInitializationTests extends TestsBase {
    @AfterEach
    void cleanUp() {
        for (String deptId : List.of(FIRST, SECOND)) {
            jdbc.update("DELETE FROM dept_system_settings WHERE dept_id = ?", deptId);
            jdbc.update("DELETE FROM bga_params WHERE dept_id = ?", deptId);
            jdbc.update("DELETE FROM dept_score_groups WHERE dept_id = ?", deptId);
            jdbc.update("DELETE FROM nursing_record_templates WHERE entity_id = ?", deptId);
            jdbc.update("DELETE FROM nursing_record_template_groups WHERE entity_id = ?", deptId);
            jdbc.update("DELETE FROM departments WHERE dept_id = ?", deptId);
            jdbc.update("DELETE FROM rbac_departments WHERE dept_id = ?", deptId);
            jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key LIKE ?",
                "%:" + deptId + "%");
        }
    }

    @Test
    void startupAndNewDepartmentUseTheSameDefaultsAndRetryPreservesRows() {
        seedDepartment(FIRST);
        // getter 能回退到默认值，不代表数据库已有记录，初始化仍须补齐四行。
        List<DisplayFieldSettingsPB> resolvedSettings = List.of(
            settingsStore.getPatientsInIcuSettingsPB(FIRST),
            settingsStore.getPatientsPendingAdmissionSettingsPB(FIRST),
            settingsStore.getPatientsPendingDischargedSettingsPB(FIRST),
            settingsStore.getPatientsDischargedSettingsPB(FIRST));
        for (DisplayFieldSettingsPB settings : resolvedSettings) {
            assertThat(settingsStore.hasDeptSettings(FIRST, settings.getFunctionIdValue()))
                .isFalse();
        }
        assertThat(patientSettingsRows(FIRST)).isEmpty();
        assertThat(commands.initDept(FIRST, UserQuery.SYSTEM_ACCOUNT_ID)).isEqualTo(StatusCode.OK);
        assertThat(commands.addDepartment(department(SECOND), "123"))
            .isEqualTo(StatusCode.OK);
        assertDefaults(FIRST);
        assertDefaults(SECOND);

        assertThat(patientSettingsRows(FIRST)).allSatisfy(row -> {
            assertThat(row.get("modified_by")).isEqualTo(UserQuery.SYSTEM_ACCOUNT_ID);
            assertThat(row.get("modified_at")).isNotNull();
        });
        assertThat(patientSettingsRows(SECOND)).allSatisfy(row -> {
            assertThat(row.get("modified_by")).isEqualTo("123");
            assertThat(row.get("modified_at")).isNotNull();
        });
        List<List<Integer>> before = defaultIds(FIRST);
        List<Map<String, Object>> settingsBefore = patientSettingsRows(FIRST);
        assertThat(commands.initDept(FIRST, "123")).isEqualTo(StatusCode.OK);
        assertThat(defaultIds(FIRST)).isEqualTo(before);
        assertThat(patientSettingsRows(FIRST)).isEqualTo(settingsBefore);
    }

    @Test
    void retryPreservesCustomSettingsAndAuditFieldsWhileFillingOnlyMissingRows() {
        seedDepartment(FIRST);
        assertThat(commands.initDept(FIRST, "123")).isEqualTo(StatusCode.OK);
        LocalDateTime marker = LocalDateTime.of(2001, 1, 1, 0, 0);
        for (DisplayFieldSettingsPB defaults
                : config.getConfig().getPatient().getDefaultDisplayFieldSettingsList()) {
            DisplayFieldSettingsPB custom = defaults.toBuilder()
                .setFunctionName("科室自定义" + defaults.getFunctionIdValue()).build();
            jdbc.update("""
                UPDATE dept_system_settings SET settings_pb = ?, modified_at = ?, modified_by = ?
                WHERE dept_id = ? AND function_id = ?
                """, Base64.getEncoder().encodeToString(custom.toByteArray()), marker,
                "original-user", FIRST, defaults.getFunctionIdValue());
        }
        List<Map<String, Object>> customRows = patientSettingsRows(FIRST);
        assertThat(commands.initDept(FIRST, "retry-user")).isEqualTo(StatusCode.OK);
        assertThat(patientSettingsRows(FIRST)).isEqualTo(customRows);

        jdbc.update("DELETE FROM dept_system_settings WHERE dept_id = ? AND function_id = ?",
            FIRST, SettingsStore.GET_PATIENTS_DISCHARGED);
        assertThat(commands.initDept(FIRST, "retry-user")).isEqualTo(StatusCode.OK);
        assertThat(patientSettingsRows(FIRST).subList(0, 3))
            .isEqualTo(customRows.subList(0, 3));
        DisplayFieldSettingsPB dischargedDefault = config.getConfig().getPatient()
            .getDefaultDisplayFieldSettingsList().stream()
            .filter(setting -> setting.getFunctionIdValue()
                == SettingsStore.GET_PATIENTS_DISCHARGED)
            .findFirst().orElseThrow();
        assertThat(patientSettingsRows(FIRST).get(3).get("settings_pb"))
            .isEqualTo(Base64.getEncoder().encodeToString(dischargedDefault.toByteArray()));
        assertThat(patientSettingsRows(FIRST).get(3).get("modified_by"))
            .isEqualTo("retry-user");
    }

    @Test
    void retryPreservesExistingUndecodableSettings() {
        seedDepartment(FIRST);
        LocalDateTime marker = LocalDateTime.of(2001, 1, 1, 0, 0);
        for (int functionId : List.of(1, 2, 3, 4)) {
            jdbc.update("""
                INSERT INTO dept_system_settings
                    (dept_id, function_id, settings_pb, modified_at, modified_by)
                VALUES (?, ?, ?, ?, ?)
                """, FIRST, functionId, "broken!", marker, "original-user");
        }
        List<Map<String, Object>> before = patientSettingsRows(FIRST);

        try (MockedStatic<ProtoUtils> protoUtils =
                mockStatic(ProtoUtils.class, CALLS_REAL_METHODS)) {
            protoUtils.when(() -> ProtoUtils.decodeDisplayFieldSettings(anyString()))
                .thenThrow(new AssertionError("初始化不应解析已有的患者显示配置"));
            assertThat(commands.initDept(FIRST, "retry-user")).isEqualTo(StatusCode.OK);
            protoUtils.verify(() -> ProtoUtils.decodeDisplayFieldSettings(anyString()), never());
        }

        assertThat(patientSettingsRows(FIRST)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 5, 6, 999})
    void invalidPatientSettingFunctionIdDoesNotWriteAnyDefaults(int functionId) {
        seedDepartment(FIRST);
        Config.Builder invalidConfig = config.getConfig().toBuilder();
        invalidConfig.getPatientBuilder().addDefaultDisplayFieldSettings(
            DisplayFieldSettingsPB.newBuilder().setFunctionIdValue(functionId));
        ProtoConfig invalid = mock(ProtoConfig.class);
        when(invalid.getConfig()).thenReturn(invalidConfig.build());
        DepartmentCommand command = new DepartmentCommand(
            deptRepo, rbacDeptRepo, rbacDeptAcctRepo, deptAcctRepo,
            bga, scores, templates, locks, invalid, settingsStore);

        assertThatThrownBy(() -> command.initDept(FIRST, UserQuery.SYSTEM_ACCOUNT_ID))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(defaultIds(FIRST)).allSatisfy(ids -> assertThat(ids).isEmpty());
    }

    @Test
    void templateFailureRollsBackAllDefaultsButKeepsDepartmentForIndependentRetry() {
        NursingRecordTemplateRepository failing = mock(
            NursingRecordTemplateRepository.class, delegatesTo(templateRepo));
        doAnswer(invocation -> {
            Iterable<NursingRecordTemplate> templates = invocation.getArgument(0);
            templateRepo.saveAllAndFlush(templates);
            throw new IllegalStateException("模拟护理模板保存失败");
        }).when(failing).saveAll(any());
        NursingOrderTemplateService failingTemplates = new NursingOrderTemplateService(
            config, userQuery, groupRepo, failing, locks);
        DepartmentCommand command = command(deptRepo, failingTemplates);

        assertThatThrownBy(() -> command.addDepartment(department(FIRST), "123"))
            .isInstanceOf(IllegalStateException.class).hasMessage("模拟护理模板保存失败");

        assertThat(deptRepo.findByDeptIdAndIsDeletedFalse(FIRST))
            .hasValueSatisfying(dept -> assertThat(dept.getModifiedBy()).isEqualTo("123"));
        assertThat(rbacDeptRepo.findByDeptId(FIRST)).isPresent();
        assertThat(defaultIds(FIRST)).allSatisfy(ids -> assertThat(ids).isEmpty());

        assertThat(commands.initDept(FIRST, "123")).isEqualTo(StatusCode.OK);
        assertDefaults(FIRST);
    }

    @Test
    void departmentFailureRollsBackCreationWithoutInitializingDefaults() {
        DepartmentRepository failing = mock(DepartmentRepository.class, delegatesTo(deptRepo));
        doAnswer(invocation -> {
            Department dept = invocation.getArgument(0);
            deptRepo.saveAndFlush(dept);
            throw new IllegalStateException("模拟科室保存失败");
        }).when(failing).save(any(Department.class));

        assertThatThrownBy(() -> command(failing, templates)
            .addDepartment(department(FIRST), "123")).hasMessage("模拟科室保存失败");
        assertThat(deptRepo.findByDeptIdAndIsDeletedFalse(FIRST)).isEmpty();
        assertThat(rbacDeptRepo.findByDeptId(FIRST)).isEmpty();
        assertThat(defaultIds(FIRST)).allSatisfy(ids -> assertThat(ids).isEmpty());
    }

    @Test
    void missingDepartmentDoesNotReceiveDefaults() {
        assertThat(commands.initDept(FIRST, "123")).isEqualTo(StatusCode.DEPARTMENT_NOT_FOUND);
        assertThat(commands.initDept(" ", "123")).isEqualTo(StatusCode.DEPT_IS_EMPTY);
        assertThat(defaultIds(FIRST)).allSatisfy(ids -> assertThat(ids).isEmpty());
    }

    @Test
    void moduleInitializersRequireTheCallerTransaction() {
        assertThatThrownBy(() -> bga.initDept(FIRST))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> scores.initDept(FIRST))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> templates.initDept(FIRST))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "department", "bga", "score", "templateGroup", "template",
        "patientsInIcu", "patientsPendingAdmission", "patientsPendingDischarged",
        "patientsDischarged"
    })
    void initializationSharesWriterLocksAndConcurrentRetriesDoNotDuplicate(String resource)
            throws Exception {
        seedDepartment(FIRST);
        NursingRecordTemplateGroupPB group = config.getConfig().getNursingRecord()
            .getDefaultDeptGroup(0);
        String key = switch (resource) {
            case "department" -> LockKeys.department(FIRST);
            case "bga" -> LockKeys.bgaParam(FIRST);
            case "score" -> LockKeys.deptScoreGroup(FIRST);
            case "templateGroup" -> LockKeys.nursingRecordTemplateGroup(1, FIRST, group.getName());
            case "patientsInIcu" -> LockKeys.deptSystemSettings(
                FIRST, SettingsStore.GET_PATIENTS_IN_ICU);
            case "patientsPendingAdmission" -> LockKeys.deptSystemSettings(
                FIRST, SettingsStore.GET_PATIENTS_PENDING_ADMISSION);
            case "patientsPendingDischarged" -> LockKeys.deptSystemSettings(
                FIRST, SettingsStore.GET_PATIENTS_PENDING_DISCHARGED);
            case "patientsDischarged" -> LockKeys.deptSystemSettings(
                FIRST, SettingsStore.GET_PATIENTS_DISCHARGED);
            default -> LockKeys.nursingRecordTemplate(
                1, FIRST, group.getNursingRecordTemplate(0).getName());
        };
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> locks.execute(List.of(key), () -> {
                acquired.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放初始化业务锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertTrue(acquired.await(10, TimeUnit.SECONDS), "未取得初始化业务锁");
            Future<StatusCode> first = pool.submit(() -> commands.initDept(FIRST, "123"));
            Future<StatusCode> second = pool.submit(() -> commands.initDept(FIRST, "123"));
            assertTrue(waitForBlockedRequests(), "初始化应等待已有业务锁");
            assertThat(defaultIds(FIRST)).allSatisfy(ids -> assertThat(ids).isEmpty());
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            assertThat(first.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK);
            assertThat(second.get(15, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK);
            assertDefaults(FIRST);
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private boolean waitForBlockedRequests() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                    AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void seedDepartment(String deptId) {
        jdbc.update("""
            INSERT INTO departments(dept_id, name, abbreviation, hospital_name, is_deleted)
            VALUES (?, ?, ?, ?, false)
            """, deptId, deptId, "初始化测试", "测试医院");
        jdbc.update("INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)",
            deptId, deptId);
    }

    private void assertDefaults(String deptId) {
        List<Map<String, Object>> settingsRows = patientSettingsRows(deptId);
        Map<Integer, String> storedSettings = new HashMap<>();
        for (Map<String, Object> row : settingsRows) {
            storedSettings.put((Integer) row.get("function_id"), (String) row.get("settings_pb"));
        }
        for (DisplayFieldSettingsPB defaults
                : config.getConfig().getPatient().getDefaultDisplayFieldSettingsList()) {
            assertThat(storedSettings).containsEntry(defaults.getFunctionIdValue(),
                Base64.getEncoder().encodeToString(defaults.toByteArray()));
        }
        assertThat(settingsRows).hasSize(4);
        assertThat(jdbc.queryForList("""
            SELECT monitoring_param_code FROM bga_params
            WHERE dept_id = ? AND is_deleted = false ORDER BY display_order
            """, String.class, deptId)).containsExactlyElementsOf(
                config.getConfig().getBga().getBgaParamCodeList().stream().distinct().toList());
        assertThat(jdbc.queryForList("""
            SELECT score_group_code FROM dept_score_groups
            WHERE dept_id = ? AND is_deleted = false ORDER BY display_order
            """, String.class, deptId)).containsExactlyElementsOf(
                config.getConfig().getScore().getDefaultScoreGroupCodeList());
        List<NursingRecordTemplateGroupPB> groups = config.getConfig().getNursingRecord()
            .getDefaultDeptGroupList();
        assertThat(groupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId))
            .extracting(group -> group.getName()).containsExactlyInAnyOrderElementsOf(
                groups.stream().map(NursingRecordTemplateGroupPB::getName).toList());
        assertThat(templateRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(1, deptId))
            .hasSize(groups.stream().mapToInt(
                NursingRecordTemplateGroupPB::getNursingRecordTemplateCount).sum());
    }

    private List<List<Integer>> defaultIds(String deptId) {
        return List.of(
            jdbc.queryForList("SELECT function_id FROM dept_system_settings "
                + "WHERE dept_id = ? ORDER BY function_id", Integer.class, deptId),
            jdbc.queryForList("SELECT id FROM bga_params WHERE dept_id = ? ORDER BY id",
                Integer.class, deptId),
            jdbc.queryForList("SELECT id FROM dept_score_groups WHERE dept_id = ? ORDER BY id",
                Integer.class, deptId),
            jdbc.queryForList("SELECT id FROM nursing_record_template_groups "
                + "WHERE entity_id = ? ORDER BY id", Integer.class, deptId),
            jdbc.queryForList("SELECT id FROM nursing_record_templates "
                + "WHERE entity_id = ? ORDER BY id", Integer.class, deptId));
    }

    private List<Map<String, Object>> patientSettingsRows(String deptId) {
        return jdbc.queryForList("""
            SELECT function_id, settings_pb, modified_at, modified_by
            FROM dept_system_settings WHERE dept_id = ? ORDER BY function_id
            """, deptId);
    }

    private DepartmentPB department(String deptId) {
        return DepartmentPB.newBuilder().setDeptId(deptId).setDeptName(deptId)
            .setAbbreviation("初始化测试").setHospitalName("测试医院").build();
    }

    private DepartmentCommand command(
        DepartmentRepository repository, NursingOrderTemplateService templateService
    ) {
        return new DepartmentCommand(repository, rbacDeptRepo, rbacDeptAcctRepo, deptAcctRepo,
            bga, scores, templateService, locks, config, settingsStore);
    }

    private static final String FIRST = "department-init-tests-first";
    private static final String SECOND = "department-init-tests-second";
    @Autowired private DepartmentCommand commands;
    @Autowired private DepartmentRepository deptRepo;
    @Autowired private RbacDepartmentRepository rbacDeptRepo;
    @Autowired private RbacDepartmentAccountRepository rbacDeptAcctRepo;
    @Autowired private DepartmentAccountRepository deptAcctRepo;
    @Autowired private ProtoConfig config;
    @Autowired private UserQuery userQuery;
    @Autowired private BgaMetaService bga;
    @Autowired private ScoreMetaService scores;
    @Autowired private SettingsStore settingsStore;
    @Autowired private NursingOrderTemplateService templates;
    @Autowired private NursingRecordTemplateGroupRepository groupRepo;
    @Autowired private NursingRecordTemplateRepository templateRepo;
    @Autowired private LockExecutor locks;
    @Autowired private JdbcTemplate jdbc;
}
