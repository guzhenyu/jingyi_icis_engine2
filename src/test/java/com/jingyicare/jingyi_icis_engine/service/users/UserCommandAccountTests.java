package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.auth.Encoder;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.RsaUtils;

class UserCommandAccountTests extends TestsBase {
    @BeforeEach
    void prepare() {
        jdbcTemplate.update("""
            INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, true)
            """, ROLE_ID, "账号迁移测试角色");
        jdbcTemplate.update("""
            INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
            """, DEPT, "账号迁移测试科室");
        jdbcTemplate.update("""
            INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
            """, OTHER_DEPT, "账号迁移测试科室二");
        jdbcTemplate.update("""
            INSERT INTO departments(dept_id, name, abbreviation, hospital_name, is_deleted)
            VALUES (?, ?, ?, ?, false)
            """, DEPT, "账号迁移测试科室", "迁移", "测试医院");
        for (String accountId : new String[] {CALLER, TARGET}) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts(account_id, account_name, password_hash)
                VALUES (?, ?, ?)
                """, accountId, "账号迁移测试用户", ORIGINAL_HASH);
        }
    }

    @AfterEach
    void cleanUp() {
        for (String accountId : List.of(CALLER, TARGET, NEW_ACCOUNT)) {
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", accountId);
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts_departments WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM rbac_accounts_roles WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", accountId);
        }
        jdbcTemplate.update(
            "DELETE FROM rbac_departments WHERE dept_id IN (?, ?)", DEPT, OTHER_DEPT);
        jdbcTemplate.update("DELETE FROM departments WHERE dept_id = ?", DEPT);
        jdbcTemplate.update("DELETE FROM rbac_roles WHERE id = ?", ROLE_ID);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?, ?, ?, ?, ?)
            """, LockKeys.account(NEW_ACCOUNT), LockKeys.account(TARGET),
            LockKeys.account(MISSING_ACCOUNT), LockKeys.department(DEPT),
            LockKeys.department(OTHER_DEPT), LockKeys.department(DEPT + "-missing"));
    }

    @Test
    void migratedAccountCreatesDepartmentMembershipAndSystemAudit() {
        AccountPB account = account(NEW_ACCOUNT, DEPT);

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.OK);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT modified_by FROM accounts WHERE account_id = ?", String.class, NEW_ACCOUNT))
            .isEqualTo(UserQuery.SYSTEM_ACCOUNT_ID);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT primary_role_id FROM rbac_accounts_departments
            WHERE account_id = ? AND dept_id = ?
            """, Integer.class, NEW_ACCOUNT, DEPT)).isEqualTo(ROLE_ID);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT primary_role_id FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = false
            """, Integer.class, NEW_ACCOUNT, DEPT)).isEqualTo(ROLE_ID);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM rbac_accounts_departments_roles
            WHERE account_id = ? AND dept_id = ? AND role_id = ?
            """, Long.class, NEW_ACCOUNT, DEPT, ROLE_ID)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM rbac_accounts_roles WHERE account_id = ?",
            Long.class, NEW_ACCOUNT)).isZero();
        assertThat(commands.changePassword(NEW_ACCOUNT, RsaUtils.encodePassword(NEW_ACCOUNT),
            RsaUtils.encodePassword("changed-password"))).isEqualTo(StatusCode.OK);
    }

    @Test
    void createdAccountAcceptsThePasswordEncodedByTheClient() {
        String password = RsaUtils.encodePassword("initial-password");
        AccountPB account = account(NEW_ACCOUNT, DEPT).toBuilder().setPassword(password).build();

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.OK);
        assertThat(commands.changePassword(
            NEW_ACCOUNT, password, RsaUtils.encodePassword("changed-password")))
            .isEqualTo(StatusCode.OK);
    }

    @Test
    void missingDepartmentDoesNotLeaveAPartiallyCreatedAccount() {
        assertThat(commands.addAccount(
            account(NEW_ACCOUNT, DEPT + "-missing"), UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.DEPARTMENT_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE account_id = ?", Long.class, NEW_ACCOUNT))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM rbac_accounts WHERE account_id = ?", Long.class, NEW_ACCOUNT))
            .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n", "\u2003"})
    @Transactional
    void blankAccountIdsAreRejectedBeforeLocking(String accountId) {
        AccountPB account = account(accountId, DEPT);

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
        assertThat(commands.updateAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
        assertThat(commands.deleteAccount(accountId, "", UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    @Transactional
    void nullAccountIdIsRejectedBeforeDeleteLockPlanning() {
        assertThat(commands.deleteAccount(null, "", UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t\n", "\u2003"})
    @Transactional
    void blankDepartmentIdsAreRejectedBeforeLocking(String deptId) {
        AccountPB account = account(NEW_ACCOUNT, deptId);

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.DEPT_IS_EMPTY);
        assertThat(commands.updateAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.DEPT_IS_EMPTY);
    }

    @ParameterizedTest
    @ValueSource(ints = {ROLE_ID, ROLE_ID + 1})
    @Transactional
    void duplicateDepartmentIsRejectedBeforeLocking(int repeatedRoleId) {
        AccountPB account = account(NEW_ACCOUNT, DEPT).toBuilder()
            .addDepartment(AccountDepartmentPB.newBuilder()
                .setDeptId(DEPT).setRoleId(repeatedRoleId))
            .build();

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_DEPARTMENT_DUPLICATE);
        for (String table : List.of("accounts", "rbac_accounts", "accounts_departments",
            "rbac_accounts_departments", "rbac_accounts_departments_roles")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, NEW_ACCOUNT))
                .as(table).isZero();
        }
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key IN (?, ?)",
            Long.class, LockKeys.account(NEW_ACCOUNT), LockKeys.department(DEPT))).isZero();
    }

    @Test
    void concurrentCreationWithoutDepartmentsReturnsOneDuplicateStatus() throws Exception {
        AccountPB account = AccountPB.newBuilder().setAccountId(NEW_ACCOUNT)
            .setAccountName("并发新增用户").build();
        Supplier<StatusCode> create =
            () -> commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID);

        assertThat(runBlocked(LockKeys.account(NEW_ACCOUNT), List.of(create, create)))
            .containsExactlyInAnyOrder(StatusCode.OK, StatusCode.ACCOUNT_ALREADY_EXISTS);
        for (String table : List.of("accounts", "rbac_accounts")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, NEW_ACCOUNT))
                .as(table).isEqualTo(1L);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void holdsEveryRequestedDepartmentLockBeforeWriting(boolean lockSecond) throws Exception {
        AccountPB account = account(NEW_ACCOUNT, DEPT).toBuilder()
            .addDepartment(AccountDepartmentPB.newBuilder()
                .setDeptId(OTHER_DEPT).setRoleId(ROLE_ID))
            .build();
        String resourceKey = LockKeys.department(lockSecond ? OTHER_DEPT : DEPT);

        assertThat(runBlocked(resourceKey, List.of(
            () -> commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))))
            .containsExactly(StatusCode.OK);
        assertThat(jdbcTemplate.queryForList("""
            SELECT dept_id FROM rbac_accounts_departments WHERE account_id = ?
            """, String.class, NEW_ACCOUNT)).containsExactlyInAnyOrder(DEPT, OTHER_DEPT);
    }

    @Test
    void administratorCanResetPasswordWithoutAGlobalRoleBinding() {
        List<Integer> previousRoles = jdbcTemplate.queryForList(
            "SELECT role_id FROM rbac_accounts_roles WHERE account_id = ?",
            Integer.class, UserQuery.ADMIN_ACCOUNT_ID);
        jdbcTemplate.update("DELETE FROM rbac_accounts_roles WHERE account_id = ?",
            UserQuery.ADMIN_ACCOUNT_ID);

        try {
            assertThat(commands.resetPassword(TARGET, UserQuery.ADMIN_ACCOUNT_ID))
                .isEqualTo(StatusCode.OK);
            assertThat(encoder.get().matches(RsaUtils.encodePassword(TARGET), passwordHash()))
                .isTrue();
        } finally {
            for (Integer roleId : previousRoles) {
                jdbcTemplate.update("""
                    INSERT INTO rbac_accounts_roles(account_id, role_id) VALUES (?, ?)
                    """, UserQuery.ADMIN_ACCOUNT_ID, roleId);
            }
        }
    }

    @Test
    void aLegacyAdministratorRoleDoesNotAuthorizeAnotherAccountToResetPasswords() {
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_roles(account_id, role_id) VALUES (?, ?)
            """, CALLER, config.getUser().getAdminRoleId());

        assertThat(commands.resetPassword(TARGET, CALLER))
            .isEqualTo(StatusCode.NO_PERMISSION_TO_OPERATE);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class, TARGET))
            .isEqualTo(ORIGINAL_HASH);
    }

    @Test
    void changingPasswordStillAcceptsLegacyRawInput() {
        setPassword("initial-password");

        assertThat(commands.changePassword(TARGET, "initial-password", "changed-password"))
            .isEqualTo(StatusCode.OK);
        assertThat(encoder.get().matches(
            RsaUtils.encodePassword("changed-password"), passwordHash())).isTrue();
    }

    @Test
    void wrongOldPasswordDoesNotChangeTheStoredHash() {
        setPassword("initial-password");
        String originalHash = passwordHash();

        assertThat(commands.changePassword(TARGET, "wrong-password", "changed-password"))
            .isEqualTo(StatusCode.WRONG_PASSWORD);
        assertThat(passwordHash()).isEqualTo(originalHash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "  ", MISSING_ACCOUNT})
    void passwordOperationsReportMissingAccountsWithoutInvalidLockKeys(String accountId) {
        assertThat(commands.changePassword(accountId, "old", "new"))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
        assertThat(commands.resetPassword(accountId, UserQuery.ADMIN_ACCOUNT_ID))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void passwordChangesAndResetsWaitForTheTargetAccountLock(boolean reset) throws Exception {
        setPassword("initial-password");
        String originalHash = passwordHash();
        Supplier<StatusCode> action = reset
            ? () -> commands.resetPassword(TARGET, UserQuery.ADMIN_ACCOUNT_ID)
            : () -> commands.changePassword(TARGET, RsaUtils.encodePassword("initial-password"),
                RsaUtils.encodePassword("changed-password"));

        assertThat(runBlocked(LockKeys.account(TARGET), List.of(action),
            () -> assertThat(passwordHash()).isEqualTo(originalHash)))
            .containsExactly(StatusCode.OK);
        assertThat(encoder.get().matches(RsaUtils.encodePassword(
            reset ? TARGET : "changed-password"), passwordHash())).isTrue();
    }

    @Test
    void concurrentChangesWithTheSameOldPasswordOnlyAllowOneSuccess() throws Exception {
        setPassword("initial-password");
        String originalHash = passwordHash();
        String oldPassword = RsaUtils.encodePassword("initial-password");

        assertThat(runBlocked(LockKeys.account(TARGET), List.of(
            () -> commands.changePassword(TARGET, oldPassword, RsaUtils.encodePassword("first")),
            () -> commands.changePassword(TARGET, oldPassword, RsaUtils.encodePassword("second"))),
            () -> assertThat(passwordHash()).isEqualTo(originalHash)))
            .containsExactlyInAnyOrder(StatusCode.OK, StatusCode.WRONG_PASSWORD);
        String hash = passwordHash();
        assertThat(encoder.get().matches(RsaUtils.encodePassword("first"), hash)
            || encoder.get().matches(RsaUtils.encodePassword("second"), hash)).isTrue();
    }

    @Test
    void resetAndChangeShareTheSameAccountLock() throws Exception {
        setPassword("initial-password");
        String originalHash = passwordHash();

        List<StatusCode> results = runBlocked(LockKeys.account(TARGET), List.of(
            () -> commands.resetPassword(TARGET, UserQuery.ADMIN_ACCOUNT_ID),
            () -> commands.changePassword(TARGET, RsaUtils.encodePassword("initial-password"),
                RsaUtils.encodePassword("changed-password"))),
            () -> assertThat(passwordHash()).isEqualTo(originalHash));

        assertThat(results.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(results.getLast()).isIn(StatusCode.OK, StatusCode.WRONG_PASSWORD);
        assertThat(encoder.get().matches(RsaUtils.encodePassword(TARGET), passwordHash())).isTrue();
    }

    private void setPassword(String password) {
        jdbcTemplate.update("UPDATE rbac_accounts SET password_hash = ? WHERE account_id = ?",
            RsaUtils.encodePassword(password, encoder.get()), TARGET);
    }

    private String passwordHash() {
        return jdbcTemplate.queryForObject(
            "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class, TARGET);
    }

    private List<StatusCode> runBlocked(
        String resourceKey, List<Supplier<StatusCode>> actions
    ) throws Exception {
        return runBlocked(resourceKey, actions, () -> {});
    }

    private List<StatusCode> runBlocked(
        String resourceKey, List<Supplier<StatusCode>> actions, Runnable assertWhileBlocked
    ) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> lockExecutor.execute(List.of(resourceKey), () -> {
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
            List<Future<StatusCode>> requests = new ArrayList<>();
            for (Supplier<StatusCode> action : actions) {
                requests.add(pool.submit(action::get));
            }
            assertTrue(waitForBlockedRequests(actions.size()), "请求未等待业务锁");
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rbac_accounts WHERE account_id = ?", Long.class, NEW_ACCOUNT))
                .isZero();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts WHERE account_id = ?", Long.class, NEW_ACCOUNT))
                .isZero();
            assertWhileBlocked.run();
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
            Integer waiting = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (waiting != null && waiting >= expected) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private AccountPB account(String accountId, String deptId) {
        return AccountPB.newBuilder().setAccountId(accountId).setAccountName("新增迁移用户")
            .addDepartment(AccountDepartmentPB.newBuilder().setDeptId(deptId).setRoleId(ROLE_ID))
            .build();
    }

    private static final String PREFIX = "user-cmd-account-tests-";
    private static final String CALLER = PREFIX + "caller";
    private static final String TARGET = PREFIX + "target";
    private static final String NEW_ACCOUNT = PREFIX + "new";
    private static final String MISSING_ACCOUNT = PREFIX + "missing";
    private static final String DEPT = PREFIX + "dept";
    private static final String OTHER_DEPT = PREFIX + "other-dept";
    private static final String ORIGINAL_HASH = "unchanged-test-hash";
    private static final int ROLE_ID = 920031;

    @Autowired private UserCommand commands;
    @Autowired private LockExecutor lockExecutor;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Config config;
    @Autowired private Encoder encoder;
}
