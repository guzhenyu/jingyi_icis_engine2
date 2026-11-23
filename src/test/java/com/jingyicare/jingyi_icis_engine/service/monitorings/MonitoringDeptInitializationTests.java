package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptMonitoringGroupParamReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDeptMonitoringGroupReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.IdResp;
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
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.PatientMonMetaReader;
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
            LockKeys.deptMonitoringGroup(DEPT));
    }

    @Test
    void shouldInitializeBothGroupTypesAndPreserveRepeatedInitialization() {
        MonitoringPB config = configuration();
        DeptMonMetaService service = configuredService(config);
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
            String.class, LockKeys.deptMonitoringGroup(DEPT)))
            .containsExactly(LockKeys.deptMonitoringGroup(DEPT));

        initialize(service);

        assertThat(activeGroups()).containsExactlyElementsOf(initialized);
        assertThat(groupParams(initialized)).containsExactlyElementsOf(initializedParams);
    }

    @Test
    void shouldPreserveCustomGroupsWithoutRestoringDeletedDefaults() {
        DeptMonMetaService service = configuredService(configuration());
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
    void shouldRequireCallerTransactionForInitialization() {
        assertThatThrownBy(() -> deptMonMetaService.initDept(DEPT))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(activeGroups()).isEmpty();
    }

    @Test
    void shouldRollBackBothGroupTypesAndParamsWhenLaterInsertFails() {
        MonitoringPB invalid = configuration().toBuilder()
            .setMonitoringGroup(1, group("x".repeat(256))).build();
        DeptMonMetaService service = configuredService(invalid);
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
            Integer.class, LockKeys.deptMonitoringGroup(DEPT))).isZero();
    }

    @Test
    void shouldInitializeAnEmptyDepartmentBeforeAddingGroup() {
        DeptMonMetaService service = configuredService(configuration());
        AddDeptMonitoringGroupReq req = AddDeptMonitoringGroupReq.newBuilder()
            .setDepartmentId(DEPT).setName("新分组")
            .setGroupType(MonEnums.GROUP_TYPE_MONITORING).build();

        IdResp response = service.addDeptMonitoringGroup(ProtoUtils.protoToJson(req));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
        List<DeptMonitoringGroup> persisted = activeGroups();
        assertThat(persisted).extracting(DeptMonitoringGroup::getName)
            .containsExactly("入量组", "出量组", "监测甲", "监测乙", "新分组");
        assertThat(persisted.getLast().getId()).isEqualTo(response.getId());
        assertThat(groupParams(persisted)).hasSize(8);
    }

    @Test
    void shouldResolveOldGroupIdToCurrentGroupAfterCopy() {
        // 测试建表只生成普通索引；补齐生产唯一约束并限定到本测试科室。
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX monitoring_dept_init_active_name_test_idx
            ON dept_monitoring_groups (dept_id, name)
            WHERE is_deleted = FALSE AND dept_id = '%s'
            """.formatted(DEPT));
        try {
            DeptMonMetaService service = configuredService(configuration());
            initialize(service);
            List<DeptMonitoringGroup> originalGroups = activeGroups();
            List<DeptMonitoringGroupParam> originalParams = groupParams(originalGroups);
            DeptMonitoringGroup originalTarget = originalGroups.stream()
                .filter(group -> group.getGroupType() == MonEnums.GROUP_TYPE_MONITORING)
                .findFirst().orElseThrow();
            PatientMonitoringGroup patientGroup = patientGroups.saveAndFlush(
                PatientMonitoringGroup.builder().pid(PID)
                    .deptMonitoringGroupId(originalTarget.getId()).isDeleted(false)
                    .modifiedBy("nurse")
                    .modifiedAt(TimeUtils.roundToMicros(TimeUtils.getNowUtc())).build());

            String extraCode = protoService.getConfig().getMonitoring()
                .getMonitoringParam(2).getCode();
            AddDeptMonitoringGroupParamReq addReq = AddDeptMonitoringGroupParamReq.newBuilder()
                .setDeptId(DEPT).setDeptMonitoringGroupId(originalTarget.getId())
                .setCode(extraCode).build();
            String reqJson = ProtoUtils.protoToJson(addReq);
            IdResp response = service.addDeptMonitoringGroupParam(reqJson);
            assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);

            List<DeptMonitoringGroup> copied = activeGroups();
            DeptMonitoringGroup newTarget = copied.stream()
                .filter(group -> group.getName().equals(originalTarget.getName()))
                .findFirst().orElseThrow();
            DeptMonitoringGroupParam added = groupParamRepository
                .findById(response.getId()).orElseThrow();
            assertThat(added.getDeptMonitoringGroupId()).isEqualTo(newTarget.getId());
            assertThat(added.getMonitoringParamCode()).isEqualTo(extraCode);
            assertThat(added.getDisplayOrder()).isEqualTo(3);

            assertThat(copied).hasSameSizeAs(originalGroups);
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
            assertThat(copiedParams).hasSize(originalParams.size() + 1);
            assertThat(copiedParams).extracting(DeptMonitoringGroupParam::getModifiedBy)
                .containsOnly("editor");
            for (DeptMonitoringGroup replacement : copied) {
                List<DeptMonitoringGroupParam> params = groupParams(List.of(replacement)).stream()
                    .filter(param -> !param.getId().equals(added.getId())).toList();
                assertThat(params).extracting(DeptMonitoringGroupParam::getMonitoringParamCode)
                    .containsExactlyElementsOf(paramCodes());
                assertThat(params).extracting(DeptMonitoringGroupParam::getDisplayOrder)
                    .containsExactly(1, 2);
            }

            IdResp duplicateResponse = service.addDeptMonitoringGroupParam(reqJson);
            assertThat(duplicateResponse.getRt().getCode())
                .isEqualTo(StatusCode.MONITORING_GROUP_PARAM_EXIST_VALUE);
            assertThat(activeGroups()).containsExactlyElementsOf(copied);
            assertThat(groupParams(copied)).containsExactlyElementsOf(copiedParams);

            String secondCode = protoService.getConfig().getMonitoring()
                .getMonitoringParam(3).getCode();
            String staleReqJson = ProtoUtils.protoToJson(addReq.toBuilder().setCode(secondCode));
            IdResp staleResponse = service.addDeptMonitoringGroupParam(staleReqJson);
            assertThat(staleResponse.getRt().getCode()).isEqualTo(StatusCode.OK_VALUE);
            DeptMonitoringGroupParam addedByOldId = groupParamRepository
                .findById(staleResponse.getId()).orElseThrow();
            assertThat(addedByOldId.getDeptMonitoringGroupId()).isEqualTo(newTarget.getId());
            assertThat(addedByOldId.getMonitoringParamCode()).isEqualTo(secondCode);
            assertThat(addedByOldId.getDisplayOrder()).isEqualTo(4);
            assertThat(activeGroups()).containsExactlyElementsOf(copied);
            assertThat(patientGroups.findById(patientGroup.getId())).contains(patientGroup);
            assertThat(groupParams(originalGroups)).containsExactlyElementsOf(originalParams);
        } finally {
            jdbcTemplate.execute("DROP INDEX IF EXISTS monitoring_dept_init_active_name_test_idx");
        }
    }

    private void initialize(DeptMonMetaService service) {
        lockExecutor.execute(List.of(LockKeys.deptMonitoringGroup(DEPT)), () -> {
            service.initDept(DEPT);
            return null;
        });
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

    private DeptMonMetaService configuredService(MonitoringPB monitoring) {
        UserQuery userQuery = mock(UserQuery.class);
        when(userQuery.getCurrentAccount())
            .thenReturn(new CurrentAccount("rbac-editor", "editor", "编辑用户"));
        return new DeptMonMetaService(
            protoService.getConfig().toBuilder().setMonitoring(monitoring).build(),
            deptReader, patientReader, monReader, monMetaService, deptParams, groups,
            groupParamRepository, lockExecutor, jdbcTemplate, userQuery);
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

    @Autowired private DeptMonMetaService deptMonMetaService;
    @Autowired private ConfigProtoService protoService;
    @Autowired private DeptMonitoringParamRepository deptParams;
    @Autowired private MonMetaService monMetaService;
    @Autowired private DeptMonMetaReader deptReader;
    @Autowired private MonMetaReader monReader;
    @Autowired private PatientMonMetaReader patientReader;
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
