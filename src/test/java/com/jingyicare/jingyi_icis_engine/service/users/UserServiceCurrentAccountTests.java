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
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddAccountPermissionReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddAccountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ChangePasswordReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteAccountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetUserInfoResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.ResetPasswordReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.auth.Encoder;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.RsaUtils;

class UserServiceCurrentAccountTests extends TestsBase {
    @BeforeEach
    void prepare() {
        SecurityContextHolder.clearContext();
        for (String accountId : List.of(CALLER, RBAC_ONLY)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)
                """, accountId, "认证姓名");
        }
        callerAutoId = saveBusinessAccount(CALLER, "用户姓名");
        saveBusinessAccount(NO_RBAC, "没有认证账号的用户");
        jdbcTemplate.update("""
            INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
            """, DEPT, "身份迁移测试科室");
        jdbcTemplate.update("""
            INSERT INTO rbac_roles(id, name, is_primary) VALUES (?, ?, true)
            """, ROLE_ID, "身份迁移测试角色");
        for (Integer permissionId : List.of(
            Consts.PERM_ID_CONFIG_ACCOUNT, Consts.PERM_ID_CONFIG_CHECKLIST)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_permissions(id, name) VALUES (?, ?)
                ON CONFLICT (id) DO NOTHING
                """, permissionId, "身份迁移测试权限");
        }
        for (String accountId : List.of(CALLER, RBAC_ONLY)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
                VALUES (?, ?, ?)
                """, accountId, DEPT, ROLE_ID);
        }
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
        for (String accountId : List.of(
            CALLER, RBAC_ONLY, NO_RBAC, TARGET, PREFIX + "denied-target")) {
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments_permissions WHERE account_id = ?",
                accountId);
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments_roles WHERE account_id = ?", accountId);
            jdbcTemplate.update(
                "DELETE FROM rbac_accounts_departments WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts_departments WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM accounts WHERE account_id = ?", accountId);
            jdbcTemplate.update("DELETE FROM rbac_accounts WHERE account_id = ?", accountId);
        }
        jdbcTemplate.update("DELETE FROM rbac_departments WHERE dept_id IN (?, ?, ?)",
            DEPT, OLD_DEPT, NEW_DEPT);
        jdbcTemplate.update("DELETE FROM rbac_roles WHERE id = ?", ROLE_ID);
        jdbcTemplate.update("""
            DELETE FROM app_transaction_locks WHERE resource_key IN (?, ?, ?, ?, ?, ?)
            """, LockKeys.account(TARGET), LockKeys.account(CALLER), LockKeys.department(DEPT),
            LockKeys.department(OLD_DEPT), LockKeys.department(NEW_DEPT),
            LockKeys.account(RBAC_ONLY));
    }

    @Test
    @Transactional
    void currentUserResponsesUseRbacCodeAndCurrentAccountName() {
        authenticate(CALLER);
        assertThat(userService.getUsername().getUsername()).isEqualTo("用户姓名");

        GetUserInfoResp response = userService.getUserInfo();
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(response.getUser().getId()).isEqualTo(CALLER);
        assertThat(response.getUser().getName()).isEqualTo("用户姓名");
    }

    @Test
    void usesRbacCodeForPermissionAndAutoIdForAuditThenHonorsRevocation() {
        authenticate(CALLER);
        assertThat(CALLER).isNotEqualTo(callerAutoId.toString());
        String permissionJson = permissionRequest(CALLER, Consts.PERM_ID_CONFIG_ACCOUNT);
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        assertThat(userService.addAccountPermission(permissionJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        authenticate(CALLER);

        assertThat(userService.addAccount(accountRequest(TARGET, "新增用户")).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(modifiedBy(TARGET)).isEqualTo(callerAutoId.toString());

        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        assertThat(userService.revokeAccountPermission(permissionJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        authenticate(CALLER);
        String deniedTarget = PREFIX + "denied-target";
        ReturnCode denied = userService.addAccount(accountRequest(deniedTarget, "无权限用户"))
            .getRt();
        assertThat(denied.getCode()).isEqualTo(StatusCode.PERMISSION_DENIED.getNumber());
        assertThat(denied.getMsg()).endsWith("deptId: " + DEPT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE account_id = ?", Long.class, deniedTarget))
            .isZero();
    }

    @Test
    @Transactional
    void emptyDepartmentListIsRejectedBeforeLocking() {
        authenticate(CALLER);
        AddAccountReq req = AddAccountReq.newBuilder().setOpDeptId(DEPT)
            .setAccount(AccountPB.newBuilder().setAccountId(TARGET).setAccountName("无科室用户"))
            .build();

        ReturnCode status = userService.addAccount(ProtoUtils.protoToJson(req)).getRt();

        assertThat(status.getCode()).isEqualTo(StatusCode.DEPT_IS_EMPTY.getNumber());
        assertThat(status.getMsg()).isEqualTo("部门为空");
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE account_id = ?", Long.class, TARGET)).isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM rbac_accounts WHERE account_id = ?", Long.class, TARGET))
            .isZero();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Long.class, LockKeys.account(TARGET))).isZero();
    }

    @Test
    void selfUpdateRequiresAccountPermissionAndSelfDeletionStillUsesRbacCode() {
        authenticate(CALLER);
        AddAccountReq updateReq = AddAccountReq.newBuilder()
            .setOpDeptId(DEPT)
            .setAccount(AccountPB.newBuilder()
                .setAccountId(CALLER)
                .setAccountName("本人修改后的姓名")
                .setDateOfBirthIso8601("1990-01-01T00:00:00Z")
                .addDepartment(AccountDepartmentPB.newBuilder()
                    .setDeptId(DEPT).setRoleId(ROLE_ID)))
            .build();
        ReturnCode denied = userService.updateAccount(ProtoUtils.protoToJson(updateReq)).getRt();
        assertThat(denied.getCode()).isEqualTo(StatusCode.PERMISSION_DENIED.getNumber());
        assertThat(denied.getMsg()).endsWith("deptId: " + DEPT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = ?", String.class, callerAutoId))
            .isEqualTo("用户姓名");

        grantAccountConfigPermission(DEPT);
        assertThat(userService.updateAccount(ProtoUtils.protoToJson(updateReq)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = ?", String.class, callerAutoId))
            .isEqualTo("本人修改后的姓名");

        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        assertThat(userService.addAccountPermission(
            permissionRequest(CALLER, Consts.PERM_ID_CONFIG_CHECKLIST)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        authenticate(CALLER);
        DeleteAccountReq deleteReq = DeleteAccountReq.newBuilder()
            .setOpDeptId(DEPT).setAccountId(CALLER).build();
        assertThat(userService.deleteAccount(ProtoUtils.protoToJson(deleteReq)).getRt().getCode())
            .isEqualTo(StatusCode.CANNOT_DELETE_SELF_ACCOUNT.getNumber());
    }

    @Test
    void accountUpdateRequiresPermissionForEveryExistingAndRequestedDepartment() {
        authenticate(CALLER);
        prepareAccountUpdate();
        grantAccountConfigPermission(DEPT);
        grantAccountConfigPermission(NEW_DEPT);
        String requestJson = updateRequest(List.of(DEPT, NEW_DEPT));

        assertUpdateDenied(requestJson, OLD_DEPT);

        grantAccountConfigPermission(OLD_DEPT);
        jdbcTemplate.update("""
            DELETE FROM rbac_accounts_departments_permissions
            WHERE account_id = ? AND dept_id = ? AND permission_id = ?
            """, CALLER, NEW_DEPT, Consts.PERM_ID_CONFIG_ACCOUNT);
        assertUpdateDenied(requestJson, NEW_DEPT);

        grantAccountConfigPermission(NEW_DEPT);
        assertThat(userService.updateAccount(requestJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(jdbcTemplate.queryForList("""
            SELECT dept_id FROM rbac_accounts_departments WHERE account_id = ?
            """, String.class, TARGET)).containsExactlyInAnyOrder(DEPT, NEW_DEPT);
        assertThat(jdbcTemplate.queryForObject("""
            SELECT deleted_by FROM accounts_departments
            WHERE account_id = ? AND dept_id = ? AND is_deleted = true
            """, String.class, TARGET, OLD_DEPT)).isEqualTo(callerAutoId.toString());
    }

    @Test
    void removingAllDepartmentsStillRequiresPermissionForTheExistingDepartments() {
        authenticate(CALLER);
        prepareAccountUpdate();
        grantAccountConfigPermission(DEPT);
        String requestJson = updateRequest(List.of());

        assertUpdateDenied(requestJson, OLD_DEPT);

        grantAccountConfigPermission(OLD_DEPT);
        assertThat(userService.updateAccount(requestJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM rbac_accounts_departments WHERE account_id = ?", Long.class,
            TARGET)).isZero();
    }

    @Test
    void rbacOnlyCallerFallsBackToAccountCodeForAudit() {
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        assertThat(userService.addAccountPermission(
            permissionRequest(RBAC_ONLY, Consts.PERM_ID_CONFIG_ACCOUNT)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        authenticate(RBAC_ONLY);

        String requestJson = accountRequest(TARGET, "回退审计用户");
        assertThat(userService.addAccount(requestJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(modifiedBy(TARGET)).isEqualTo(RBAC_ONLY);
    }

    @Test
    void passwordChangeUsesTheCurrentRbacAccountInsteadOfItsBusinessId() {
        authenticate(CALLER);
        jdbcTemplate.update("UPDATE rbac_accounts SET password_hash = ? WHERE account_id = ?",
            RsaUtils.encodePassword("initial-password", encoder.get()), CALLER);
        ChangePasswordReq req = ChangePasswordReq.newBuilder()
            .setOldPassword(RsaUtils.encodePassword("initial-password"))
            .setNewPassword(RsaUtils.encodePassword("changed-password")).build();

        assertThat(userService.changePassword(ProtoUtils.protoToJson(req)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(encoder.get().matches(RsaUtils.encodePassword("changed-password"),
            jdbcTemplate.queryForObject(
                "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class,
                CALLER))).isTrue();
    }

    @Test
    void departmentManagementPermissionDoesNotAuthorizePasswordResets() {
        authenticate(CALLER);
        prepareAccountUpdate();
        for (String deptId : List.of(DEPT, OLD_DEPT, NEW_DEPT)) {
            grantAccountConfigPermission(deptId);
        }
        jdbcTemplate.update(
            "DELETE FROM rbac_accounts_departments WHERE account_id = ?", RBAC_ONLY);

        for (String accountId : List.of(TARGET, UserQuery.ADMIN_ACCOUNT_ID, CALLER, RBAC_ONLY)) {
            String previousHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM rbac_accounts WHERE account_id = ?",
                String.class, accountId);
            ResetPasswordReq req = ResetPasswordReq.newBuilder()
                .setAccountId(accountId).setOpDeptId(DEPT).build();

            assertThat(userService.resetPassword(ProtoUtils.protoToJson(req)).getRt().getCode())
                .isEqualTo(StatusCode.NO_PERMISSION_TO_OPERATE.getNumber());
            assertThat(jdbcTemplate.queryForObject(
                "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class,
                accountId)).isEqualTo(previousHash);
        }
    }

    @Test
    void administratorCanResetAUserWithSeveralDepartments() {
        authenticate(UserQuery.ADMIN_ACCOUNT_ID);
        prepareAccountUpdate();
        ResetPasswordReq req = ResetPasswordReq.newBuilder().setAccountId(TARGET).build();

        assertThat(userService.resetPassword(ProtoUtils.protoToJson(req)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        assertThat(encoder.get().matches(RsaUtils.encodePassword(TARGET),
            jdbcTemplate.queryForObject(
                "SELECT password_hash FROM rbac_accounts WHERE account_id = ?", String.class,
                TARGET))).isTrue();
    }

    @Test
    @Transactional
    void allIdentityBasedWritesReportCurrentAccountNotFoundForMissingIdentity() {
        assertIdentityFailure();
        for (ReturnCode status : List.of(
            userService.changePassword("{}").getRt(), userService.resetPassword("{}").getRt()
        )) {
            assertThat(status.getCode()).isEqualTo(StatusCode.ACCOUNT_NOT_FOUND.getNumber());
            assertThat(status.getMsg()).isEqualTo("未找到当前用户信息");
        }
        authenticate(NO_RBAC);
        assertIdentityFailure();
    }

    private void assertIdentityFailure() {
        int expectedCode = StatusCode.ACCOUNT_NOT_FOUND.getNumber();
        ReturnCode addDeptStatus = userService.addDept("{}").getRt();
        assertThat(addDeptStatus.getCode()).isEqualTo(expectedCode);
        assertThat(addDeptStatus.getMsg()).isEqualTo("未找到当前用户信息");
        assertThat(userService.updateDept("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.deleteDept("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.addAccount("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.updateAccount("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.deleteAccount("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.addRolePermission("{}").getRt().getCode()).isEqualTo(expectedCode);
        assertThat(userService.revokeRolePermission("{}").getRt().getCode())
            .isEqualTo(expectedCode);
        assertThat(userService.addAccountPermission("{}").getRt().getCode())
            .isEqualTo(expectedCode);
        assertThat(userService.revokeAccountPermission("{}").getRt().getCode())
            .isEqualTo(expectedCode);
    }

    private void prepareAccountUpdate() {
        for (String deptId : List.of(OLD_DEPT, NEW_DEPT)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_departments(dept_id, dept_name) VALUES (?, ?)
                """, deptId, deptId);
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
                VALUES (?, ?, ?)
                """, CALLER, deptId, ROLE_ID);
        }
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts(account_id, account_name) VALUES (?, ?)
            """, TARGET, "待修改用户");
        Long targetAutoId = saveBusinessAccount(TARGET, "待修改用户");
        for (String deptId : List.of(DEPT, OLD_DEPT)) {
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments(account_id, dept_id, primary_role_id)
                VALUES (?, ?, ?)
                """, TARGET, deptId, ROLE_ID);
            jdbcTemplate.update("""
                INSERT INTO rbac_accounts_departments_roles(account_id, dept_id, role_id)
                VALUES (?, ?, ?)
                """, TARGET, deptId, ROLE_ID);
            jdbcTemplate.update("""
                INSERT INTO accounts_departments(
                    employee_id, account_id, dept_id, primary_role_id, start_date, is_deleted)
                VALUES (?, ?, ?, ?, now(), false)
                """, targetAutoId, TARGET, deptId, ROLE_ID);
        }
    }

    private void grantAccountConfigPermission(String deptId) {
        jdbcTemplate.update("""
            INSERT INTO rbac_accounts_departments_permissions(account_id, dept_id, permission_id)
            VALUES (?, ?, ?)
            """, CALLER, deptId, Consts.PERM_ID_CONFIG_ACCOUNT);
    }

    private String updateRequest(List<String> deptIds) {
        AccountPB.Builder account = AccountPB.newBuilder().setAccountId(TARGET)
            .setAccountName("修改后用户").setDateOfBirthIso8601("1990-01-01T00:00:00Z");
        for (String deptId : deptIds) {
            account.addDepartment(AccountDepartmentPB.newBuilder().setDeptId(deptId)
                .setRoleId(ROLE_ID));
        }
        return ProtoUtils.protoToJson(AddAccountReq.newBuilder()
            .setOpDeptId(DEPT).setAccount(account).build());
    }

    private void assertUpdateDenied(String requestJson, String deniedDeptId) {
        ReturnCode status = userService.updateAccount(requestJson).getRt();
        assertThat(status.getCode()).isEqualTo(StatusCode.PERMISSION_DENIED.getNumber());
        assertThat(status.getMsg()).endsWith("deptId: " + deniedDeptId);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE account_id = ?", String.class, TARGET))
            .isEqualTo("待修改用户");
        assertThat(jdbcTemplate.queryForList("""
            SELECT dept_id FROM rbac_accounts_departments WHERE account_id = ?
            """, String.class, TARGET)).containsExactlyInAnyOrder(DEPT, OLD_DEPT);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM app_transaction_locks WHERE resource_key = ?",
            Long.class, LockKeys.account(TARGET))).isZero();
    }

    private Long saveBusinessAccount(String accountId, String name) {
        return jdbcTemplate.queryForObject("""
            INSERT INTO accounts(account_id, name, is_deleted, is_disabled)
            VALUES (?, ?, false, 0) RETURNING id
            """, Long.class, accountId, name);
    }

    private String modifiedBy(String accountId) {
        return jdbcTemplate.queryForObject(
            "SELECT modified_by FROM accounts WHERE account_id = ? AND is_deleted = false",
            String.class, accountId);
    }

    private String accountRequest(String accountId, String name) {
        AddAccountReq req = AddAccountReq.newBuilder()
            .setOpDeptId(DEPT)
            .setAccount(AccountPB.newBuilder().setAccountId(accountId).setAccountName(name)
                .addDepartment(AccountDepartmentPB.newBuilder()
                    .setDeptId(DEPT).setRoleId(ROLE_ID)))
            .build();
        return ProtoUtils.protoToJson(req);
    }

    private String permissionRequest(String accountId, int permissionId) {
        AddAccountPermissionReq req = AddAccountPermissionReq.newBuilder()
            .setAccountId(accountId)
            .setDeptId(DEPT)
            .setPermissionId(permissionId)
            .build();
        return ProtoUtils.protoToJson(req);
    }

    private void authenticate(String accountId) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(accountId, "", List.of()));
    }

    private static final String PREFIX = "user-service-current-account-";
    private static final String CALLER = PREFIX + "caller";
    private static final String RBAC_ONLY = PREFIX + "rbac-only";
    private static final String NO_RBAC = PREFIX + "no-rbac";
    private static final String TARGET = PREFIX + "target";
    private static final String DEPT = PREFIX + "dept";
    private static final String OLD_DEPT = PREFIX + "old-dept";
    private static final String NEW_DEPT = PREFIX + "new-dept";
    private static final int ROLE_ID = 910041;

    private Long callerAutoId;

    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Encoder encoder;
}
