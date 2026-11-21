package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.MonMetaService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class MonitoringGroupLockTests extends TestsBase {
    @BeforeEach
    void prepare() {
        UserQuery user = mock(UserQuery.class);
        when(user.getCurrentAccount()).thenReturn(new CurrentAccount("rbac", "123", "测试用户"));
        trackedExecutor = spy(executor);
        service = new MonitoringService(protoService, user,
            context.getBean(PatientService.class), context.getBean(PatientReader.class),
            context.getBean(PatientTubeImpl.class), context.getBean(MonitoringConfig.class),
            context.getBean(MonitoringMetaService.class),
            context.getBean(PatientMonitoringMetaService.class), trackedExecutor, jdbc,
            context.getBean(MonMetaReader.class), context.getBean(DeptMonMetaReader.class),
            context.getBean(MonMetaService.class),
            context.getBean(DeptMonitoringParamRepository.class), groups, groupParams,
            context.getBean(PatientMonitoringGroupRepository.class),
            context.getBean(PatientMonitoringGroupParamRepository.class));
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 0, 0);
        String meta = ProtoUtils.encodeValueMeta(ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.FLOAT).build());
        for (String code : List.of(FIRST_CODE, SECOND_CODE)) {
            params.save(MonitoringParam.builder().code(code).name(code).typePb(meta)
                .balanceType(0).displayOrder(1).build());
        }
        group = groups.save(DeptMonitoringGroup.builder().deptId(DEPT).name("已有组")
            .groupType(MonEnums.GROUP_TYPE_MONITORING).displayOrder(1)
            .sumTypePb(meta).isDeleted(false)
            .modifiedBy("123").modifiedAt(now).build());
        groupParam = groupParams.save(DeptMonitoringGroupParam.builder()
            .deptMonitoringGroupId(group.getId()).monitoringParamCode(FIRST_CODE)
            .displayOrder(1).isDeleted(false).modifiedBy("123").modifiedAt(now).build());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("""
            DELETE FROM dept_monitoring_group_params WHERE dept_monitoring_group_id IN
            (SELECT id FROM dept_monitoring_groups WHERE dept_id = ?)
            """, DEPT);
        jdbc.update("DELETE FROM dept_monitoring_groups WHERE dept_id = ?", DEPT);
        jdbc.update("DELETE FROM dept_monitoring_params WHERE dept_id IN (?, ?)", DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM monitoring_params_history WHERE dept_id IN (?, ?)",
            DEPT, OTHER_DEPT);
        jdbc.update("DELETE FROM monitoring_params WHERE code IN (?, ?)", FIRST_CODE, SECOND_CODE);
        jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?)",
            LockKeys.deptMonitoringGroups(DEPT), LockKeys.deptMonitoringGroups(OTHER_DEPT));
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void everyGroupAndParamMutationWaitsForTheSameDepartmentLock(Operation operation)
        throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        List<String> deptKeys = List.of(LockKeys.deptMonitoringGroups(DEPT));
        boolean deptParam = operation == Operation.CREATE_DEPT_PARAM
            || operation == Operation.UPDATE_DEPT_PARAM;
        List<String> keys = deptParam
            ? List.of(LockKeys.deptMonitoringGroups(DEPT), LockKeys.monitoringParam())
            : deptKeys;
        if (operation == Operation.UPDATE_DEPT_PARAM) {
            jdbc.update("""
                INSERT INTO dept_monitoring_params (code, dept_id, type_pb) VALUES (?, ?, ?)
                """, FIRST_CODE, DEPT, params.findByCode(FIRST_CODE).orElseThrow().getTypePb());
        }
        doAnswer(invocation -> {
            attempted.countDown();
            return invocation.callRealMethod();
        }).when(trackedExecutor).execute(eq(keys), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 只占用科室锁，确认自定义参数写入也与分组和组内参数互斥。
            Future<?> blocker = pool.submit(() -> executor.execute(deptKeys, () -> {
                held.countDown();
                await(release);
                return null;
            }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> mutation = pool.submit(() -> mutate(operation));
            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(mutation.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            verify(trackedExecutor).execute(eq(keys), any());
            if (deptParam) {
                assertThat(jdbc.queryForObject("""
                    SELECT type_pb FROM dept_monitoring_params WHERE dept_id = ? AND code = ?
                    """, String.class, DEPT, FIRST_CODE))
                    .isEqualTo(ProtoUtils.encodeValueMeta(DEPT_META));
                assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM monitoring_params_history WHERE dept_id = ?
                    """, Integer.class, DEPT))
                    .isZero();
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void differentDepartmentsWaitForTheSameGlobalParamLock() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch attempted = new CountDownLatch(1);
        List<String> otherDeptKeys = List.of(
            LockKeys.deptMonitoringGroups(OTHER_DEPT), LockKeys.monitoringParam());
        doAnswer(invocation -> {
            attempted.countDown();
            return invocation.callRealMethod();
        }).when(trackedExecutor).execute(eq(otherDeptKeys), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> blocker = pool.submit(() -> executor.execute(List.of(
                LockKeys.deptMonitoringGroups(DEPT), LockKeys.monitoringParam()), () -> {
                    held.countDown();
                    await(release);
                    return null;
                }));
            assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Integer> mutation = pool.submit(() -> service.updateDeptMonitoringParam(
                ProtoUtils.protoToJson(UpdateDeptMonitoringParamReq.newBuilder()
                    .setDepartmentId(OTHER_DEPT).setCode(FIRST_CODE)
                    .setValueMeta(DEPT_META).build()))
                .getRt().getCode());
            assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> mutation.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(mutation.get(10, TimeUnit.SECONDS)).isEqualTo(StatusCode.OK_VALUE);
            assertThat(jdbc.queryForObject("""
                SELECT type_pb FROM dept_monitoring_params WHERE dept_id = ? AND code = ?
                """, String.class, OTHER_DEPT, FIRST_CODE))
                .isEqualTo(ProtoUtils.encodeValueMeta(DEPT_META));
            verify(trackedExecutor).execute(eq(otherDeptKeys), any());
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentGroupAddsCheckDuplicatesAfterTakingTheLock() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> {
                await(start);
                return mutate(Operation.ADD_GROUP);
            });
            Future<Integer> second = pool.submit(() -> {
                await(start);
                return mutate(Operation.ADD_GROUP);
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder(
                    StatusCode.OK_VALUE, StatusCode.MONITORING_GROUP_EXIST_VALUE);
            assertThat(groups.findByDeptIdAndIsDeletedFalse(DEPT)).hasSize(2);
        } finally {
            start.countDown();
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int mutate(Operation operation) {
        return switch (operation) {
            case CREATE_DEPT_PARAM, UPDATE_DEPT_PARAM -> service.updateDeptMonitoringParam(
                ProtoUtils.protoToJson(UpdateDeptMonitoringParamReq.newBuilder()
                    .setDepartmentId(DEPT).setCode(FIRST_CODE).setValueMeta(DEPT_META).build()))
                .getRt().getCode();
            case ADD_GROUP -> service.addDeptMonitoringGroup(ProtoUtils.protoToJson(
                AddDeptMonitoringGroupReq.newBuilder().setDepartmentId(DEPT).setName("新组")
                    .setGroupType(MonEnums.GROUP_TYPE_MONITORING).build())).getRt().getCode();
            case RENAME_GROUP -> service.updateDeptMonGroupName(ProtoUtils.protoToJson(
                UpdateDeptMonGroupNameReq.newBuilder().setId(group.getId()).setName("改名组")
                    .build())).getRt().getCode();
            case DELETE_GROUP -> service.deleteDeptMonitoringGroup(ProtoUtils.protoToJson(
                DeleteDeptMonitoringGroupReq.newBuilder().setId(group.getId()).build()))
                .getRt().getCode();
            case REORDER_GROUP -> service.reorderDeptMonitoringGroup(ProtoUtils.protoToJson(
                ReorderDeptMonitoringGroupReq.newBuilder().setDepartmentId(DEPT)
                    .addGroupOrder(MonitoringGroupOrder.newBuilder()
                        .setMonitoringGroupId(group.getId()).setDisplayOrder(2)).build()))
                .getRt().getCode();
            case ADD_PARAM -> service.addDeptMonitoringGroupParam(ProtoUtils.protoToJson(
                AddDeptMonitoringGroupParamReq.newBuilder().setDeptId(DEPT)
                    .setDeptMonitoringGroupId(group.getId()).setCode(SECOND_CODE).build()))
                .getRt().getCode();
            case DELETE_PARAM -> service.deleteDeptMonitoringGroupParam(ProtoUtils.protoToJson(
                DeleteDeptMonitoringGroupParamReq.newBuilder().setId(groupParam.getId()).build()))
                .getRt().getCode();
            case REORDER_PARAM -> service.reorderDeptMonitoringGroupParam(ProtoUtils.protoToJson(
                ReorderDeptMonitoringGroupParamReq.newBuilder().setDeptId(DEPT)
                    .addParamOrder(MonitoringGroupParamOrder.newBuilder()
                        .setId(groupParam.getId()).setDisplayOrder(2)).build()))
                .getRt().getCode();
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("等待并发测试同步超时");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发测试被中断", e);
        }
    }

    private enum Operation {
        ADD_GROUP, RENAME_GROUP, DELETE_GROUP, REORDER_GROUP, ADD_PARAM, DELETE_PARAM,
        REORDER_PARAM, CREATE_DEPT_PARAM, UPDATE_DEPT_PARAM
    }

    @Autowired private ApplicationContext context;
    @Autowired private ConfigProtoService protoService;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MonitoringParamRepository params;
    @Autowired private DeptMonitoringGroupRepository groups;
    @Autowired private DeptMonitoringGroupParamRepository groupParams;

    private MonitoringService service;
    private LockExecutor trackedExecutor;
    private DeptMonitoringGroup group;
    private DeptMonitoringGroupParam groupParam;

    private static final String DEPT = "test-monitoring-group-lock";
    private static final String OTHER_DEPT = "test-monitoring-group-lock-other";
    private static final String FIRST_CODE = "test_group_lock_first";
    private static final String SECOND_CODE = "test_group_lock_second";
    private static final ValueMetaPB DEPT_META = ValueMetaPB.newBuilder()
        .setValueType(TypeEnumPB.FLOAT).setDecimalPlaces(2).setUnit("ml").build();
}
