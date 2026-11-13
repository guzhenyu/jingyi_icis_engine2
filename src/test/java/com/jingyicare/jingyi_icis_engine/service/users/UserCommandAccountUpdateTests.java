package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountJdbc;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountJdbc.DeletePlan;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

class UserCommandAccountUpdateTests extends TestsBase {
    @BeforeEach
    void prepare() {
        for (Integer roleId : List.of(FIRST_ROLE, SECOND_ROLE, EXTRA_ROLE)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, ?)
                """, roleId, "账号更新测试角色", roleId != EXTRA_ROLE);
        }
        jdbcTemplate.update("INSERT INTO rbac_permissions(id, name) VALUES (?, ?)",
            PERMISSION, "账号更新测试权限");
        for (String deptId : List.of(UPDATED_DEPT, REMOVED_DEPT, ADDED_DEPT)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
                """, deptId, deptId);
        }
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts(account_id, account_name, password_hash) VALUES (?, ?, ?)
            """, ACCOUNT, ORIGINAL_NAME, "unchanged-password");
        employeeId = jdbcTemplate.queryForObject("""
            INSERT INTO accounts(account_id, name, is_deleted) VALUES (?, ?, false) RETURNING id
            """, Long.class, ACCOUNT, ORIGINAL_NAME);
        for (String deptId : List.of(UPDATED_DEPT, REMOVED_DEPT)) {
            addMembership(deptId);
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments_roles(account_id, dept_id, role_id)
                VALUES (?, ?, ?)
                """, ACCOUNT, deptId, EXTRA_ROLE);
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments_permissions(
                    account_id, dept_id, permission_id)
                VALUES (?, ?, ?)
                """, ACCOUNT, deptId, PERMISSION);
        }
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_permissions WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts_roles WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts_permissions WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id IN (?, ?, ?)",
            UPDATED_DEPT, REMOVED_DEPT, ADDED_DEPT);
        jdbcTemplate.update("DELETE FROM rbac_roles WHERE id IN (?, ?, ?)",
            FIRST_ROLE, SECOND_ROLE, EXTRA_ROLE);
        jdbcTemplate.update("DELETE FROM rbac_permissions WHERE id = ?", PERMISSION);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?, ?, ?, ?)
            """, LockKeys.account(ACCOUNT), LockKeys.department(UPDATED_DEPT),
            LockKeys.department(REMOVED_DEPT), LockKeys.department(ADDED_DEPT),
            LockKeys.department(MISSING_DEPT));
    }

    @Test
    void updatesBothAccountTablesAndAddsChangesAndRemovesMemberships() {
        assertThat(commands.updateAccount(update(), OPERATOR)).isEqualTo(StatusCode.OK);

        assertNames(UPDATED_NAME);
        assertThat(activeDepartments()).containsExactlyInAnyOrder(UPDATED_DEPT, ADDED_DEPT);
        assertThat(primaryRole(UPDATED_DEPT)).isEqualTo(SECOND_ROLE);
        assertThat(primaryRole(ADDED_DEPT)).isEqualTo(FIRST_ROLE);
        assertThat(jdbcTemplate.queryForList("""
            SELECT role_id FROM rbac_accounts_departments_roles
            WHERE account_id = ? AND dept_id = ?
            """, Integer.class, ACCOUNT, UPDATED_DEPT))
            .containsExactlyInAnyOrder(SECOND_ROLE, EXTRA_ROLE);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT primary_role_id FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = false
            """, Integer.class, ACCOUNT, UPDATED_DEPT)).isEqualTo(SECOND_ROLE);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT employee_id FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = false
            """, Long.class, ACCOUNT, ADDED_DEPT)).isEqualTo(employeeId);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = true
              AND deleted_by = ? AND deleted_at IS NOT NULL
            """, Long.class, ACCOUNT, REMOVED_DEPT, OPERATOR)).isEqualTo(1L);
        for (String table : List.of(
            "rbac_accounts_departments_roles", "rbac_accounts_departments_permissions")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ? AND dept_id = ?",
                Long.class, ACCOUNT, REMOVED_DEPT)).as(table).isZero();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "missing, 950041, DEPARTMENT_NOT_FOUND",
        "added, 999999, ROLE_NOT_FOUND",
        "added, 950043, ROLE_NOT_PRIMARY"
    })
    void invalidDepartmentOrRoleLeavesAllExistingDataUnchanged(
        String department, int roleId, StatusCode expected
    ) {
        AccountPB account = update().toBuilder().clearDepartment()
            .addDepartment(department(UPDATED_DEPT, SECOND_ROLE))
            .addDepartment(department(
                department.equals("missing") ? MISSING_DEPT : ADDED_DEPT, roleId))
            .build();

        assertThat(commands.updateAccount(account, OPERATOR)).isEqualTo(expected);
        assertUnchanged();
    }

    @ParameterizedTest
    @EnumSource(value = StatusCode.class, names = {
        "DEPARTMENT_ACCOUNT_ROLE_NOT_FOUND", "DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS"
    })
    void invalidRoleBindingDoesNotCommitProfileOrMembershipChanges(StatusCode expected) {
        if (expected == StatusCode.DEPARTMENT_ACCOUNT_ROLE_NOT_FOUND) {
            jdbcTemplate.update("""
                DELETE FROM rbac_accounts_departments_roles
                WHERE account_id = ? AND dept_id = ? AND role_id = ?
                """, ACCOUNT, UPDATED_DEPT, FIRST_ROLE);
        } else {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments_roles(account_id, dept_id, role_id)
                VALUES (?, ?, ?)
                """, ACCOUNT, UPDATED_DEPT, SECOND_ROLE);
        }

        assertThat(commands.updateAccount(update(), OPERATOR)).isEqualTo(expected);
        assertUnchanged();
        if (expected == StatusCode.DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS) {
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rbac_accounts_departments_roles
                WHERE account_id = ? AND dept_id = ? AND role_id = ?
                """, Long.class, ACCOUNT, UPDATED_DEPT, FIRST_ROLE)).isEqualTo(1L);
        }
    }

    @Test
    void missingRbacAccountDoesNotUpdateTheBusinessAccount() {
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_permissions WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", ACCOUNT);

        assertThat(commands.updateAccount(update(), OPERATOR))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE account_id = ?", String.class, ACCOUNT))
            .isEqualTo(ORIGINAL_NAME);
    }

    @ParameterizedTest
    @ValueSource(ints = {FIRST_ROLE, SECOND_ROLE})
    @Transactional
    void duplicateDepartmentsAreRejectedBeforeLockPlanning(int roleId) {
        AccountPB account = update().toBuilder()
            .addDepartment(department(UPDATED_DEPT, roleId)).build();

        assertThat(commands.updateAccount(account, OPERATOR))
            .isEqualTo(StatusCode.ACCOUNT_DEPARTMENT_DUPLICATE);
        assertUnchanged();
    }

    @Test
    void emptyDepartmentListRemovesAllMemberships() {
        AccountPB account = update().toBuilder().clearDepartment().build();

        assertThat(commands.updateAccount(account, OPERATOR)).isEqualTo(StatusCode.OK);
        assertNames(UPDATED_NAME);
        assertThat(activeDepartments()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND is_deleted = true AND deleted_by = ?
            """, Long.class, ACCOUNT, OPERATOR)).isEqualTo(2L);
    }

    @Test
    void databaseFailureRollsBackProfileAndMembershipChanges() {
        AccountPB account = update().toBuilder().setTitle("x".repeat(1000)).build();

        assertThatThrownBy(() -> commands.updateAccount(account, OPERATOR))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"present", "missing", "deleted"})
    void removesOnlyTheTargetDepartmentAndSoftDeletesItsActiveBusinessMembership(String state) {
        if (state.equals("missing")) {
            jdbcTemplate.update("""
                DELETE FROM accounts_departments WHERE account_id = ? AND dept_id = ?
                """, ACCOUNT, REMOVED_DEPT);
        } else {
            if (state.equals("deleted")) {
                jdbcTemplate.update("""
                    DELETE FROM accounts_departments WHERE account_id = ? AND dept_id = ?
                    """, ACCOUNT, REMOVED_DEPT);
            }
            jdbcTemplate.update("""
                INSERT INTO accounts_departments(employee_id, account_id, dept_id, primary_role_id,
                    start_date, is_deleted, deleted_by, deleted_at)
                VALUES (?, ?, ?, ?, now(), true, 'original-operator', '2020-01-01')
                """, employeeId, ACCOUNT, REMOVED_DEPT, FIRST_ROLE);
        }

        assertThat(commands.deleteAccount(ACCOUNT, REMOVED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);

        assertNames(ORIGINAL_NAME);
        assertThat(activeDepartments()).containsExactly(UPDATED_DEPT);
        for (String table : List.of("rbac_accounts_departments_permissions",
            "rbac_accounts_departments_roles", "rbac_accounts_departments")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ? AND dept_id = ?",
                Long.class, ACCOUNT, REMOVED_DEPT)).as(table).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = false
            """, Long.class, ACCOUNT, REMOVED_DEPT)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND deleted_by = ? AND deleted_at IS NOT NULL
            """, Long.class, ACCOUNT, REMOVED_DEPT, OPERATOR))
            .isEqualTo(state.equals("present") ? 1L : 0L);
        if (!state.equals("missing")) {
            assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM accounts_departments
                WHERE account_id = ? AND dept_id = ? AND deleted_by = 'original-operator'
                  AND deleted_at = '2020-01-01' AND is_deleted = true
                """, Long.class, ACCOUNT, REMOVED_DEPT)).isEqualTo(1L);
        }
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = false
            """, Long.class, ACCOUNT, UPDATED_DEPT)).isEqualTo(1L);
    }

    @Test
    void failedSoftDeletionRollsBackAllRbacMembershipDeletions() {
        assertThatThrownBy(() -> commands.deleteAccount(ACCOUNT, REMOVED_DEPT, "x".repeat(1000)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertUnchanged();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM rbac_accounts_departments_permissions
            WHERE account_id = ? AND dept_id = ?
            """, Long.class, ACCOUNT, REMOVED_DEPT)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM rbac_accounts_departments_roles
            WHERE account_id = ? AND dept_id = ?
            """, Long.class, ACCOUNT, REMOVED_DEPT)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " \t"})
    void deletingAllDepartmentsAlsoCleansBusinessOnlyMembershipsAndLegacyGrants(String deptId) {
        addBusinessMembership(ADDED_DEPT);
        addLegacyGrants();

        assertThat(commands.deleteAccount(ACCOUNT, deptId, OPERATOR)).isEqualTo(StatusCode.OK);

        assertAccountDeleted();
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments
            WHERE account_id = ? AND is_deleted = true AND deleted_by = ?
            """, Long.class, ACCOUNT, OPERATOR)).isEqualTo(3L);
    }

    @Test
    void deletingTheLastDepartmentDeletesTheAccountAndRetainsGrantsUntilThen() {
        addLegacyGrants();

        assertThat(commands.deleteAccount(ACCOUNT, REMOVED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);
        assertNames(ORIGINAL_NAME);
        for (String table : List.of("rbac_accounts_roles", "rbac_accounts_permissions")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, ACCOUNT))
                .as(table).isEqualTo(1L);
        }

        assertThat(commands.deleteAccount(ACCOUNT, UPDATED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);
        assertAccountDeleted();
        assertThat(commands.deleteAccount(ACCOUNT, "", OPERATOR))
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void anAccountWithoutDepartmentsCanBeDeleted() {
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_permissions WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM rbac_accounts_departments WHERE account_id = ?", ACCOUNT);
        jdbcTemplate.update("DELETE FROM accounts_departments WHERE account_id = ?", ACCOUNT);
        addLegacyGrants();

        assertThat(commands.deleteAccount(ACCOUNT, "", OPERATOR)).isEqualTo(StatusCode.OK);
        assertAccountDeleted();
    }

    @Test
    void aRemainingBusinessOnlyMembershipKeepsTheAccountUntilItIsRemoved() {
        addBusinessMembership(ADDED_DEPT);
        assertThat(commands.deleteAccount(ACCOUNT, REMOVED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);
        assertThat(commands.deleteAccount(ACCOUNT, UPDATED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);

        assertNames(ORIGINAL_NAME);
        assertThat(activeDepartments()).isEmpty();
        assertThat(jdbcTemplate.queryForList("""
            SELECT dept_id FROM accounts_departments WHERE account_id = ? AND is_deleted = false
            """, String.class, ACCOUNT)).containsExactly(ADDED_DEPT);

        assertThat(commands.deleteAccount(ACCOUNT, ADDED_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);
        assertAccountDeleted();
    }

    @Test
    void deletingAnUnrelatedDepartmentDoesNotDeleteTheAccountOrExistingMemberships() {
        assertThat(commands.deleteAccount(ACCOUNT, MISSING_DEPT, OPERATOR))
            .isEqualTo(StatusCode.OK);
        assertUnchanged();
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "replacement"})
    void deletionPlanDetectsBusinessChangesEvenWhenDepartmentIdsStayTheSame(String change) {
        DeletePlan before = RbacDepartmentAccountJdbc.findDeletePlan(jdbcTemplate, ACCOUNT);
        if (change.equals("role")) {
            jdbcTemplate.update("""
                UPDATE accounts_departments SET primary_role_id = ?
                WHERE account_id = ? AND dept_id = ? AND is_deleted = false
                """, SECOND_ROLE, ACCOUNT, UPDATED_DEPT);
        } else {
            jdbcTemplate.update("""
                DELETE FROM accounts_departments WHERE account_id = ? AND dept_id = ?
                """, ACCOUNT, UPDATED_DEPT);
            addBusinessMembership(UPDATED_DEPT);
        }
        DeletePlan after = RbacDepartmentAccountJdbc.findDeletePlan(jdbcTemplate, ACCOUNT);

        assertThat(after.rbacDeptIds()).isEqualTo(before.rbacDeptIds());
        assertThat(after.departmentIds()).isEqualTo(before.departmentIds());
        assertThat(after).isNotEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "updated", "removed", "business"})
    void deletionWaitsForTheAccountAndEveryRbacOrBusinessDepartment(String resource)
        throws Exception {
        addBusinessMembership(ADDED_DEPT);
        String resourceKey = switch (resource) {
            case "account" -> LockKeys.account(ACCOUNT);
            case "updated" -> LockKeys.department(UPDATED_DEPT);
            case "removed" -> LockKeys.department(REMOVED_DEPT);
            default -> LockKeys.department(ADDED_DEPT);
        };

        assertThat(runBlocked(resourceKey,
            List.of(() -> commands.deleteAccount(ACCOUNT, "", OPERATOR)), () -> {}, () -> {
                assertNames(ORIGINAL_NAME);
                assertThat(activeDepartments())
                    .containsExactlyInAnyOrder(UPDATED_DEPT, REMOVED_DEPT);
                assertThat(jdbcTemplate.queryForObject("""
                    SELECT count(*) FROM accounts_departments
                    WHERE account_id = ? AND is_deleted = false
                    """, Long.class, ACCOUNT)).isEqualTo(3L);
            })).containsExactly(StatusCode.OK);
        assertAccountDeleted();
    }

    @Test
    void deletionReplansWhenABusinessMembershipAppearsWhileWaiting() throws Exception {
        assertThat(runBlocked(LockKeys.account(ACCOUNT),
            List.of(() -> commands.deleteAccount(ACCOUNT, "", OPERATOR)),
            () -> addBusinessMembership(ADDED_DEPT))).containsExactly(StatusCode.OK);

        assertAccountDeleted();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Long.class, LockKeys.department(ADDED_DEPT))).isEqualTo(1L);
    }

    @Test
    void concurrentDepartmentDeletionsRemoveTheAccountAfterTheLastMembership() throws Exception {
        assertThat(runBlocked(LockKeys.account(ACCOUNT), List.of(
            () -> commands.deleteAccount(ACCOUNT, UPDATED_DEPT, OPERATOR),
            () -> commands.deleteAccount(ACCOUNT, REMOVED_DEPT, OPERATOR)), () -> {}))
            .containsExactly(StatusCode.OK, StatusCode.OK);
        assertAccountDeleted();
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "updated", "removed", "added"})
    void waitsForAccountAndEveryAffectedDepartmentBeforeWriting(String resource) throws Exception {
        String resourceKey = switch (resource) {
            case "account" -> LockKeys.account(ACCOUNT);
            case "updated" -> LockKeys.department(UPDATED_DEPT);
            case "removed" -> LockKeys.department(REMOVED_DEPT);
            default -> LockKeys.department(ADDED_DEPT);
        };

        assertThat(runBlocked(resourceKey,
            List.of(() -> commands.updateAccount(update(), OPERATOR)), () -> {}))
            .containsExactly(StatusCode.OK);
        assertNames(UPDATED_NAME);
    }

    @Test
    void replansWhenAnotherUpdateAddsADepartmentWhileWaitingForAccountLock() throws Exception {
        AccountPB account = update().toBuilder().clearDepartment()
            .addDepartment(department(UPDATED_DEPT, FIRST_ROLE)).build();

        assertThat(runBlocked(LockKeys.account(ACCOUNT),
            List.of(() -> commands.updateAccount(account, OPERATOR)),
            () -> addMembership(ADDED_DEPT))).containsExactly(StatusCode.OK);

        assertThat(activeDepartments()).containsExactly(UPDATED_DEPT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Long.class, LockKeys.department(ADDED_DEPT))).isEqualTo(1L);
    }

    @Test
    void concurrentUpdatesLeaveOneCompleteAccountState() throws Exception {
        AccountPB first = update().toBuilder().setAccountName("第一次更新").build();
        AccountPB second = update().toBuilder().setAccountName("第二次更新").clearDepartment()
            .addDepartment(department(REMOVED_DEPT, FIRST_ROLE)).build();

        assertThat(runBlocked(LockKeys.account(ACCOUNT), List.of(
            () -> commands.updateAccount(first, OPERATOR),
            () -> commands.updateAccount(second, OPERATOR)), () -> {}))
            .containsExactly(StatusCode.OK, StatusCode.OK);

        String name = jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE account_id = ?", String.class, ACCOUNT);
        assertThat(name).isIn("第一次更新", "第二次更新");
        assertNames(name);
        if (name.equals("第一次更新")) {
            assertThat(activeDepartments()).containsExactlyInAnyOrder(UPDATED_DEPT, ADDED_DEPT);
            assertThat(primaryRole(UPDATED_DEPT)).isEqualTo(SECOND_ROLE);
        } else {
            assertThat(activeDepartments()).containsExactly(REMOVED_DEPT);
        }
    }

    private List<StatusCode> runBlocked(
        String resourceKey, List<Supplier<StatusCode>> actions, Runnable beforeHolderCommit
    ) throws Exception {
        return runBlocked(resourceKey, actions, beforeHolderCommit, this::assertUnchanged);
    }

    private List<StatusCode> runBlocked(
        String resourceKey, List<Supplier<StatusCode>> actions, Runnable beforeHolderCommit,
        Runnable assertWhileBlocked
    ) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> lockExecutor.execute(List.of(resourceKey), () -> {
                locked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放账号业务锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                beforeHolderCommit.run();
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得账号业务锁");
            List<Future<StatusCode>> requests = new ArrayList<>();
            for (Supplier<StatusCode> action : actions) requests.add(pool.submit(action::get));
            assertTrue(waitForBlockedRequests(actions.size()), "更新请求未等待业务锁");
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

    private void addMembership(String deptId) {
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, deptId, FIRST_ROLE);
        addBusinessMembership(deptId);
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_roles(account_id, dept_id, role_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, deptId, FIRST_ROLE);
    }

    private void addBusinessMembership(String deptId) {
        jdbcTemplate.update("""
            INSERT INTO accounts_departments(
                employee_id, account_id, dept_id, primary_role_id, start_date, is_deleted)
            VALUES (?, ?, ?, ?, now(), false)
            """, employeeId, ACCOUNT, deptId, FIRST_ROLE);
    }

    private void addLegacyGrants() {
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_roles(account_id, role_id) VALUES (?, ?)
            """, ACCOUNT, FIRST_ROLE);
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_permissions(account_id, permission_id) VALUES (?, ?)
            """, ACCOUNT, PERMISSION);
    }

    private void assertAccountDeleted() {
        for (String table : List.of("rbac_accounts", "rbac_accounts_departments",
            "rbac_accounts_departments_roles", "rbac_accounts_departments_permissions",
            "rbac_accounts_roles", "rbac_accounts_permissions")) {
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE account_id = ?", Long.class, ACCOUNT))
                .as(table).isZero();
        }
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts
            WHERE account_id = ? AND is_deleted = true AND deleted_by = ?
              AND deleted_at IS NOT NULL
            """, Long.class, ACCOUNT, OPERATOR)).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments WHERE account_id = ? AND is_deleted = false
            """, Long.class, ACCOUNT)).isZero();
    }

    private void assertUnchanged() {
        assertNames(ORIGINAL_NAME);
        assertThat(activeDepartments()).containsExactlyInAnyOrder(UPDATED_DEPT, REMOVED_DEPT);
        assertThat(primaryRole(UPDATED_DEPT)).isEqualTo(FIRST_ROLE);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT count(*) FROM accounts_departments WHERE account_id = ? AND is_deleted = false
            """, Long.class, ACCOUNT)).isEqualTo(2L);
    }

    private void assertNames(String expected) {
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE account_id = ?", String.class, ACCOUNT))
            .isEqualTo(expected);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT account_name FROM rbac_accounts WHERE account_id = ?", String.class, ACCOUNT))
            .isEqualTo(expected);
    }

    private Set<String> activeDepartments() {
        return Set.copyOf(jdbcTemplate.queryForList(
            "SELECT dept_id FROM rbac_accounts_departments WHERE account_id = ?",
            String.class, ACCOUNT));
    }

    private Integer primaryRole(String deptId) {
        return jdbcTemplate.queryForObject("""
            SELECT primary_role_id FROM rbac_accounts_departments
            WHERE account_id = ? AND dept_id = ?
            """, Integer.class, ACCOUNT, deptId);
    }

    private AccountPB update() {
        return AccountPB.newBuilder().setAccountId(ACCOUNT).setAccountName(UPDATED_NAME)
            .setDateOfBirthIso8601("1990-01-01T00:00:00Z")
            .addDepartment(department(UPDATED_DEPT, SECOND_ROLE))
            .addDepartment(department(ADDED_DEPT, FIRST_ROLE)).build();
    }

    private AccountDepartmentPB department(String deptId, int roleId) {
        return AccountDepartmentPB.newBuilder().setDeptId(deptId).setRoleId(roleId).build();
    }

    private static final String ACCOUNT = "user-command-update-tests";
    private static final String UPDATED_DEPT = ACCOUNT + "-updated";
    private static final String REMOVED_DEPT = ACCOUNT + "-removed";
    private static final String ADDED_DEPT = ACCOUNT + "-added";
    private static final String MISSING_DEPT = ACCOUNT + "-missing";
    private static final String ORIGINAL_NAME = "更新前姓名";
    private static final String UPDATED_NAME = "更新后姓名";
    private static final String OPERATOR = "123456";
    private static final int FIRST_ROLE = 950041;
    private static final int SECOND_ROLE = 950042;
    private static final int EXTRA_ROLE = 950043;
    private static final int PERMISSION = 950044;

    private Long employeeId;

    @Autowired private UserCommand commands;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LockExecutor lockExecutor;
}
