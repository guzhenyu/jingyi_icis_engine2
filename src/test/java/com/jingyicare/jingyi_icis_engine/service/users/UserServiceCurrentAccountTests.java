package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

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
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DeleteAccountReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetUserInfoResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountDepartmentPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.AccountPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Transactional
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
        jdbcTemplate.update("""
            INSERT INTO rbac_permissions(id, name) VALUES (?, ?)
            ON CONFLICT (id) DO NOTHING
            """, Consts.PERM_ID_CONFIG_CHECKLIST, "配置组长质控");
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
    }

    @Test
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
        String permissionJson = permissionRequest(CALLER);
        assertThat(userService.addAccountPermission(permissionJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());

        assertThat(userService.addAccount(accountRequest(TARGET, "新增用户")).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        entityManager.flush();
        assertThat(modifiedBy(TARGET)).isEqualTo(callerAutoId.toString());

        assertThat(userService.revokeAccountPermission(permissionJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        entityManager.flush();
        String deniedTarget = PREFIX + "denied-target";
        assertThat(userService.addAccount(accountRequest(deniedTarget, "无权限用户"))
            .getRt().getCode()).isEqualTo(StatusCode.PERMISSION_DENIED.getNumber());
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM accounts WHERE account_id = ?", Long.class, deniedTarget))
            .isZero();
    }

    @Test
    void selfUpdateBypassesPermissionAndSelfDeletionStillUsesRbacCode() {
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
        assertThat(userService.updateAccount(ProtoUtils.protoToJson(updateReq)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
            "SELECT name FROM accounts WHERE id = ?", String.class, callerAutoId))
            .isEqualTo("本人修改后的姓名");

        assertThat(userService.addAccountPermission(permissionRequest(CALLER)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        DeleteAccountReq deleteReq = DeleteAccountReq.newBuilder()
            .setOpDeptId(DEPT).setAccountId(CALLER).build();
        assertThat(userService.deleteAccount(ProtoUtils.protoToJson(deleteReq)).getRt().getCode())
            .isEqualTo(StatusCode.CANNOT_DELETE_SELF_ACCOUNT.getNumber());
    }

    @Test
    void rbacOnlyCallerFallsBackToAccountCodeForAudit() {
        authenticate(RBAC_ONLY);
        assertThat(userService.addAccountPermission(permissionRequest(RBAC_ONLY)).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());

        String requestJson = accountRequest(TARGET, "回退审计用户");
        assertThat(userService.addAccount(requestJson).getRt().getCode())
            .isEqualTo(StatusCode.OK.getNumber());
        entityManager.flush();
        assertThat(modifiedBy(TARGET)).isEqualTo(RBAC_ONLY);
    }

    @Test
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
            .setAccount(AccountPB.newBuilder().setAccountId(accountId).setAccountName(name))
            .build();
        return ProtoUtils.protoToJson(req);
    }

    private String permissionRequest(String accountId) {
        AddAccountPermissionReq req = AddAccountPermissionReq.newBuilder()
            .setAccountId(accountId)
            .setDeptId(DEPT)
            .setPermissionId(Consts.PERM_ID_CONFIG_CHECKLIST)
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
    private static final int ROLE_ID = 910041;

    private Long callerAutoId;

    @Autowired private UserService userService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @PersistenceContext private EntityManager entityManager;
}
