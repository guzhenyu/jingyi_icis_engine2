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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddAccountPermissionReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddRolePermissionReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class UserPermissionTests extends TestsBase {
    private enum Operation {
        ADD_ROLE, REVOKE_ROLE, ADD_ACCOUNT, REVOKE_ACCOUNT;

        boolean forAccount() {
            return this == ADD_ACCOUNT || this == REVOKE_ACCOUNT;
        }

        boolean revokes() {
            return this == REVOKE_ROLE || this == REVOKE_ACCOUNT;
        }
    }

    @BeforeEach
    void prepare() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("""
            INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, true), (?, ?, false)
            """, ROLE, "权限测试主角色", SECONDARY_ROLE, "权限测试附加角色");
        jdbcTemplate.update("INSERT INTO rbac_permissions(id, name) VALUES (?, ?)",
            PERMISSION, "权限测试权限");
        jdbcTemplate.update("INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)",
            DEPT, "权限测试科室");
        for (String accountId : List.of(CALLER, ACCOUNT, UNASSIGNED)) {
            jdbcTemplate.update("INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)",
                accountId, accountId);
        }
        accountAutoId = jdbcTemplate.queryForObject("""
            INSERT INTO accounts(account_id, name, is_deleted) VALUES (?, ?, false) RETURNING id
            """, Long.class, ACCOUNT, "权限测试用户");
        for (String accountId : List.of(CALLER, ACCOUNT)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
                VALUES (?, ?, ?)
                """, accountId, DEPT, ROLE);
        }
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_permissions(account_id, dept_id, permission_id)
            VALUES (?, ?, ?)
            """, CALLER, DEPT, Consts.PERM_ID_CONFIG_ACCOUNT);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        for (String accountId : List.of(CALLER, ACCOUNT, UNASSIGNED)) {
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments_permissions WHERE account_id = ?",
                accountId);
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", accountId);
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", accountId);
        }
        jdbcTemplate.update("DELETE FROM rbac_roles_permissions WHERE role_id IN (?, ?)",
            ROLE, SECONDARY_ROLE);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", DEPT);
        jdbcTemplate.update("DELETE FROM rbac_roles WHERE id IN (?, ?)", ROLE, SECONDARY_ROLE);
        jdbcTemplate.update("DELETE FROM rbac_permissions WHERE id = ?", PERMISSION);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?, ?, ?, ?, ?)
            """, LockKeys.role(ROLE), LockKeys.role(SECONDARY_ROLE), LockKeys.role(MISSING_ROLE),
            LockKeys.account(ACCOUNT), LockKeys.account(UNASSIGNED),
            LockKeys.account(accountAutoId.toString()));
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void nonAdministratorCannotGrantOrRevokeEvenWithAccountPermissionAndAdminAuthority(
        Operation operation
    ) {
        seedGrant(operation);
        authenticate(CALLER);

        assertThat(invokeService(operation).getCode())
            .isEqualTo(StatusCode.ONLY_ADMIN_IS_AUTHORIZED.getNumber());
        assertThat(grantCount(operation)).isEqualTo(1L);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void administratorCanGrantAndRevokeIdempotently(Operation operation) {
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        if (operation.revokes()) seedGrant(operation);

        assertThat(invokeService(operation).getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(invokeService(operation).getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(grantCount(operation)).isEqualTo(operation.revokes() ? 0L : 1L);
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void missingIdentityStillReportsAccountNotFound(Operation operation) {
        assertThat(invokeService(operation).getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
        authenticate("permission-tests-no-such-user");
        assertThat(invokeService(operation).getCode())
            .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
        assertThat(grantCount(operation)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Operation.class)
    void commandWaitsForTheRoleOrTargetAccountLockBeforeChangingGrants(Operation operation)
        throws Exception {
        if (operation.revokes()) seedGrant(operation);
        long initialCount = grantCount(operation);

        assertThat(runBlocked(operation, List.of(() -> invokeCommand(operation)), () -> {},
            () -> assertThat(grantCount(operation)).isEqualTo(initialCount)))
            .containsExactly(StatusCode.OK);
        assertThat(grantCount(operation)).isEqualTo(operation.revokes() ? 0L : 1L);
    }

    @ParameterizedTest
    @EnumSource(value = Operation.class, names = {"ADD_ROLE", "ADD_ACCOUNT"})
    void concurrentDuplicateGrantsReturnSuccessAndCreateOneRow(Operation operation)
        throws Exception {
        assertThat(runBlocked(operation, List.of(
            () -> invokeCommand(operation), () -> invokeCommand(operation)), () -> {},
            () -> assertThat(grantCount(operation)).isZero()))
            .containsExactly(StatusCode.OK, StatusCode.OK);
        assertThat(grantCount(operation)).isEqualTo(1L);
    }

    @Test
    void accountGrantRechecksMembershipAfterWaitingForTheAccountLock() throws Exception {
        assertThat(runBlocked(Operation.ADD_ACCOUNT,
            List.of(() -> invokeCommand(Operation.ADD_ACCOUNT)), () -> jdbcTemplate.update("""
                DELETE FROM rbac_accounts_departments WHERE account_id = ? AND dept_id = ?
                """, ACCOUNT, DEPT), () -> assertThat(grantCount(Operation.ADD_ACCOUNT)).isZero()))
            .containsExactly(StatusCode.DEPARTMENT_ACCOUNT_NOT_FOUND);
        assertThat(grantCount(Operation.ADD_ACCOUNT)).isZero();
    }

    @Test
    void grantingRequiresAccountDepartmentMembershipButMissingRevocationsRemainIdempotent() {
        assertThat(commands.addAccountPermission(UNASSIGNED, DEPT, PERMISSION, AUDIT))
            .isEqualTo(StatusCode.DEPARTMENT_ACCOUNT_NOT_FOUND);
        assertThat(commands.revokeAccountPermission(UNASSIGNED, DEPT, PERMISSION, AUDIT))
            .isEqualTo(StatusCode.OK);
        assertThat(grantCount(Operation.ADD_ACCOUNT)).isZero();
    }

    @Test
    void invalidResourceIdsDoNotBecomeInvalidLockKeysOrBusinessIds() {
        for (Integer roleId : List.of(0, -1)) {
            assertThat(commands.addRolePermission(roleId, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ROLE_NOT_FOUND);
            assertThat(commands.revokeRolePermission(roleId, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ROLE_NOT_FOUND);
        }
        for (String accountId : List.of("", "  ", accountAutoId.toString())) {
            assertThat(commands.addAccountPermission(accountId, DEPT, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
            assertThat(commands.revokeAccountPermission(accountId, DEPT, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ACCOUNT_NOT_FOUND);
        }
    }

    @Test
    void missingRolesPermissionsAndDepartmentsKeepTheirBusinessErrors() {
        for (Integer roleId : List.of(MISSING_ROLE, SECONDARY_ROLE)) {
            assertThat(commands.addRolePermission(roleId, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ROLE_NOT_FOUND);
            assertThat(commands.revokeRolePermission(roleId, PERMISSION, AUDIT))
                .isEqualTo(StatusCode.ROLE_NOT_FOUND);
        }
        assertThat(commands.addRolePermission(ROLE, MISSING_PERMISSION, AUDIT))
            .isEqualTo(StatusCode.PERMISSION_NOT_FOUND);
        assertThat(commands.revokeRolePermission(ROLE, MISSING_PERMISSION, AUDIT))
            .isEqualTo(StatusCode.PERMISSION_NOT_FOUND);
        assertThat(commands.addAccountPermission(ACCOUNT, DEPT, MISSING_PERMISSION, AUDIT))
            .isEqualTo(StatusCode.PERMISSION_NOT_FOUND);
        assertThat(commands.revokeAccountPermission(ACCOUNT, DEPT, MISSING_PERMISSION, AUDIT))
            .isEqualTo(StatusCode.PERMISSION_NOT_FOUND);
        assertThat(commands.addAccountPermission(ACCOUNT, DEPT + "-missing", PERMISSION, AUDIT))
            .isEqualTo(StatusCode.DEPARTMENT_NOT_FOUND);
        assertThat(commands.revokeAccountPermission(ACCOUNT, DEPT + "-missing", PERMISSION, AUDIT))
            .isEqualTo(StatusCode.DEPARTMENT_NOT_FOUND);
    }

    private StatusCode invokeCommand(Operation operation) {
        return switch (operation) {
            case ADD_ROLE -> commands.addRolePermission(ROLE, PERMISSION, AUDIT);
            case REVOKE_ROLE -> commands.revokeRolePermission(ROLE, PERMISSION, AUDIT);
            case ADD_ACCOUNT -> commands.addAccountPermission(ACCOUNT, DEPT, PERMISSION, AUDIT);
            case REVOKE_ACCOUNT ->
                commands.revokeAccountPermission(ACCOUNT, DEPT, PERMISSION, AUDIT);
        };
    }

    private ReturnCode invokeService(Operation operation) {
        String roleJson = ProtoUtils.protoToJson(AddRolePermissionReq.newBuilder()
            .setRoleId(ROLE).setPermissionId(PERMISSION).build());
        String accountJson = ProtoUtils.protoToJson(AddAccountPermissionReq.newBuilder()
            .setAccountId(ACCOUNT).setDeptId(DEPT).setPermissionId(PERMISSION).build());
        return switch (operation) {
            case ADD_ROLE -> service.addRolePermission(roleJson).getRt();
            case REVOKE_ROLE -> service.revokeRolePermission(roleJson).getRt();
            case ADD_ACCOUNT -> service.addAccountPermission(accountJson).getRt();
            case REVOKE_ACCOUNT -> service.revokeAccountPermission(accountJson).getRt();
        };
    }

    private void authenticate(String accountId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(accountId, "",
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
    }

    private void seedGrant(Operation operation) {
        if (operation.forAccount()) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments_permissions(
                    account_id, dept_id, permission_id)
                VALUES (?, ?, ?)
                """, ACCOUNT, DEPT, PERMISSION);
        } else {
            jdbcTemplate.update("""
                INSERT INTO rbac_roles_permissions(role_id, permission_id) VALUES (?, ?)
                """, ROLE, PERMISSION);
        }
    }

    private Long grantCount(Operation operation) {
        if (operation.forAccount()) {
            return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM rbac_accounts_departments_permissions
                WHERE account_id = ? AND dept_id = ? AND permission_id = ?
                """, Long.class, ACCOUNT, DEPT, PERMISSION);
        }
        return jdbcTemplate.queryForObject("""
            SELECT count(*) FROM rbac_roles_permissions WHERE role_id = ? AND permission_id = ?
            """, Long.class, ROLE, PERMISSION);
    }

    private List<StatusCode> runBlocked(
        Operation operation, List<Supplier<StatusCode>> actions, Runnable beforeCommit,
        Runnable assertWhileBlocked
    ) throws Exception {
        String resourceKey = operation.forAccount()
            ? LockKeys.account(ACCOUNT) : LockKeys.role(ROLE);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(actions.size() + 1);
        try {
            Future<?> holder = pool.submit(() -> executor.execute(List.of(resourceKey), () -> {
                locked.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS), "等待释放权限业务锁超时");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                beforeCommit.run();
                return null;
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS), "未取得权限业务锁");
            List<Future<StatusCode>> requests = new ArrayList<>();
            for (Supplier<StatusCode> action : actions) requests.add(pool.submit(action::get));
            assertTrue(waitForBlockedRequests(actions.size()), "权限操作未等待业务锁");
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
            Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE datname = current_database() AND wait_event_type = 'Lock'
                  AND query LIKE '%app_transaction_locks%'
                """, Integer.class);
            if (count != null && count >= expected) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static final String ACCOUNT = "permission-tests-account";
    private static final String CALLER = "permission-tests-caller";
    private static final String UNASSIGNED = "permission-tests-unassigned";
    private static final String DEPT = "permission-tests-dept";
    private static final String AUDIT = "123456";
    private static final int ROLE = 965001;
    private static final int SECONDARY_ROLE = 965002;
    private static final int MISSING_ROLE = 965003;
    private static final int PERMISSION = 965011;
    private static final int MISSING_PERMISSION = 965012;

    private Long accountAutoId;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private LockExecutor executor;
    @Autowired private UserCommand commands;
    @Autowired private UserService service;
}
