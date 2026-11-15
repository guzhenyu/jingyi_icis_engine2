package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BgaMetaService;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.NursingOrderTemplateService;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreMetaService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@SpringBootTest(properties = "jingyi.test.postgresql.database-name=department_command_test")
class DepartmentCommandTests extends TestsBase {
    @BeforeEach
    void prepare() {
        clearInvocations(bgaMetaService);
        originalActiveIds = jdbc.queryForList(
            "SELECT id FROM departments WHERE is_deleted = false", Integer.class);
        jdbc.update("UPDATE departments SET is_deleted = true WHERE is_deleted = false");
        for (String code : List.of(FIRST, SECOND)) {
            jdbc.update("""
                INSERT INTO departments(dept_id, name, abbreviation, hospital_name, is_deleted)
                VALUES (?, ?, ?, ?, false)
                """, code, code, code, "测试医院");
            jdbc.update("INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)",
                code, code);
        }
        jdbc.update("INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)",
            ACCOUNT, ACCOUNT);
        employeeId = jdbc.queryForObject("""
            INSERT INTO accounts(account_id, name, is_deleted) VALUES (?, ?, false) RETURNING id
            """, Long.class, ACCOUNT, ACCOUNT);
        jdbc.update("INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, true)",
            ROLE, "科室命令测试角色");
        // 补齐实体未声明的生产外键，验证拒绝删除和删除顺序的实际 PostgreSQL 行为。
        if (!jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM pg_constraint WHERE conname = 'dept_command_test_fk')
            """, Boolean.class)) {
            jdbc.execute("""
                ALTER TABLE rbac_accounts_departments ADD CONSTRAINT dept_command_test_fk
                FOREIGN KEY (dept_id) REFERENCES rbac_departments(dept_id)
                """);
        }
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM rbac_accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM accounts WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);
        jdbc.update("DELETE FROM rbac_roles WHERE id = ?", ROLE);
        for (String code : List.of(FIRST, SECOND, THIRD, FOURTH)) {
            jdbc.update("DELETE FROM departments WHERE dept_id = ?", code);
            jdbc.update("DELETE FROM rbac_departments WHERE dept_id = ?", code);
            jdbc.update("DELETE FROM app_transaction_locks WHERE resource_key = ?",
                LockKeys.department(code));
        }
        for (Integer id : originalActiveIds) {
            jdbc.update("UPDATE departments SET is_deleted = false WHERE id = ?", id);
        }
    }

    @Test
    void deletionSoftDeletesTheBusinessRowAndRemovesTheRbacRow() {
        assertThat(commands.deleteDepartment(FIRST, OPERATOR)).isEqualTo(StatusCode.OK);

        assertThat(rbacRepo.findByDeptId(FIRST)).isEmpty();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM departments WHERE dept_id = ? AND is_deleted = true
                AND deleted_by = ? AND deleted_at IS NOT NULL
            """, Long.class, FIRST, OPERATOR)).isEqualTo(1L);
        assertThat(commands.deleteDepartment(FIRST, OPERATOR))
            .isEqualTo(StatusCode.DEPARTMENT_NOT_FOUND);
        assertThat(commands.deleteDepartment(SECOND, OPERATOR))
            .isEqualTo(StatusCode.DEPARTMENT_AT_LEAST_ONE);
        assertThat(rbacRepo.findByDeptId(SECOND)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"rbac", "business"})
    void eitherKindOfActiveAccountMembershipPreventsDeletion(String source) {
        if (source.equals("rbac")) addRbacMembership();
        else addBusinessMembership(false);

        assertThat(commands.deleteDepartment(FIRST, OPERATOR))
            .isEqualTo(StatusCode.DEPARTMENT_HAS_ACCOUNTS);
        assertUnchanged();
        assertThat(rbacRepo.findByDeptId(FIRST)).isPresent();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM accounts WHERE account_id = ? AND is_deleted = false",
            Long.class, ACCOUNT)).isEqualTo(1L);
    }

    @Test
    void historicalBusinessMembershipDoesNotPreventDeletionOrGetRewritten() {
        addBusinessMembership(true);

        assertThat(commands.deleteDepartment(FIRST, OPERATOR)).isEqualTo(StatusCode.OK);
        assertThat(jdbc.queryForObject("""
            SELECT deleted_by FROM accounts_departments WHERE account_id = ? AND dept_id = ?
            """, String.class, ACCOUNT, FIRST)).isEqualTo("original-deleter");
    }

    @Test
    void updateKeepsTheCodeAndSynchronizesNamesAndAuditFields() {
        assertThat(update(UPDATED_NAME)).isEqualTo(StatusCode.OK);
        assertThat(businessName()).isEqualTo(UPDATED_NAME);
        assertThat(rbacRepo.findByDeptId(FIRST).orElseThrow().getDeptName())
            .isEqualTo(UPDATED_NAME);
        assertThat(jdbc.queryForObject(
            "SELECT modified_by FROM departments WHERE dept_id = ?", String.class, FIRST))
            .isEqualTo(OPERATOR);

        assertThat(commands.updateDepartment(firstId(), THIRD, "非法改码", "简称", "", "",
            "测试医院", OPERATOR)).isEqualTo(StatusCode.INVALID_DEPARTMENT_ID);
        assertThat(businessName()).isEqualTo(UPDATED_NAME);
        assertThat(rbacRepo.findByDeptId(THIRD)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"business", "rbac"})
    void duplicateNamesFailWithoutCommittingProfileChanges(String source) {
        String duplicate = SECOND;
        if (source.equals("rbac")) {
            duplicate = "只有RBAC的重名科室";
            rbacRepo.saveAndFlush(new RbacDepartment(THIRD, duplicate));
        }

        assertThat(update(duplicate)).isEqualTo(StatusCode.DEPARTMENT_ALREADY_EXISTS);
        assertUnchanged();
    }

    @Test
    void rbacSaveFailureRollsBackTheBusinessUpdate() {
        RbacDepartmentRepository failing =
            mock(RbacDepartmentRepository.class, delegatesTo(rbacRepo));
        doAnswer(invocation -> {
            rbacRepo.saveAndFlush(invocation.getArgument(0));
            throw new IllegalStateException("模拟RBAC保存失败");
        }).when(failing).save(any(RbacDepartment.class));
        DepartmentCommand command = new DepartmentCommand(
            deptRepo, failing, membershipRepo, businessMembershipRepo, bgaMetaService,
            scoreMetaService, nursingTemplateService, locks);

        assertThatThrownBy(() -> command.updateDepartment(firstId(), FIRST, UPDATED_NAME,
            "新简称", "", "", "测试医院", OPERATOR)).hasMessage("模拟RBAC保存失败");
        assertUnchanged();
        assertThat(rbacRepo.findByDeptId(FIRST).orElseThrow().getDeptName()).isEqualTo(FIRST);
    }

    @Test
    void rbacDeleteFailureRollsBackTheBusinessSoftDeletion() {
        RbacDepartmentRepository failing =
            mock(RbacDepartmentRepository.class, delegatesTo(rbacRepo));
        doAnswer(invocation -> {
            rbacRepo.deleteById(invocation.getArgument(0));
            rbacRepo.flush();
            throw new IllegalStateException("模拟RBAC删除失败");
        }).when(failing).deleteById(FIRST);
        DepartmentCommand command = new DepartmentCommand(
            deptRepo, failing, membershipRepo, businessMembershipRepo, bgaMetaService,
            scoreMetaService, nursingTemplateService, locks);

        assertThatThrownBy(() -> command.deleteDepartment(FIRST, OPERATOR))
            .hasMessage("模拟RBAC删除失败");
        assertUnchanged();
        assertThat(rbacRepo.findByDeptId(FIRST)).isPresent();
    }

    @ParameterizedTest
    @ValueSource(strings = {"catalog", "department"})
    void updatesWaitForBothResourceLocks(String resource) throws Exception {
        String key = resource.equals("catalog")
            ? LockKeys.departments() : LockKeys.department(FIRST);
        assertThat(runBlocked(key, List.of(() -> update(UPDATED_NAME)), () -> {}))
            .containsExactly(StatusCode.OK);
    }

    @Test
    void deletionRechecksAccountMembershipAfterWaitingForTheLock() throws Exception {
        assertThat(runBlocked(LockKeys.department(FIRST),
            List.of(() -> commands.deleteDepartment(FIRST, OPERATOR)), this::addRbacMembership))
            .containsExactly(StatusCode.DEPARTMENT_HAS_ACCOUNTS);
        assertUnchanged();
    }

    @Test
    void concurrentDeletesOfDifferentDepartmentsKeepOneDepartment() throws Exception {
        assertThat(runBlocked(LockKeys.departments(), List.of(
            () -> commands.deleteDepartment(FIRST, OPERATOR),
            () -> commands.deleteDepartment(SECOND, OPERATOR)), () -> {}))
            .containsExactlyInAnyOrder(StatusCode.OK, StatusCode.DEPARTMENT_AT_LEAST_ONE);
        assertThat(deptRepo.countByIsDeletedFalse()).isEqualTo(1);
    }

    @Test
    void concurrentCreatesWithTheSameNameReturnOneBusinessError() throws Exception {
        assertThat(runBlocked(LockKeys.departments(), List.of(
            () -> commands.addDepartment(department(THIRD), OPERATOR),
            () -> commands.addDepartment(department(FOURTH), OPERATOR)), () -> {}))
            .containsExactlyInAnyOrder(StatusCode.OK, StatusCode.DEPARTMENT_ALREADY_EXISTS);
        verify(bgaMetaService, times(1)).initDept(anyString());
    }

    private List<StatusCode> runBlocked(
        String key, List<Supplier<StatusCode>> actions, Runnable beforeRelease
    ) throws Exception {
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> locks.execute(List.of(key), () -> {
                acquired.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放科室锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                beforeRelease.run();
                return null;
            }));
            assertTrue(acquired.await(10, TimeUnit.SECONDS), "未取得科室锁");
            List<Future<StatusCode>> requests = new ArrayList<>();
            for (Supplier<StatusCode> action : actions) requests.add(pool.submit(action::get));
            assertTrue(waitForBlockedRequests(actions.size()), "请求未等待科室锁");
            assertUnchanged();
            release.countDown();
            holder.get(15, TimeUnit.SECONDS);
            List<StatusCode> results = new ArrayList<>();
            for (Future<StatusCode> request : requests) {
                results.add(request.get(15, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private boolean waitForBlockedRequests(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                    AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= expected) return true;
            Thread.sleep(25);
        }
        return false;
    }

    private void addRbacMembership() {
        jdbc.update("""
            INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, FIRST, ROLE);
    }

    private void addBusinessMembership(boolean deleted) {
        jdbc.update("""
            INSERT INTO accounts_departments(employee_id, account_id, dept_id, primary_role_id,
                start_date, is_deleted, deleted_by)
            VALUES (?, ?, ?, ?, current_timestamp, ?, ?)
            """, employeeId, ACCOUNT, FIRST, ROLE, deleted, deleted ? "original-deleter" : null);
    }

    private StatusCode update(String name) {
        return commands.updateDepartment(firstId(), FIRST, name, "新简称", "病区编码", "病区名称",
            "测试医院", OPERATOR);
    }

    private Integer firstId() {
        return jdbc.queryForObject("SELECT id FROM departments WHERE dept_id = ?",
            Integer.class, FIRST);
    }

    private String businessName() {
        return jdbc.queryForObject("SELECT name FROM departments WHERE dept_id = ?",
            String.class, FIRST);
    }

    private void assertUnchanged() {
        assertThat(businessName()).isEqualTo(FIRST);
        assertThat(deptRepo.findByDeptIdAndIsDeletedFalse(FIRST)).isPresent();
    }

    private DepartmentPB department(String code) {
        return DepartmentPB.newBuilder().setDeptId(code).setDeptName("并发重名科室")
            .setAbbreviation("简称").setHospitalName("测试医院").build();
    }

    private static final String PREFIX = "department-command-tests-";
    private static final String FIRST = PREFIX + "first";
    private static final String SECOND = PREFIX + "second";
    private static final String THIRD = PREFIX + "third";
    private static final String FOURTH = PREFIX + "fourth";
    private static final String ACCOUNT = PREFIX + "account";
    private static final String UPDATED_NAME = "科室更新后的名称";
    private static final String OPERATOR = "123456";
    private static final int ROLE = 982103;
    private List<Integer> originalActiveIds;
    private Long employeeId;
    @Autowired private DepartmentCommand commands;
    @Autowired private DepartmentRepository deptRepo;
    @Autowired private RbacDepartmentRepository rbacRepo;
    @Autowired private RbacDepartmentAccountRepository membershipRepo;
    @Autowired private DepartmentAccountRepository businessMembershipRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LockExecutor locks;
    @MockBean private BgaMetaService bgaMetaService;
    @MockBean private ScoreMetaService scoreMetaService;
    @MockBean private NursingOrderTemplateService nursingTemplateService;
}
