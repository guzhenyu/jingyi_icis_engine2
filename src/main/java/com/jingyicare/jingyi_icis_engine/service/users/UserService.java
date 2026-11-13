package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.service.users.menu.MenuService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserService {
    public UserService(
        @Autowired ProtoConfig protoConfig,
        @Autowired UserCmdService userCmdService,
        @Autowired UserQuery userQuery,
        @Autowired MenuService menuService,
        @Autowired CertificateService certificateService
    ) {
        Config config = protoConfig.getConfig();
        UserConfigPB userConfig = config.getUser();
        this.ZONE_ID = config.getZoneId();
        this.ADMIN_ACCOUNT_ID = userConfig.getAdminAccountId();
        this.ADMIN_ROLE_ID = userConfig.getAdminRoleId();
        this.statusCodeMsgList = config.getText().getStatusCodeMsgList();

        this.menuPermissions = new HashMap<>();
        for (MenuPermissionPB menuPermPb : userConfig.getMenuPermissionList()) {
            this.menuPermissions.put(menuPermPb.getMenuGroupId(), menuPermPb.getPermissionId());
        }
        this.fullMenu = userConfig.getMenu();

        this.userCmdService = userCmdService;
        this.userQuery = userQuery;
        this.menuService = menuService;
        this.certificateService = certificateService;
    }

    public GetUsernameResp getUsername() {
        CurrentAccount account = userQuery.getCurrentAccount();
        return GetUsernameResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setUsername(account == null ? "" : account.acctName())
            .build();
    }

    public GetUserInfoResp getUserInfo() {
        // 获取相关用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GetUserInfoResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 获取相关部门，部门用户权限
        List<Department> departments;
        Map<String, Set<Integer>> deptPermIdsMap = new HashMap<>();
        if (account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)) {
            departments = userQuery.getAllDeptInfo();
            for (Department department : departments) {
                Set<Integer> adminRoleSet = new HashSet<>();
                adminRoleSet.add(ADMIN_ROLE_ID);
                deptPermIdsMap.put(department.getDeptId(), adminRoleSet);
            }
        } else {
            departments = userQuery.getAllDeptInfoByAccountId(account.rbacAcctId())
                .stream().sorted(Comparator.comparing(Department::getId)).toList();
            for (Department department : departments) {
                final String deptId = department.getDeptId();
                deptPermIdsMap.put(deptId,
                    userQuery.getPermissionIds(account.rbacAcctId(), deptId));
            }
        }

        // 获取部门用户菜单
        List<DeptInfoPB> deptInfos = new ArrayList<>();
        for (Department department : departments) {
            final String deptId = department.getDeptId();
            if (!certificateService.checkBedAvailable(deptId)) {
                return GetUserInfoResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
                    .build();
            }

            DeptInfoPB.Builder deptInfoBuilder = DeptInfoPB.newBuilder().setDept(
                StringIdEntityPB.newBuilder().setId(deptId).setName(department.getName()).build());
            Menu.Builder menuBuilder = Menu.newBuilder();

            final Set<Integer> permIds = deptPermIdsMap.get(deptId);
            if (permIds.isEmpty()) {
                deptInfos.add(deptInfoBuilder.setMenu(menuBuilder.build()).build());
                continue;
            }
            deptInfoBuilder.addAllPermissionId(permIds);

            List<MenuGroup> menuGroups = menuService.getMenuGroups(deptId);
            for (MenuGroup menuGroup : menuGroups) {
                if (account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)) {
                    menuBuilder.addGroup(menuGroup);
                    continue;
                }

                final Integer menuGroupPermId = menuPermissions.get(menuGroup.getGroupId());
                if (menuGroupPermId == null) {
                    log.error("Menu group {} not found in menuPermissions", menuGroup.getGroupId());
                    continue;
                }
                if (!permIds.contains(menuGroupPermId)) continue;
                menuBuilder.addGroup(menuGroup);
            }

            // 查询并添加主要角色
            deptInfoBuilder.setPrimaryRoleName(
                userQuery.getPrimaryRoleName(account.rbacAcctId(), deptId));
            deptInfos.add(deptInfoBuilder.setMenu(menuBuilder.build()).build());
        }

        return GetUserInfoResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setUser(StringIdEntityPB.newBuilder()
                .setId(account.rbacAcctId()).setName(account.acctName()).build())
            .addAllDeptInfo(deptInfos)
            .build();
    }

    public GetAllDeptNamesResp getAllDeptNames(String getAllDeptNamesReqJson) {
        List<StringIdEntityPB> deptEntityIds = userQuery.getAllDeptInfo()
            .stream()
            .sorted(Comparator.comparing(Department::getId))
            .map(dept -> StringIdEntityPB.newBuilder()
                .setId(dept.getDeptId()).setName(dept.getName()).build())
            .toList();
        return GetAllDeptNamesResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllDept(deptEntityIds)
            .build();
    }

    public GetAllDeptsResp getAllDepts(String getAllDeptsReqJson) {
        return GetAllDeptsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllDepartment(userQuery.getAllDepartments())
            .build();
    }

    public AddDeptResp addDept(String addDeptReqJson) {
        final AddDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addDeptReqJson, AddDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        if (!account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)) {
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }
        if (!certificateService.isDepartmentCreationAllowed()) {
            log.error("Production ICIS department scope cannot be expanded online");
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }

        // 新增部门
        IcisDepartmentPB deptPb = req.getDepartment();
        StatusCode statusCode = userCmdService.addDepartment(
            deptPb.getDeptId(), deptPb.getDeptName(), deptPb.getAbbreviation(),
            deptPb.getWardCode(), deptPb.getWardName(), deptPb.getHospitalName(),
            account.autoAcctId()
        );
        if (statusCode != StatusCode.OK) {
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode))
                .build();
        }

        // 查找部门Id
        Integer deptIntegerId = userQuery.getDepartmentId(deptPb.getDeptId());
        if (deptIntegerId == null) {
            log.error("Failed to find department ID for department id {}", deptPb.getDeptId());
            return AddDeptResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.DEPARTMENT_NOT_FOUND))
                .build();
        }

        return AddDeptResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setDeptId(deptIntegerId)
            .build();
    }

    public GenericResp updateDept(String updateDeptReqJson) {
        final AddDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateDeptReqJson, AddDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        if (!account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }

        IcisDepartmentPB deptPb = req.getDepartment();
        if (!certificateService.isDepartmentIdentityUpdateAllowed(
            deptPb.getId(),
            deptPb.getDeptId(),
            deptPb.getDeptName(),
            deptPb.getHospitalName()
        )) {
            log.error("Production ICIS department identity cannot be changed online: {}",
                deptPb.getDeptId());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, userCmdService.updateDepartment(
                deptPb.getId(), deptPb.getDeptId(), deptPb.getDeptName(),
                deptPb.getAbbreviation(), deptPb.getWardCode(), deptPb.getWardName(),
                deptPb.getHospitalName(), account.autoAcctId()
            ))).build();
    }

    public GenericResp deleteDept(String deleteDeptReqJson) {
        final DeleteDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteDeptReqJson, DeleteDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        if (!account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }
        if (!certificateService.isDepartmentDeletionAllowed()) {
            log.error("Production ICIS department scope cannot be reduced online: {}",
                req.getDeptId());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, userCmdService.deleteDepartment(
                req.getDeptId(), account.autoAcctId()
            ))).build();
    }

    public GetAllAccountsResp getAllAccounts(String getAllAccountsReqJson) {
        final GetAllAccountsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                getAllAccountsReqJson, GetAllAccountsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetAllAccountsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return GetAllAccountsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllAccount(userQuery.getAllAccounts(
                req.getDeptId(), req.getAccountId(), req.getAccountName(), ZONE_ID
            ).stream().filter(account -> !account.getAccountId().equals(ADMIN_ACCOUNT_ID)).toList())
            .build();
    }

    public GenericResp addAccount(String addAccountReqJson) {
        final AddAccountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addAccountReqJson, AddAccountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查用户权限
        Pair<String, Boolean> permPair = userQuery.hasPermission(
            account.rbacAcctId(), req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.addAccount(
                    req.getAccount(), account.autoAcctId())))
            .build();
    }

    public GenericResp updateAccount(String updateAccountReqJson) {
        final AddAccountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateAccountReqJson, AddAccountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查用户权限
        if (!account.rbacAcctId().equals(req.getAccount().getAccountId())) {
            Pair<String, Boolean> permPair = userQuery.hasPermission(
                account.rbacAcctId(), req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
            if (permPair == null || !permPair.getSecond()) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(
                        statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                    .build();
            }
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.updateAccount(
                    req.getAccount(), account.autoAcctId())))
            .build();
    }

    public GenericResp deleteAccount(String deleteAccountReqJson) {
        final DeleteAccountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteAccountReqJson, DeleteAccountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查用户权限
        Pair<String, Boolean> permPair = userQuery.hasPermission(
            account.rbacAcctId(), req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }
        if (account.rbacAcctId().equals(req.getAccountId())) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.CANNOT_DELETE_SELF_ACCOUNT))
                .build();
        }

        // deleteAccount(String accountId, String deptId, String modifiedBy)
        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, userCmdService.deleteAccount(
                req.getAccountId(), req.getDeptId(), account.autoAcctId()
            )))
            .build();
    }

    public GenericResp changePassword(String changePasswordReqJson) {
        final ChangePasswordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                changePasswordReqJson, ChangePasswordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList,
                userCmdService.changePassword(
                    account.rbacAcctId(), req.getOldPassword(), req.getNewPassword())
            ))
            .build();
    }

    public GenericResp resetPassword(String resetPasswordReqJson) {
        final ResetPasswordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(resetPasswordReqJson, ResetPasswordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查用户权限
        Pair<String, Boolean> permPair = userQuery.hasPermission(
            account.rbacAcctId(), req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.resetPassword(
                    req.getAccountId(), account.rbacAcctId(), ADMIN_ROLE_ID)))
            .build();
    }

    public GetRolesResp getRoles(String getRolesReqJson) {
        return GetRolesResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllRole(userQuery.getPrimaryRoles()
                .stream()
                .filter(role -> role.getId() !=  ADMIN_ROLE_ID)
                .toList()
            )
            .build();
    }

    public GetPermissionsResp getPermissions(String getPermissionsReqJson) {
        final GetPermissionsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                getPermissionsReqJson, GetPermissionsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPermissionsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        if (!userQuery.departmentExists(deptId)) {
            log.error("Department {} not found", deptId);
            return GetPermissionsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.DEPARTMENT_NOT_FOUND))
                .build();
        }

        return GetPermissionsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllPermissionGroup(userQuery.getNonPrimaryRolePermissions(deptId))
            .build();
    }

    public GenericResp addRolePermission(String addRolePermissionReqJson) {
        final AddRolePermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addRolePermissionReqJson, AddRolePermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.addRolePermission(
                    req.getRoleId(), req.getPermissionId(), account.autoAcctId()
                )))
            .build();
    }

    public GenericResp revokeRolePermission(String revokeRolePermissionReqJson) {
        final AddRolePermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                revokeRolePermissionReqJson, AddRolePermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.revokeRolePermission(
                    req.getRoleId(), req.getPermissionId(), account.autoAcctId()
                )))
            .build();
    }

    public GenericResp addAccountPermission(String addAccountPermissionReqJson) {
        final AddAccountPermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addAccountPermissionReqJson, AddAccountPermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.addAccountPermission(
                    req.getAccountId(), req.getDeptId(), req.getPermissionId(), account.autoAcctId()
                )))
            .build();
    }

    public GenericResp revokeAccountPermission(String revokeAccountPermissionReqJson) {
        final AddAccountPermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                revokeAccountPermissionReqJson, AddAccountPermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, userCmdService.revokeAccountPermission(
                    req.getAccountId(), req.getDeptId(), req.getPermissionId(), account.autoAcctId()
                )))
            .build();
    }

    final String ZONE_ID;
    final String ADMIN_ACCOUNT_ID;
    final Integer ADMIN_ROLE_ID;
    final Map<Integer, Integer> menuPermissions;
    final Menu fullMenu;

    private final List<String> statusCodeMsgList;
    private UserCmdService userCmdService;
    private UserQuery userQuery;
    private MenuService menuService;
    private CertificateService certificateService;
}
