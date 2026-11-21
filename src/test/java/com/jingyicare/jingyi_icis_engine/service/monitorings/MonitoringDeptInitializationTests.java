package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaService;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class MonitoringDeptInitializationTests extends TestsBase {
    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM patient_monitoring_groups WHERE pid = ?", PID);
        jdbcTemplate.update("""
            DELETE FROM dept_monitoring_group_params
            WHERE dept_monitoring_group_id IN (
                SELECT id FROM dept_monitoring_groups WHERE dept_id = ?)
            """, DEPT);
        jdbcTemplate.update("DELETE FROM dept_monitoring_groups WHERE dept_id = ?", DEPT);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
            LockKeys.deptMonitoringGroups(DEPT));
    }

    @Test
    void shouldInitializeBothGroupTypesAndPreserveRepeatedInitialization() {
        MonitoringPB config = configuration();
        MonitoringMetaService service = configuredService(config);
        LocalDateTime before = TimeUtils.getNowUtc().minusSeconds(1);
        initialize(service);
        LocalDateTime after = TimeUtils.getNowUtc().plusSeconds(1);

        List<DeptMonitoringGroup> initialized = activeGroups();
        assertThat(initialized).hasSize(4);
        assertGroups(config.getBalanceGroupList(),
            MonEnums.GROUP_TYPE_BALANCE, initialized, before, after);
        assertGroups(config.getMonitoringGroupList(),
            MonEnums.GROUP_TYPE_MONITORING, initialized, before, after);
        List<DeptMonitoringGroupParam> initializedParams = groupParams(initialized);
        assertThat(initializedParams).hasSize(8);
        assertThat(jdbcTemplate.queryForList(
            "SELECT resource_key FROM app_transaction_locks WHERE resource_key = ?",
            String.class, LockKeys.deptMonitoringGroups(DEPT)))
            .containsExactly(LockKeys.deptMonitoringGroups(DEPT));

        initialize(service);

        assertThat(activeGroups()).containsExactlyElementsOf(initialized);
        assertThat(groupParams(initialized)).containsExactlyElementsOf(initializedParams);
    }

    @Test
    void shouldPreserveCustomGroupsWithoutRestoringDeletedDefaults() {
        MonitoringMetaService service = configuredService(configuration());
        initialize(service);
        List<DeptMonitoringGroup> initialized = activeGroups();
        DeptMonitoringGroup custom = initialized.getFirst();
        custom.setName("科室自定义分组");
        custom.setDisplayOrder(27);
        custom.setModifiedBy("custom-account");
        DeptMonitoringGroup removed = initialized.getLast();
        removed.setIsDeleted(true);
        removed.setDeletedBy("custom-account");
        removed.setDeletedAt(TimeUtils.roundToMicros(TimeUtils.getNowUtc()));
        groups.saveAllAndFlush(List.of(custom, removed));
        List<DeptMonitoringGroup> expectedGroups = activeGroups();
        List<DeptMonitoringGroupParam> expectedParams = groupParams(initialized);

        initialize(service);

        assertThat(activeGroups()).containsExactlyElementsOf(expectedGroups);
        assertThat(groups.findById(removed.getId())).contains(removed);
        assertThat(groupParams(initialized)).containsExactlyElementsOf(expectedParams);
    }

    @Test
    void shouldRequireCallerTransactionForInitializationAndReset() {
        assertThatThrownBy(() -> monitoringMetaService.initDept(DEPT))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> monitoringMetaService.resetDeptMonitoringGroups(DEPT, "editor"))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(activeGroups()).isEmpty();
    }

    @Test
    void shouldRollBackBothGroupTypesAndParamsWhenLaterInsertFails() {
        MonitoringPB invalid = configuration().toBuilder()
            .setMonitoringGroup(1, group("x".repeat(256))).build();
        MonitoringMetaService service = configuredService(invalid);
        long originalParamCount = groupParamRepository.count();

        // 平衡组及其参数已写入后，观察项组的超长名称触发 PostgreSQL 约束错误。
        assertThatThrownBy(() -> initialize(service))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(activeGroups()).isEmpty();
        assertThat(groupParamRepository.count()).isEqualTo(originalParamCount);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT COUNT(*) FROM dept_monitoring_group_params p
            JOIN dept_monitoring_groups g ON g.id = p.dept_monitoring_group_id
            WHERE g.dept_id = ?
            """, Integer.class, DEPT)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM app_transaction_locks WHERE resource_key = ?",
            Integer.class, LockKeys.deptMonitoringGroups(DEPT))).isZero();
    }

    @Test
    void shouldReturnPersistedGroupsWhenResetInitializesAnEmptyDepartment() {
        List<DeptMonitoringGroup> initialized = lockExecutor.execute(
            List.of(LockKeys.deptMonitoringGroups(DEPT)),
            () -> monitoringMetaService.resetDeptMonitoringGroups(DEPT, "editor"));

        assertThat(initialized).isNotEmpty();
        assertPersistedGroups(initialized);
        assertThat(groupParams(initialized)).isNotEmpty();
    }

    @Test
    void shouldKeepPatientReferencesOnOldVersionWhenResetCopiesGroups() {
        // 测试建表只生成普通索引；补齐生产唯一约束并限定到本测试科室。
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX monitoring_dept_init_active_name_test_idx
            ON dept_monitoring_groups (dept_id, name)
            WHERE is_deleted = FALSE AND dept_id = '%s'
            """.formatted(DEPT));
        try {
            MonitoringMetaService service = configuredService(configuration());
            initialize(service);
            List<DeptMonitoringGroup> originalGroups = activeGroups();
            List<DeptMonitoringGroupParam> originalParams = groupParams(originalGroups);
            PatientMonitoringGroup patientGroup = patientGroups.saveAndFlush(
                PatientMonitoringGroup.builder().pid(PID)
                    .deptMonitoringGroupId(originalGroups.getFirst().getId()).isDeleted(false)
                    .modifiedBy("nurse")
                    .modifiedAt(TimeUtils.roundToMicros(TimeUtils.getNowUtc())).build());

            List<DeptMonitoringGroup> copied = lockExecutor.execute(
                List.of(LockKeys.deptMonitoringGroups(DEPT)),
                () -> service.resetDeptMonitoringGroups(DEPT, "editor"));

            assertThat(copied).hasSameSizeAs(originalGroups);
            assertPersistedGroups(copied);
            for (DeptMonitoringGroup original : originalGroups) {
                DeptMonitoringGroup retired = groups.findById(original.getId()).orElseThrow();
                assertThat(retired.getIsDeleted()).isTrue();
                assertThat(retired.getDeletedBy()).isEqualTo("editor");
                DeptMonitoringGroup replacement = copied.stream()
                    .filter(group -> group.getName().equals(original.getName()))
                    .findFirst().orElseThrow();
                assertThat(replacement.getId()).isNotEqualTo(original.getId());
                assertThat(replacement.getModifiedBy()).isEqualTo("editor");
                assertThat(replacement).usingRecursiveComparison()
                    .ignoringFields("id", "modifiedBy", "modifiedAt").isEqualTo(original);
            }
            assertThat(patientGroups.findById(patientGroup.getId())).contains(patientGroup);
            assertThat(groupParams(originalGroups)).containsExactlyElementsOf(originalParams);
            List<DeptMonitoringGroupParam> copiedParams = groupParams(copied);
            assertThat(copiedParams).hasSameSizeAs(originalParams);
            assertThat(copiedParams).extracting(DeptMonitoringGroupParam::getModifiedBy)
                .containsOnly("editor");
            for (DeptMonitoringGroup replacement : copied) {
                List<DeptMonitoringGroupParam> params = groupParams(List.of(replacement));
                assertThat(params).extracting(DeptMonitoringGroupParam::getMonitoringParamCode)
                    .containsExactlyElementsOf(paramCodes());
                assertThat(params).extracting(DeptMonitoringGroupParam::getDisplayOrder)
                    .containsExactly(1, 2);
            }
        } finally {
            jdbcTemplate.execute("DROP INDEX IF EXISTS monitoring_dept_init_active_name_test_idx");
        }
    }

    private void initialize(MonitoringMetaService service) {
        lockExecutor.execute(List.of(LockKeys.deptMonitoringGroups(DEPT)), () -> {
            service.initDept(DEPT);
            return null;
        });
    }

    private void assertPersistedGroups(List<DeptMonitoringGroup> returned) {
        List<DeptMonitoringGroup> persisted = activeGroups();
        assertThat(persisted).extracting(DeptMonitoringGroup::getId)
            .containsExactlyInAnyOrderElementsOf(returned.stream()
                .map(DeptMonitoringGroup::getId).toList());
        for (DeptMonitoringGroup group : returned) {
            DeptMonitoringGroup saved = persisted.stream()
                .filter(candidate -> candidate.getId().equals(group.getId()))
                .findFirst().orElseThrow();
            assertThat(saved).usingRecursiveComparison().ignoringFields("modifiedAt")
                .isEqualTo(group);
            // PostgreSQL 时间按微秒存储，其余返回字段须与实际落库值完全一致。
            assertThat(saved.getModifiedAt())
                .isEqualTo(TimeUtils.roundToMicros(group.getModifiedAt()));
        }
    }

    private MonitoringPB configuration() {
        return protoService.getConfig().getMonitoring().toBuilder()
            .clearBalanceGroup()
            .addBalanceGroup(group("入量组").toBuilder().setBalanceRole(MonEnums.BALANCE_TYPE_IN))
            .addBalanceGroup(group("出量组").toBuilder().setBalanceRole(MonEnums.BALANCE_TYPE_OUT))
            .clearMonitoringGroup().addMonitoringGroup(group("监测甲"))
            .addMonitoringGroup(group("监测乙")).build();
    }

    private MonitoringGroupPB group(String name) {
        MonitoringGroupPB.Builder builder = MonitoringGroupPB.newBuilder().setName(name)
            .setDisplayOrder(99).setSumValueMeta(META);
        for (String code : paramCodes()) {
            builder.addMonitoringParam(MonitoringGroupParamPB.newBuilder()
                .setCode(code).setDisplayOrder(99));
        }
        return builder.build();
    }

    private List<String> paramCodes() {
        return protoService.getConfig().getMonitoring().getMonitoringParamList().stream()
            .limit(2).map(param -> param.getCode()).toList();
    }

    private MonitoringMetaService configuredService(MonitoringPB monitoring) {
        ConfigProtoService configured = spy(protoService);
        doReturn(protoService.getConfig().toBuilder().setMonitoring(monitoring).build())
            .when(configured).getConfig();
        return new MonitoringMetaService(configured, userQuery, monMetaService, deptReader,
            deptParams,
            groups, groupParamRepository, patientGroups, lockExecutor);
    }

    private List<DeptMonitoringGroup> activeGroups() {
        return groups.findByDeptIdAndIsDeletedFalse(DEPT).stream()
            .sorted(Comparator.comparing(DeptMonitoringGroup::getId)).toList();
    }

    private List<DeptMonitoringGroupParam> groupParams(List<DeptMonitoringGroup> selectedGroups) {
        List<Integer> ids = selectedGroups.stream().map(DeptMonitoringGroup::getId).toList();
        return groupParamRepository.findByDeptMonitoringGroupIdInAndIsDeletedFalse(ids).stream()
            .sorted(Comparator.comparing(DeptMonitoringGroupParam::getId)).toList();
    }

    private void assertGroups(
        List<MonitoringGroupPB> configured, int groupType, List<DeptMonitoringGroup> initialized,
        LocalDateTime before, LocalDateTime after
    ) {
        List<DeptMonitoringGroup> typedGroups = initialized.stream()
            .filter(group -> group.getGroupType() == groupType).toList();
        assertThat(typedGroups).extracting(DeptMonitoringGroup::getName)
            .containsExactlyElementsOf(configured.stream()
                .map(MonitoringGroupPB::getName).toList());
        assertThat(typedGroups).extracting(DeptMonitoringGroup::getBalanceRole)
            .containsExactlyElementsOf(configured.stream()
                .map(group -> group.hasBalanceRole() ? group.getBalanceRole() : null).toList());
        assertThat(typedGroups).extracting(DeptMonitoringGroup::getDisplayOrder)
            .containsExactly(0, 1);
        for (DeptMonitoringGroup group : typedGroups) {
            assertThat(group.getSumTypePb()).isEqualTo(ProtoUtils.encodeValueMeta(META));
            assertThat(group.getModifiedBy()).isEqualTo(UserQuery.SYSTEM_ACCOUNT_ID);
            assertThat(group.getModifiedAt()).isBetween(before, after);
            assertThat(group.getIsDeleted()).isFalse();
            List<DeptMonitoringGroupParam> params = groupParams(List.of(group));
            assertThat(params).extracting(DeptMonitoringGroupParam::getMonitoringParamCode)
                .containsExactlyElementsOf(paramCodes());
            assertThat(params).extracting(DeptMonitoringGroupParam::getDisplayOrder)
                .containsExactly(1, 2);
            for (DeptMonitoringGroupParam param : params) {
                assertThat(param.getModifiedBy()).isEqualTo(UserQuery.SYSTEM_ACCOUNT_ID);
                assertThat(param.getModifiedAt()).isEqualTo(group.getModifiedAt());
                assertThat(param.getIsDeleted()).isFalse();
            }
        }
    }

    @Autowired private MonitoringMetaService monitoringMetaService;
    @Autowired private ConfigProtoService protoService;
    @Autowired private UserQuery userQuery;
    @Autowired private DeptMonitoringParamRepository deptParams;
    @Autowired private MonMetaService monMetaService;
    @Autowired private DeptMonMetaReader deptReader;
    @Autowired private DeptMonitoringGroupRepository groups;
    @Autowired private DeptMonitoringGroupParamRepository groupParamRepository;
    @Autowired private PatientMonitoringGroupRepository patientGroups;
    @Autowired private LockExecutor lockExecutor;
    @Autowired private JdbcTemplate jdbcTemplate;

    private static final String DEPT = "monitoring-dept-init-tests";
    private static final long PID = 9928801L;
    private static final ValueMetaPB META = ValueMetaPB.newBuilder()
        .setValueType(TypeEnumPB.FLOAT).setDecimalPlaces(2).setUnit("ml").build();
}
