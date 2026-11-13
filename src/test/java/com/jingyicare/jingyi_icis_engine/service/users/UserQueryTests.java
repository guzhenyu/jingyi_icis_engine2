package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
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
    void currentAccountRejectsInvalidAuthenticationWithoutQuerying() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long previousStatements = statistics.getPrepareStatementCount();
            assertThat(query.getCurrentAccount()).isNull();
            for (String code : List.of("anonymousUser", "", "  ")) {
                authenticate(code);
                assertThat(query.getCurrentAccount()).isNull();
            }
            SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ACCOUNT, ""));
            assertThat(query.getCurrentAccount()).isNull();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(previousStatements);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
    }

    @Test
    void currentAccountReadsAllFieldsAndFallbacksWithOneProjectionQuery() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean statisticsEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            List<CurrentAccount> expectedAccounts = List.of(
                new CurrentAccount(ACCOUNT, accountAutoId.toString(), "用户姓名"),
                new CurrentAccount(RBAC_ONLY, RBAC_ONLY, "RBAC姓名"),
                new CurrentAccount(DELETED, DELETED, "RBAC姓名")
            );
            for (CurrentAccount expected : expectedAccounts) {
                authenticate(expected.rbacAcctId());
                assertCurrentAccountProjection(statistics, expected);
            }
            authenticate(NO_RBAC);
            assertCurrentAccountProjection(statistics, null);
        } finally {
            statistics.setStatisticsEnabled(statisticsEnabled);
        }
    }

    @Test
    void currentAccountIgnoresDeletedHistoryForTheSameAccountCode() {
        saveAccount(ACCOUNT, "历史姓名一", true);
        saveAccount(ACCOUNT, "历史姓名二", true);
        authenticate(ACCOUNT);

        assertThat(query.getCurrentAccount()).isEqualTo(
            new CurrentAccount(ACCOUNT, accountAutoId.toString(), "用户姓名"));
    }

    @Test
    void currentAccountPreservesEmptyBusinessNameAndMissingRbacName() {
        jdbcTemplate.update("UPDATE accounts SET name = '' WHERE id = ?", accountAutoId);
        authenticate(ACCOUNT);
        assertThat(query.getCurrentAccount()).isEqualTo(
            new CurrentAccount(ACCOUNT, accountAutoId.toString(), ""));

        jdbcTemplate.update(
            "UPDATE rbac_accounts SET account_name = NULL WHERE account_id = ?", RBAC_ONLY);
        authenticate(RBAC_ONLY);
        assertThat(query.getCurrentAccount()).isEqualTo(
            new CurrentAccount(RBAC_ONLY, RBAC_ONLY, null));

        jdbcTemplate.update(
            "UPDATE rbac_accounts SET account_name = '' WHERE account_id = ?", RBAC_ONLY);
        assertThat(query.getCurrentAccount()).isEqualTo(
            new CurrentAccount(RBAC_ONLY, RBAC_ONLY, ""));
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
        List<AccountPB> accounts = query.getDeptAccounts(DEPT);
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().getId()).isEqualTo(accountAutoId);
        assertThat(accounts.getFirst().getAccountId()).isEqualTo(ACCOUNT);
        assertThat(accounts.getFirst().getAccountName()).isEqualTo("用户姓名");
        assertThat(accounts.getFirst().getDepartment(0).getRoleId()).isEqualTo(PRIMARY_ROLE);
        assertThat(query.getDeptAccounts(DEPT + "-missing")).isEmpty();
    }

    @Test
    void preservesDirectInheritedAndMissingDepartmentPermissions() {
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        for (int permissionId : List.of(DIRECT_PERMISSION, INHERITED_PERMISSION)) {
            Pair<String, Boolean> permission = query.hasPermission(ACCOUNT, DEPT, permissionId);
            assertThat(permission.getFirst()).isEqualTo("查询角色" + PRIMARY_ROLE);
            assertThat(permission.getSecond()).isTrue();
        }
        assertThat(query.hasPermission(ACCOUNT, DEPT, -1).getSecond()).isFalse();
        Pair<String, Boolean> missing = query.hasPermission(
            ACCOUNT, DEPT + "-missing", DIRECT_PERMISSION);
        assertThat(missing.getFirst()).isEqualTo(Consts.VOID_ROLE);
        assertThat(missing.getSecond()).isFalse();
    }

    @Test
    void preservesAdministratorBypass() {
        authenticate(ACCOUNT);
        Pair<String, Boolean> permission = query.hasPermission(
            UserQuery.ADMIN_ACCOUNT_ID, DEPT + "-missing", -1);
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
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        assertThat(query.canCurrentUserAccessDept(DEPT + "-missing")).isTrue();
        assertThat(query.canCurrentUserAccessDept(null)).isFalse();
        assertThat(query.canCurrentUserAccessDept("  ")).isFalse();
    }

    @Test
    void accountConfigurationUsesTheSuppliedAccountAndDirectPermission() {
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        Pair<Boolean, String> denied = query.canConfigAccount(ACCOUNT, List.of(DEPT));
        assertThat(denied.getFirst()).isFalse();
        assertThat(denied.getSecond()).isEqualTo(DEPT);

        grantAccountConfigPermission();

        Pair<Boolean, String> allowed = query.canConfigAccount(ACCOUNT, List.of(DEPT));
        assertThat(allowed.getFirst()).isTrue();
        assertThat(allowed.getSecond()).isEmpty();
    }

    @Test
    void accountConfigurationIncludesInheritedRolePermission() {
        jdbcTemplate.update("""
            INSERT INTO rbac_roles_permissions(role_id, permission_id) VALUES (?, ?)
            """, CHILD_ROLE, Consts.PERM_ID_CONFIG_ACCOUNT);

        Pair<Boolean, String> result = query.canConfigAccount(ACCOUNT, List.of(DEPT));

        assertThat(result.getFirst()).isTrue();
        assertThat(result.getSecond()).isEmpty();
    }

    @Test
    void accountConfigurationReturnsTheFirstDeniedDepartmentInInputOrder() {
        grantAccountConfigPermission();
        String first = DEPT + "-first-missing";
        String second = DEPT + "-second-missing";

        Pair<Boolean, String> result = query.canConfigAccount(
            ACCOUNT, List.of(DEPT, first, second));
        assertThat(result.getFirst()).isFalse();
        assertThat(result.getSecond()).isEqualTo(first);
        assertThat(query.canConfigAccount(ACCOUNT, List.of(DEPT, second, first)).getSecond())
            .isEqualTo(second);
    }

    @Test
    void accountConfigurationPreservesAdministratorBypass() {
        authenticate(ACCOUNT);

        Pair<Boolean, String> result = query.canConfigAccount(
            UserQuery.ADMIN_ACCOUNT_ID, List.of(DEPT, DEPT + "-missing"));

        assertThat(result.getFirst()).isTrue();
        assertThat(result.getSecond()).isEmpty();
    }

    @Test
    void accountConfigurationHasNoDepartmentPermissionsToCheckForAnEmptyList() {
        Pair<Boolean, String> result = query.canConfigAccount(ACCOUNT, List.of());

        assertThat(result.getFirst()).isTrue();
        assertThat(result.getSecond()).isEmpty();
    }

    private void grantAccountConfigPermission() {
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_permissions(account_id, dept_id, permission_id)
            VALUES (?, ?, ?)
            """, ACCOUNT, DEPT, Consts.PERM_ID_CONFIG_ACCOUNT);
    }

    @Test
    void departmentMembershipRejectsBlankInputAndRequiresAnActualMembership() {
        assertThat(query.getDeptIdsByAccountId(ACCOUNT)).containsExactly(DEPT);
        assertThat(query.getDeptIdsByAccountId(RBAC_ONLY)).isEmpty();
        assertThat(query.accountBelongsToDept(ACCOUNT, DEPT)).isTrue();
        assertThat(query.accountBelongsToDept(RBAC_ONLY, DEPT)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, DEPT + "-missing")).isFalse();
        assertThat(query.accountBelongsToDept(null, DEPT)).isFalse();
        assertThat(query.accountBelongsToDept("  ", DEPT)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, null)).isFalse();
        assertThat(query.accountBelongsToDept(ACCOUNT, "  ")).isFalse();
        assertThat(query.accountBelongsToDept(UserQuery.ADMIN_ACCOUNT_ID, DEPT)).isFalse();
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

    private void assertCurrentAccountProjection(Statistics statistics, CurrentAccount expected) {
        long previousStatements = statistics.getPrepareStatementCount();
        long previousEntityLoads = statistics.getEntityLoadCount();
        assertThat(query.getCurrentAccount()).isEqualTo(expected);
        assertThat(statistics.getPrepareStatementCount() - previousStatements).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isEqualTo(previousEntityLoads);
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
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbcTemplate;
}
