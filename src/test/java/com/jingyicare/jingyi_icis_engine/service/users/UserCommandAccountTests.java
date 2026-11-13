package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.service.users.auth.Encoder;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.RsaUtils;

@Transactional
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

    @Test
    void migratedAccountCreatesDepartmentMembershipAndSystemAudit() {
        AccountPB account = account(NEW_ACCOUNT, DEPT);

        assertThat(commands.addAccount(account, UserQuery.SYSTEM_ACCOUNT_ID))
            .isEqualTo(StatusCode.OK);
        entityManager.flush();
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

    @Test
    void administratorCanResetPasswordWithoutAGlobalRoleBinding() {
        jdbcTemplate.update("DELETE FROM rbac_accounts_roles WHERE account_id = ?",
            UserQuery.ADMIN_ACCOUNT_ID);

        assertThat(commands.resetPassword(TARGET, UserQuery.ADMIN_ACCOUNT_ID))
            .isEqualTo(StatusCode.OK);
        entityManager.flush();
        String passwordHash = jdbcTemplate.queryForObject(
            "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class, TARGET);
        assertThat(encoder.get().matches(RsaUtils.encodePassword(TARGET), passwordHash)).isTrue();
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

    private AccountPB account(String accountId, String deptId) {
        return AccountPB.newBuilder().setAccountId(accountId).setAccountName("新增迁移用户")
            .addDepartment(AccountDepartmentPB.newBuilder().setDeptId(deptId).setRoleId(ROLE_ID))
            .build();
    }

    private static final String PREFIX = "user-cmd-account-tests-";
    private static final String CALLER = PREFIX + "caller";
    private static final String TARGET = PREFIX + "target";
    private static final String NEW_ACCOUNT = PREFIX + "new";
    private static final String DEPT = PREFIX + "dept";
    private static final String ORIGINAL_HASH = "unchanged-test-hash";
    private static final int ROLE_ID = 920031;

    @Autowired private UserCommand commands;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Config config;
    @Autowired private Encoder encoder;
    @PersistenceContext private EntityManager entityManager;
}
