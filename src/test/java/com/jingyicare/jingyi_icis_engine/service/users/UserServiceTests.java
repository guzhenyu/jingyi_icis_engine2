package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class UserServiceTests extends TestsBase {
    public UserServiceTests(
        @Autowired UserService userService,
        @Autowired RbacDepartmentAccountRoleRepository deptAccountRoleRepo
    ) {
        this.accountId1 = "admin";
        this.accountId2 = "test-account-001";
        this.accountId3 = "test-account-002";
        this.accountId4 = "test-account-003";
        this.accountId5 = "test-account-004";
        this.accountId6 = "test-account-005";
        this.deptId1 = "10019";
        this.deptId2 = "10020";
        this.deptId3 = "10021";
        this.deptId4 = "10022";
        this.deptId5 = "10023";
        this.deptId6 = "10024";
        this.deptId7 = "10026";
        this.DIRECTOR_ROLE_ID = 2;
        this.HEAD_NURSE_ROLE_ID = 3;
        this.NURSE_ROLE_ID = 5;
        this.userService = userService;
        this.deptAccountRoleRepo = deptAccountRoleRepo;
    }

    @Test
    public void testDeptSettings() {
        GetAllDeptNamesReq getAllDeptNamesReq = GetAllDeptNamesReq.newBuilder().build();
        String getAllDeptNamesReqJson = ProtoUtils.protoToJson(getAllDeptNamesReq);
        GetAllDeptNamesResp resp = userService.getAllDeptNames(getAllDeptNamesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getDeptList().get(0).getName()).isEqualTo("信息科");
    }

    @Test
    public void testDepartmentOperations() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询科室
        GetAllDeptsReq getAllDeptsReq = GetAllDeptsReq.newBuilder().build();
        String getAllDeptsReqJson = ProtoUtils.protoToJson(getAllDeptsReq);
        GetAllDeptsResp getAllDeptsResp = userService.getAllDepts(getAllDeptsReqJson);
        assertThat(getAllDeptsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllDeptsResp.getDepartmentList().stream()
            .filter(dept -> dept.getDeptId().equals(deptId1))
            .count()
        ).isEqualTo(0);

        // 新增科室
        AddDeptReq addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId1)
            .setDeptName("dept-test-dept-ops-1")
            .setAbbreviation("dept_tdo_1")
            .setWardCode("ward_code_1")
            .setWardName("ward_name_1")
            .setHospitalName("hospital_1")
            .build()
        ).build();
        String addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        AddDeptResp addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer deptIntegerId = addDeptResp.getDeptId();

        // 更新科室
        AddDeptReq updateDeptReq = AddDeptReq.newBuilder().setDepartment(
            addDeptReq.getDepartment().toBuilder()
                .setId(deptIntegerId)
                .setDeptId(deptId1)
                .setAbbreviation("dept_tdo_1_updated")
                .build()
        ).build();
        String updateDeptReqJson = ProtoUtils.protoToJson(updateDeptReq);
        GenericResp updateDeptResp = userService.updateDept(updateDeptReqJson);
        assertThat(updateDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询科室
        getAllDeptsResp = userService.getAllDepts(getAllDeptsReqJson);
        assertThat(getAllDeptsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        List<IcisDepartmentPB> depts = getAllDeptsResp.getDepartmentList()
            .stream()
            .filter(dept -> dept.getDeptId().equals(deptId1))
            .toList();
        assertThat(depts.size()).isEqualTo(1);
        assertThat(depts.get(0).getAbbreviation()).isEqualTo("dept_tdo_1_updated");


        // 查询科室名称
        GetAllDeptNamesReq getAllDeptNamesReq = GetAllDeptNamesReq.newBuilder().build();
        String getAllDeptNamesReqJson = ProtoUtils.protoToJson(getAllDeptNamesReq);
        GetAllDeptNamesResp getAllDeptNamesResp = userService.getAllDeptNames(getAllDeptNamesReqJson);
        assertThat(getAllDeptNamesResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllDeptNamesResp.getDeptList().stream()
            .filter(dept -> dept.getName().equals("dept-test-dept-ops-1"))
            .count()
        ).isEqualTo(1);

        // 删除科室
        DeleteDeptReq deleteDeptReq = DeleteDeptReq.newBuilder().setDeptId(deptId1).build();
        String deleteDeptReqJson = ProtoUtils.protoToJson(deleteDeptReq);
        GenericResp deleteDeptResp = userService.deleteDept(deleteDeptReqJson);
        assertThat(deleteDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询科室
        getAllDeptsResp = userService.getAllDepts(getAllDeptsReqJson);
        assertThat(getAllDeptsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllDeptsResp.getDepartmentList().stream()
            .filter(dept -> dept.getDeptId().equals(deptId1))
            .count()
        ).isEqualTo(0);
    }

    @Test
    public void testDeleteDepartmentWithAccount() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门deptId4(10022)
        AddDeptReq addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId4)
            .setDeptName("test-dept-10022")
            .setAbbreviation("td-10022")
            .build()
        ).build();
        String addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        AddDeptResp addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增账号
        AddAccountReq addAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId4)
            .setAccountName("test-account-004")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId4)
                    .setRoleId(HEAD_NURSE_ROLE_ID)
                    .build()
            )).build();
        String addAccountReqJson = ProtoUtils.protoToJson(addAccountReq);
        GenericResp addAccountResp = userService.addAccount(addAccountReqJson);
        assertThat(addAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 删除科室失败（员工再次登录将看不到这个科室）
        DeleteDeptReq deleteDeptReq = DeleteDeptReq.newBuilder().setDeptId(deptId4).build();
        String deleteDeptReqJson = ProtoUtils.protoToJson(deleteDeptReq);
        GenericResp deleteDeptResp = userService.deleteDept(deleteDeptReqJson);
        assertThat(deleteDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    @Test
    public void testAccountOperations() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门deptId2(10020)， deptId3(10021)
        AddDeptReq addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId2)
            .setDeptName("test-dept-10020")
            .setAbbreviation("td-10020")
            .build()
        ).build();
        String addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        AddDeptResp addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId3)
            .setDeptName("test-dept-10021")
            .setAbbreviation("td-10021")
            .build()
        ).build();
        addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());


        // 新增账号
        AddAccountReq addAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId2)
            .setAccountName("test-account-001")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId2)
                    .setRoleId(HEAD_NURSE_ROLE_ID)
                    .build()
            )
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId3)
                    .setRoleId(NURSE_ROLE_ID)
                    .build()
            )).build();
        String addAccountReqJson = ProtoUtils.protoToJson(addAccountReq);
        GenericResp addAccountResp = userService.addAccount(addAccountReqJson);
        assertThat(addAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        addAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId3)
            .setAccountName("test-account-002")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId2)
                    .setRoleId(HEAD_NURSE_ROLE_ID)
                    .build()
            )).build();
        addAccountReqJson = ProtoUtils.protoToJson(addAccountReq);
        addAccountResp = userService.addAccount(addAccountReqJson);
        assertThat(addAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询账号 - 根据deptId2查
        GetAllAccountsReq getAllAccountsReq = GetAllAccountsReq.newBuilder().setDeptId(deptId2).build();
        String getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        GetAllAccountsResp getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(2);
        assertThat(getAllAccountsResp.getAccount(0).getDepartmentList()).hasSize(2);
        assertThat(getAllAccountsResp.getAccount(1).getDepartmentList()).hasSize(1);

        // 查帐号 - 根据deptId3查
        getAllAccountsReq = GetAllAccountsReq.newBuilder().setDeptId(deptId3).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);

        // 查帐号 - 根据accountId查
        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId2).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);

        // 查账号 - 根据账号名"test"查
        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountName("test").build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        List<IcisAccountPB> accounts = getAllAccountsResp.getAccountList();
        assertThat(accounts.stream().filter(account -> account.getAccountId().equals(accountId2)).count()).isEqualTo(1);
        assertThat(accounts.stream().filter(account -> account.getAccountId().equals(accountId3)).count()).isEqualTo(1);

        // 查账号 - 根据账号名"001"查
        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountName("001").build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);

        // 删除账号002的部门
        DeleteAccountReq deleteAccountReq = DeleteAccountReq.newBuilder()
            .setAccountId(accountId3)
            .setDeptId(deptId2)
            .build();
        String deleteAccountReqJson = ProtoUtils.protoToJson(deleteAccountReq);
        GenericResp deleteAccountResp = userService.deleteAccount(deleteAccountReqJson);
        assertThat(deleteAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId3).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartmentList()).hasSize(0);

        // 删除账号002
        deleteAccountReq = DeleteAccountReq.newBuilder().setAccountId(accountId3).build();
        deleteAccountReqJson = ProtoUtils.protoToJson(deleteAccountReq);
        deleteAccountResp = userService.deleteAccount(deleteAccountReqJson);
        assertThat(deleteAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId3).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(0);
    }

    @Test
    public void testUpdateAccount() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门deptId2(10020)， deptId3(10021)
        AddDeptReq addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId5)
            .setDeptName("test-dept-10023")
            .setAbbreviation("td-10023")
            .build()
        ).build();
        String addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        AddDeptResp addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId6)
            .setDeptName("test-dept-10024")
            .setAbbreviation("td-10024")
            .build()
        ).build();
        addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增账号
        AddAccountReq addAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId5)
            .setAccountName("test-account-004")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId5)
                    .setRoleId(HEAD_NURSE_ROLE_ID)
                    .build()
            )
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId6)
                    .setRoleId(NURSE_ROLE_ID)
                    .build()
            )).build();
        String addAccountReqJson = ProtoUtils.protoToJson(addAccountReq);
        GenericResp addAccountResp = userService.addAccount(addAccountReqJson);
        assertThat(addAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        GetAllAccountsReq getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId5).build();
        String getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        GetAllAccountsResp getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartmentList()).hasSize(2);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getDeptId()).isEqualTo(deptId5);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getRoleId()).isEqualTo(HEAD_NURSE_ROLE_ID);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(1).getDeptId()).isEqualTo(deptId6);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(1).getRoleId()).isEqualTo(NURSE_ROLE_ID);

        // 更新账号(删除deptId6，改deptId5的角色)
        AddAccountReq updateAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId5)
            .setAccountName("test-account-004")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId5)
                    .setRoleId(NURSE_ROLE_ID)
                    .build()
            )).build();
        String updateAccountReqJson = ProtoUtils.protoToJson(updateAccountReq);
        GenericResp updateAccountResp = userService.updateAccount(updateAccountReqJson);
        assertThat(updateAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId5).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartmentList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getDeptId()).isEqualTo(deptId5);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getRoleId()).isEqualTo(NURSE_ROLE_ID);

        // 更新账号(新增deptId6)
        updateAccountReq = AddAccountReq.newBuilder().setAccount(IcisAccountPB.newBuilder()
            .setAccountId(accountId5)
            .setAccountName("test-account-004")
            .setGender(1)
            .addDepartment(
                IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId6)
                    .setRoleId(DIRECTOR_ROLE_ID)
                    .build()
            )).build();
        updateAccountReqJson = ProtoUtils.protoToJson(updateAccountReq);
        updateAccountResp = userService.updateAccount(updateAccountReqJson);
        assertThat(updateAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getAllAccountsReq = GetAllAccountsReq.newBuilder().setAccountId(accountId5).build();
        getAllAccountsReqJson = ProtoUtils.protoToJson(getAllAccountsReq);
        getAllAccountsResp = userService.getAllAccounts(getAllAccountsReqJson);
        assertThat(getAllAccountsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getAllAccountsResp.getAccountList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartmentList()).hasSize(1);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getDeptId()).isEqualTo(deptId6);
        assertThat(getAllAccountsResp.getAccount(0).getDepartment(0).getRoleId()).isEqualTo(DIRECTOR_ROLE_ID);
    }

    @Test
    public void testChangePassword() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        ChangePasswordReq changePasswordReq = ChangePasswordReq.newBuilder()
            .setOldPassword("wrong-password")
            .setNewPassword("admin123")
            .build();
        String changePasswordReqJson = ProtoUtils.protoToJson(changePasswordReq);
        GenericResp changePasswordResp = userService.changePassword(changePasswordReqJson);
        assertThat(changePasswordResp.getRt().getCode()).isEqualTo(StatusCode.WRONG_PASSWORD.ordinal());

        changePasswordReq = ChangePasswordReq.newBuilder()
            .setOldPassword(RsaUtils.encodePassword("admin"))
            .setNewPassword("admin123")
            .build();
        changePasswordReqJson = ProtoUtils.protoToJson(changePasswordReq);
        changePasswordResp = userService.changePassword(changePasswordReqJson);
        assertThat(changePasswordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        ResetPasswordReq resetPasswordReq = ResetPasswordReq.newBuilder()
            .setAccountId(accountId1)
            .build();
        String resetPasswordReqJson = ProtoUtils.protoToJson(resetPasswordReq);
        GenericResp resetPasswordResp = userService.resetPassword(resetPasswordReqJson);
        assertThat(resetPasswordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        changePasswordReq = ChangePasswordReq.newBuilder()
            .setOldPassword(RsaUtils.encodePassword("admin"))
            .setNewPassword("admin123")
            .build();
        changePasswordReqJson = ProtoUtils.protoToJson(changePasswordReq);
        changePasswordResp = userService.changePassword(changePasswordReqJson);
        assertThat(changePasswordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    @Test
    public void testPermissionRoles() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId1, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门
        AddDeptReq addDeptReq = AddDeptReq.newBuilder().setDepartment(IcisDepartmentPB.newBuilder()
            .setDeptId(deptId7)
            .setDeptName("test-dept-10026")
            .setAbbreviation("td-10026")
            .build()
        ).build();
        String addDeptReqJson = ProtoUtils.protoToJson(addDeptReq);
        AddDeptResp addDeptResp = userService.addDept(addDeptReqJson);
        assertThat(addDeptResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增账号
        AddAccountReq addAccountReq = AddAccountReq.newBuilder().setAccount(
            IcisAccountPB.newBuilder()
                .setAccountId(accountId6)
                .setAccountName("test-account-005")
                .setGender(1)
                .addDepartment(
                    IcisAccountDepartmentPB.newBuilder()
                        .setDeptId(deptId7)
                        .setRoleId(HEAD_NURSE_ROLE_ID)
                        .build()
                )
                .build()
            )
            .build();
        String addAccountReqJson = ProtoUtils.protoToJson(addAccountReq);
        GenericResp addAccountResp = userService.addAccount(addAccountReqJson);
        assertThat(addAccountResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取主角色
        GetRolesReq getRolesReq = GetRolesReq.newBuilder().build();
        String getRolesReqJson = ProtoUtils.protoToJson(getRolesReq);
        GetRolesResp getRolesResp = userService.getRoles(getRolesReqJson);
        assertThat(getRolesResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取权限分组(非主角色，"菜单权限组")
        GetPermissionsReq getPermissionsReq = GetPermissionsReq.newBuilder()
            .setDeptId(deptId7).build();
        String getPermissionsReqJson = ProtoUtils.protoToJson(getPermissionsReq);
        GetPermissionsResp getPermissionsResp = userService.getPermissions(getPermissionsReqJson);
        assertThat(getPermissionsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPermissionsResp.getPermissionGroupList()).hasSize(1);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermissionList()).hasSize(6);
        /* 管理员, 主任, 护士长, 护理组长 */
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getRoleList()).hasSize(4);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getAccountIdList()).hasSize(0);

        // // 为权限（5: 管理菜单）添加角色（5：护士）
        AddRolePermissionReq addRolePermissionReq = AddRolePermissionReq.newBuilder()
            .setRoleId(NURSE_ROLE_ID)
            .setPermissionId(5)
            .build();
        String addRolePermissionReqJson = ProtoUtils.protoToJson(addRolePermissionReq);
        GenericResp addRolePermissionResp = userService.addRolePermission(addRolePermissionReqJson);
        assertThat(addRolePermissionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPermissionsResp = userService.getPermissions(getPermissionsReqJson);
        assertThat(getPermissionsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPermissionsResp.getPermissionGroupList()).hasSize(1);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermissionList()).hasSize(6);
        /* 管理员, 主任, 护士长, 护理组长, 护士 */
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getRoleList()).hasSize(5);

        // // 为权限（5: 管理菜单）回收角色（5：护士）
        GenericResp revokeRolePermissionResp = userService.revokeRolePermission(addRolePermissionReqJson);
        assertThat(revokeRolePermissionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPermissionsResp = userService.getPermissions(getPermissionsReqJson);
        assertThat(getPermissionsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPermissionsResp.getPermissionGroupList()).hasSize(1);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermissionList()).hasSize(6);
        /* 管理员, 主任, 护士长, 护理组长 */
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getRoleList()).hasSize(4);

        // 为权限（5: 管理菜单）添加用户accountId6
        AddAccountPermissionReq addAccountPermissionReq = AddAccountPermissionReq.newBuilder()
            .setAccountId(accountId6)
            .setDeptId(deptId7)
            .setPermissionId(5)
            .build();
        String addAccountPermissionReqJson = ProtoUtils.protoToJson(addAccountPermissionReq);
        GenericResp addAccountPermissionResp = userService.addAccountPermission(addAccountPermissionReqJson);
        assertThat(addAccountPermissionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPermissionsResp = userService.getPermissions(getPermissionsReqJson);
        assertThat(getPermissionsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPermissionsResp.getPermissionGroupList()).hasSize(1);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermissionList()).hasSize(6);
        /* 管理员, 主任, 护士长, 护理组长 */
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getRoleList()).hasSize(4);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getAccountIdList()).hasSize(1);

        // 为权限（5: 管理菜单）回收用户（admin）
        GenericResp revokeAccountPermissionResp = userService.revokeAccountPermission(addAccountPermissionReqJson);
        assertThat(revokeAccountPermissionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPermissionsResp = userService.getPermissions(getPermissionsReqJson);
        assertThat(getPermissionsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPermissionsResp.getPermissionGroupList()).hasSize(1);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermissionList()).hasSize(6);
        /* 管理员, 主任, 护士长, 护理组长 */
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getRoleList()).hasSize(4);
        assertThat(getPermissionsResp.getPermissionGroup(0).getPermission(4).getAccountIdList()).hasSize(0);
    }

    private final String accountId1;
    private final String accountId2;
    private final String accountId3;
    private final String accountId4;
    private final String accountId5;
    private final String accountId6;
    private String deptId1;
    private String deptId2;
    private String deptId3;
    private String deptId4;
    private String deptId5;
    private String deptId6;
    private String deptId7;
    private final Integer DIRECTOR_ROLE_ID;
    private final Integer HEAD_NURSE_ROLE_ID;
    private final Integer NURSE_ROLE_ID;

    private final UserService userService;
    private final RbacDepartmentAccountRoleRepository deptAccountRoleRepo;
}