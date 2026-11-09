package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.Encoder;
import com.jingyicare.jingyi_icis_engine.service.users.UserBasicOperator;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class BgaMetaServiceTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanData();
        // 与生产建表脚本一致，验证软删除记录和有效记录可以共存。
        jdbcTemplate.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS idx_bga_params_dept_param
            ON bga_params(dept_id, monitoring_param_code) WHERE is_deleted = false
            """);
        departmentRepository.saveAndFlush(new RbacDepartment(DEPT_ID, "血气元数据测试"));
        for (String code : List.of(FIRST_CODE, SECOND_CODE)) {
            monitoringParamRepository.saveAndFlush(MonitoringPbUtils.toMonitoringParamEntity(
                MonitoringParamPB.newBuilder().setCode(code).setName(code)
                    .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.FLOAT)).build(),
                10000));
        }
        trackedRepository = mock(BgaParamRepository.class, delegatesTo(repository));
        trackedExecutor = spy(executor);
        service = configuredService(List.of(FIRST_CODE, SECOND_CODE, FIRST_CODE));
    }

    @AfterEach
    void cleanUp() {
        cleanData();
    }

    @Test
    void missingDefaultsAreAppendedAndRepeatedInitializationDoesNotLockOrWrite() {
        BgaParam existing = repository.saveAndFlush(paramBuilder(FIRST_CODE, 4)
            .enabled(true).lisResultCode("existing-lis").modifiedBy("existing-user").build());

        service.initBgaParams(DEPT_ID);
        List<BgaParam> initialized = repository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);

        assertThat(initialized).extracting(BgaParam::getMonitoringParamCode)
            .containsExactly(FIRST_CODE, SECOND_CODE);
        assertThat(initialized).extracting(BgaParam::getDisplayOrder).containsExactly(4, 5);
        assertThat(initialized.getFirst().getId()).isEqualTo(existing.getId());
        assertThat(initialized.getFirst().getEnabled()).isTrue();
        assertThat(initialized.getFirst().getLisResultCode()).isEqualTo("existing-lis");
        assertThat(initialized.getFirst().getModifiedBy()).isEqualTo("existing-user");
        assertThat(initialized.getLast().getEnabled()).isFalse();
        assertThat(initialized.getLast().getLisResultCode()).isNull();
        assertThat(initialized.getLast().getModifiedBy()).isEqualTo("system");
        verify(trackedRepository, times(2))
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);
        verify(trackedRepository).saveAll(any());
        verifyNoMoreInteractions(trackedRepository);
        verify(trackedExecutor).execute(eq(List.of(LockKeys.bgaParam(DEPT_ID))), any());

        clearInvocations(trackedRepository, trackedExecutor);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long previousStatements = statistics.getPrepareStatementCount();
            service.initBgaParams(DEPT_ID);
            assertThat(statistics.getPrepareStatementCount() - previousStatements).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
        List<BgaParam> reread = repository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);
        assertThat(reread).extracting(BgaParam::getId)
            .containsExactlyElementsOf(initialized.stream().map(BgaParam::getId).toList());
        verify(trackedRepository).findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);
        verifyNoMoreInteractions(trackedRepository);
        verifyNoInteractions(trackedExecutor);
    }

    @Test
    void deletedDefaultsStayDeletedAndNewActiveRowsUseDefaultSettings() {
        BgaParam deleted = repository.saveAndFlush(paramBuilder(FIRST_CODE, 10)
            .enabled(true).lisResultCode("old-lis").isDeleted(true)
            .deletedBy("old-user").deletedAt(TimeUtils.getNowUtc()).build());

        service.initBgaParams(DEPT_ID);
        List<BgaParam> params = repository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);

        assertThat(params).extracting(BgaParam::getMonitoringParamCode)
            .containsExactly(FIRST_CODE, SECOND_CODE);
        assertThat(params).extracting(BgaParam::getDisplayOrder).containsExactly(1, 2);
        BgaParam replacement = params.getFirst();
        assertThat(replacement.getId()).isNotEqualTo(deleted.getId());
        assertThat(replacement.getEnabled()).isFalse();
        assertThat(replacement.getLisResultCode()).isNull();
        assertThat(replacement.getIsDeleted()).isFalse();
        assertThat(replacement.getDeletedAt()).isNull();
        BgaParam unchangedDeleted = repository.findById(deleted.getId()).orElseThrow();
        assertThat(unchangedDeleted.getIsDeleted()).isTrue();
        assertThat(unchangedDeleted.getEnabled()).isTrue();
        assertThat(unchangedDeleted.getLisResultCode()).isEqualTo("old-lis");
        assertThat(unchangedDeleted.getDeletedBy()).isEqualTo("old-user");
        assertThat(unchangedDeleted.getDeletedAt()).isNotNull();
    }

    @Test
    void concurrentInitializationRechecksMissingCodesAndWritesOnlyOneBatch() throws Exception {
        Supplier<Integer> initialize = () -> {
            service.initBgaParams(DEPT_ID);
            return StatusCode.OK_VALUE;
        };
        assertThat(runContending(initialize, initialize))
            .containsExactly(StatusCode.OK_VALUE, StatusCode.OK_VALUE);

        List<BgaParam> params = repository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);
        assertThat(params).extracting(BgaParam::getMonitoringParamCode)
            .containsExactly(FIRST_CODE, SECOND_CODE);
        assertThat(params).extracting(BgaParam::getDisplayOrder).containsExactly(1, 2);
        verify(trackedRepository, times(4))
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(DEPT_ID);
        verify(trackedRepository).saveAll(any());
        verifyNoMoreInteractions(trackedRepository);
        verify(trackedExecutor, times(2))
            .execute(eq(List.of(LockKeys.bgaParam(DEPT_ID))), any());
    }

    @Test
    void bgaFailureRollsBackOnlyInitializationAndAllowsIndependentRetry() {
        UserBasicOperator operator = departmentOperator(departmentInfoRepository);
        doAnswer(invocation -> {
            Iterable<BgaParam> params = invocation.getArgument(0);
            repository.saveAllAndFlush(params);
            throw new IllegalStateException("模拟血气初始化失败");
        }).when(trackedRepository).saveAll(any());

        assertThatThrownBy(() -> operator.addDepartment(
            ROLLBACK_DEPT_ID, "血气事务边界测试", "血气", "", "", "测试医院", "test-user"))
            .isInstanceOf(IllegalStateException.class).hasMessage("模拟血气初始化失败");

        assertThat(departmentInfoRepository.findByDeptIdAndIsDeletedFalse(ROLLBACK_DEPT_ID))
            .isPresent();
        assertThat(departmentRepository.findByDeptId(ROLLBACK_DEPT_ID)).isPresent();
        assertThat(repository.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(ROLLBACK_DEPT_ID))
            .isEmpty();
        verify(trackedExecutor).execute(eq(List.of(LockKeys.department(ROLLBACK_DEPT_ID))), any());
        verify(trackedExecutor).execute(eq(List.of(LockKeys.bgaParam(ROLLBACK_DEPT_ID))), any());

        doAnswer(invocation -> {
            Iterable<BgaParam> params = invocation.getArgument(0);
            return repository.saveAll(params);
        }).when(trackedRepository).saveAll(any());
        service.initBgaParams(ROLLBACK_DEPT_ID);
        assertThat(repository.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(ROLLBACK_DEPT_ID))
            .hasSize(2);
    }

    @Test
    void departmentFailureRollsBackAndDoesNotStartBgaInitialization() {
        DepartmentRepository failingRepository =
            mock(DepartmentRepository.class, delegatesTo(departmentInfoRepository));
        doAnswer(invocation -> {
            Department department = invocation.getArgument(0);
            departmentInfoRepository.saveAndFlush(department);
            throw new IllegalStateException("模拟科室保存失败");
        }).when(failingRepository).save(any(Department.class));
        UserBasicOperator operator = departmentOperator(failingRepository);

        assertThatThrownBy(() -> operator.addDepartment(
            ROLLBACK_DEPT_ID, "血气事务边界测试", "血气", "", "", "测试医院", "test-user"))
            .isInstanceOf(IllegalStateException.class).hasMessage("模拟科室保存失败");

        assertThat(departmentInfoRepository.findByDeptIdAndIsDeletedFalse(ROLLBACK_DEPT_ID))
            .isEmpty();
        assertThat(departmentRepository.findByDeptId(ROLLBACK_DEPT_ID)).isEmpty();
        verifyNoInteractions(trackedRepository);
        verify(trackedExecutor).execute(eq(List.of(LockKeys.department(ROLLBACK_DEPT_ID))), any());
        verifyNoMoreInteractions(trackedExecutor);
    }

    private UserBasicOperator departmentOperator(DepartmentRepository infoRepository) {
        return new UserBasicOperator(
            protoService, mock(RbacPermissionRepository.class), mock(RbacRoleRepository.class),
            mock(RbacRolePermissionRepository.class), mock(RbacRoleRoleRepository.class),
            mock(RbacAccountRepository.class), mock(AccountRepository.class),
            mock(RbacAccountRoleRepository.class), departmentRepository, infoRepository,
            mock(RbacDepartmentAccountRepository.class), mock(DepartmentAccountRepository.class),
            mock(RbacDepartmentAccountRoleRepository.class),
            mock(RbacDepartmentAccountPermissionRepository.class),
            encoder, service, trackedExecutor);
    }

    private BgaMetaService configuredService(List<String> codes) {
        ConfigProtoService configured = spy(protoService);
        doReturn(protoService.getConfig().toBuilder().setBga(
            protoService.getConfig().getBga().toBuilder()
                .clearBgaParamCode().addAllBgaParamCode(codes)).build())
            .when(configured).getConfig();
        return new BgaMetaService(configured, trackedRepository, trackedExecutor);
    }

    private BgaParam.BgaParamBuilder paramBuilder(String code, int displayOrder) {
        return BgaParam.builder().deptId(DEPT_ID).monitoringParamCode(code)
            .displayOrder(displayOrder).enabled(false).isDeleted(false)
            .modifiedBy("system").modifiedAt(TimeUtils.getNowUtc());
    }

    private <T> List<T> runContending(Supplier<T> first, Supplier<T> second) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(
                List.of(LockKeys.bgaParam(DEPT_ID)), () -> {
                    locked.countDown();
                    try {
                        assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放血气参数锁超时");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return null;
                }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得血气参数业务锁");
            Future<T> firstResult = pool.submit(first::get);
            Future<T> secondResult = pool.submit(second::get);
            assertTrue(waitForBlockedRequests(), "两个请求应同时等待血气参数业务锁");
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            return List.of(firstResult.get(15, TimeUnit.SECONDS),
                secondResult.get(15, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private boolean waitForBlockedRequests() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= 2) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private void cleanData() {
        jdbcTemplate.update("DELETE FROM bga_params WHERE dept_id IN (?, ?)",
            DEPT_ID, ROLLBACK_DEPT_ID);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id IN (?, ?)",
            DEPT_ID, ROLLBACK_DEPT_ID);
        jdbcTemplate.update("DELETE FROM departments WHERE dept_id IN (?, ?)",
            DEPT_ID, ROLLBACK_DEPT_ID);
        jdbcTemplate.update("DELETE FROM monitoring_params WHERE code IN (?, ?)",
            FIRST_CODE, SECOND_CODE);
        jdbcTemplate.update("DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?, ?, ?)",
            LockKeys.bgaParam(DEPT_ID), LockKeys.department(DEPT_ID),
            LockKeys.bgaParam(ROLLBACK_DEPT_ID), LockKeys.department(ROLLBACK_DEPT_ID));
    }

    private static final String DEPT_ID = "bga-meta-tests";
    private static final String ROLLBACK_DEPT_ID = "bga-meta-tests-rollback";
    private static final String FIRST_CODE = "bga-meta-tests-first";
    private static final String SECOND_CODE = "bga-meta-tests-second";

    private BgaMetaService service;
    private BgaParamRepository trackedRepository;
    private LockExecutor trackedExecutor;
    @Autowired private ConfigProtoService protoService;
    @Autowired private BgaParamRepository repository;
    @Autowired private MonitoringParamRepository monitoringParamRepository;
    @Autowired private RbacDepartmentRepository departmentRepository;
    @Autowired private DepartmentRepository departmentInfoRepository;
    @Autowired private Encoder encoder;
    @Autowired private LockExecutor executor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManagerFactory entityManagerFactory;
}
