package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.IcisAccountPB;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

class UserQueryTests extends TestsBase {
    @BeforeEach
    void prepare() {
        cleanUp();
        for (String code : List.of(ACCOUNT, RBAC_ONLY, DELETED)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)
                """, code, "RBAC姓名");
        }
        accountAutoId = saveAccount(ACCOUNT, "用户姓名", false);
        saveAccount(DELETED, "已删除用户", true);
        saveAccount(NO_RBAC, "无认证账号的用户", false);
        jdbcTemplate.update("""
            INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
            """, DEPT, "查询测试科室");
        for (int roleId : List.of(PRIMARY_ROLE, CHILD_ROLE)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, ?)
                """, roleId, "查询角色" + roleId, roleId == PRIMARY_ROLE);
        }
        for (int permissionId : List.of(INHERITED_PERMISSION, DIRECT_PERMISSION)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_permissions(id, name) VALUES (?, ?)
                """, permissionId, "查询权限" + permissionId);
        }
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, DEPT, PRIMARY_ROLE);
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_roles(account_id, dept_id, role_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, DEPT, PRIMARY_ROLE);
        jdbcTemplate.update("""
            INSERT INTO rbac_roles_roles(parent_role_id, child_role_id) VALUES (?, ?), (?, ?)
            """, PRIMARY_ROLE, CHILD_ROLE, CHILD_ROLE, PRIMARY_ROLE);
        jdbcTemplate.update("""
            INSERT INTO rbac_roles_permissions(role_id, permission_id) VALUES (?, ?)
            """, CHILD_ROLE, INHERITED_PERMISSION);
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_permissions(account_id, dept_id, permission_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, DEPT, DIRECT_PERMISSION);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_permissions WHERE dept_id = ?", DEPT);
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments_roles WHERE dept_id = ?", DEPT);
        jdbcTemplate.update(
            "DELETE FROM rbac_roles_roles WHERE parent_role_id IN (?, ?)",
            PRIMARY_ROLE, CHILD_ROLE);
        jdbcTemplate.update(
            "DELETE FROM rbac_roles_permissions WHERE role_id IN (?, ?)", PRIMARY_ROLE, CHILD_ROLE);
        jdbcTemplate.update("DELETE FROM rbac_accounts_departments WHERE dept_id = ?", DEPT);
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id = ?", DEPT);
        jdbcTemplate.update("DELETE FROM rbac_roles WHERE id IN (?, ?)", PRIMARY_ROLE, CHILD_ROLE);
        jdbcTemplate.update("DELETE FROM rbac_permissions WHERE id IN (?, ?)",
            INHERITED_PERMISSION, DIRECT_PERMISSION);
        jdbcTemplate.update("DELETE FROM accounts WHERE account_id LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id LIKE ?", PREFIX + "%");
    }

    @Test
    void preservesMissingAndAnonymousLoginResults() {
        assertThat(query.getCtxAccountId()).isEmpty();
        assertThat(query.getAccountWithAutoId()).isNull();
        assertThat(query.hasPermission(DEPT, DIRECT_PERMISSION)).isNull();
        authenticate("anonymousUser");
        assertThat(query.getCtxAccountId()).isEmpty();
        assertThat(query.getAccountWithAutoId()).isNull();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(ACCOUNT, ""));
        assertThat(query.getCtxAccountId()).isEmpty();
    }

    @Test
    void preservesAutoIdAndRbacFallbackForCurrentUser() {
        authenticate(ACCOUNT);
        Pair<String, String> current = query.getAccountWithAutoId();
        assertThat(query.getCtxAccountId()).isEqualTo(ACCOUNT);
        assertThat(current.getFirst()).isEqualTo(accountAutoId.toString());
        assertThat(current.getSecond()).isEqualTo("用户姓名");
        for (String code : List.of(RBAC_ONLY, DELETED)) {
            authenticate(code);
            Pair<String, String> fallback = query.getAccountWithAutoId();
            assertThat(fallback.getFirst()).isEqualTo(code);
            assertThat(fallback.getSecond()).isEqualTo("RBAC姓名");
        }
        authenticate(NO_RBAC);
        assertThat(query.getAccountWithAutoId()).isNull();
    }

    @Test
    void preservesNameAndIdLookupFallbacksAndDeletedFiltering() {
        assertThat(query.getAccountAutoId(ACCOUNT)).isEqualTo(accountAutoId.toString());
        assertThat(query.getAccountAutoId(RBAC_ONLY)).isEqualTo(RBAC_ONLY);
        assertThat(query.getAccountAutoId(DELETED)).isEqualTo(DELETED);
        assertThat(query.getAccountAutoId(null)).isNull();
        assertThat(query.getNameByAutoId(accountAutoId.toString())).isEqualTo("用户姓名");
        assertThat(query.getNameByAutoId(ACCOUNT)).isEqualTo("用户姓名");
        assertThat(query.getNameByAutoId(DELETED)).isEmpty();
        assertThat(query.getNameByAutoId(null)).isEmpty();
        assertThat(query.getNameByAccountId(ACCOUNT)).isEqualTo("用户姓名");
        assertThat(query.getNameByAccountId(DELETED)).isEmpty();
        assertThat(query.getNameByAccountId(RBAC_ONLY)).isEmpty();
    }

    @Test
    void preservesDepartmentAccountsAndRoleMetadata() {
        List<IcisAccountPB> accounts = query.getDeptAccounts(DEPT);
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().getId()).isEqualTo(accountAutoId);
        assertThat(accounts.getFirst().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(accounts.getFirst().getAccountName()).isEqualTo("用户姓名");
        assertThat(accounts.getFirst().getDepartment(0).getRoleId()).isEqualTo(PRIMARY_ROLE);
        assertThat(query.getDeptAccounts(DEPT + "-missing")).isEmpty();
    }

    @Test
    void preservesDirectInheritedAndMissingDepartmentPermissions() {
        authenticate(ACCOUNT);
        for (int permissionId : List.of(DIRECT_PERMISSION, INHERITED_PERMISSION)) {
            Pair<String, Boolean> permission = query.hasPermission(DEPT, permissionId);
            assertThat(permission.getFirst()).isEqualTo("查询角色" + PRIMARY_ROLE);
            assertThat(permission.getSecond()).isTrue();
        }
        assertThat(query.hasPermission(DEPT, -1).getSecond()).isFalse();
        Pair<String, Boolean> missing = query.hasPermission(DEPT + "-missing", DIRECT_PERMISSION);
        assertThat(missing.getFirst()).isEqualTo(Consts.VOID_ROLE);
        assertThat(missing.getSecond()).isFalse();
    }

    @Test
    void preservesAdministratorBypass() {
        authenticate(config.getUser().getAdminAccountId());
        Pair<String, Boolean> permission = query.hasPermission(DEPT + "-missing", -1);
        assertThat(permission.getFirst()).isEqualTo(Consts.ADMIN_ROLE);
        assertThat(permission.getSecond()).isTrue();
    }

    @Test
    void departmentAccessUsesCurrentLoginMembershipAndAdministratorBypass() {
        assertThat(query.canCurrentUserAccessDept(DEPT)).isFalse();
        authenticate(ACCOUNT);
        assertThat(query.canCurrentUserAccessDept(DEPT)).isTrue();
        assertThat(query.canCurrentUserAccessDept(DEPT + "-missing")).isFalse();
        authenticate(RBAC_ONLY);
        assertThat(query.canCurrentUserAccessDept(DEPT)).isFalse();
        authenticate(config.getUser().getAdminAccountId());
        assertThat(query.canCurrentUserAccessDept(DEPT + "-missing")).isTrue();
        assertThat(query.canCurrentUserAccessDept(null)).isFalse();
        assertThat(query.canCurrentUserAccessDept("  ")).isFalse();
    }

    @Test
    void departmentMembershipRejectsBlankInputAndRequiresAnActualMembership() {
        assertThat(query.accountBelongsToDept(ACCOUNT, DEPT)).isTrue();
        assertThat(query.accountBelongsToDept(RBAC_ONLY, DEPT)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, DEPT + "-missing")).isFalse();
        assertThat(query.accountBelongsToDept(null, DEPT)).isFalse();
        assertThat(query.accountBelongsToDept("  ", DEPT)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, null)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, "  ")).isFalse();
        assertThat(query.accountBelongsToDept(config.getUser().getAdminAccountId(), DEPT)).isFalse();
    }

    private Long saveAccount(String code, String name, boolean deleted) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO accounts(account_id, name, is_deleted, is_disabled)
            VALUES (?, ?, ?, 0) RETURNING id
            """, Long.class, code, name, deleted);
    }

    private void authenticate(String code) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(code, "", List.of()));
    }

    private static final String PREFIX = "user-query-tests-";
    private static final String ACCOUNT = PREFIX + "account";
    private static final String RBAC_ONLY = PREFIX + "rbac";
    private static final String DELETED = PREFIX + "deleted";
    private static final String NO_RBAC = PREFIX + "no-rbac";
    private static final String DEPT = PREFIX + "dept";
    private static final int PRIMARY_ROLE = 900001;
    private static final int CHILD_ROLE = 900002;
    private static final int INHERITED_PERMISSION = 900001;
    private static final int DIRECT_PERMISSION = 900002;

    private Long accountAutoId;

    @Autowired private UserQuery query;
    @Autowired private Config config;
    @Autowired private JdbcTemplate jdbcTemplate;
}
