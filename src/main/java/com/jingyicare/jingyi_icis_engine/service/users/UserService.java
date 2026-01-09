package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Comparator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserService {
    public UserService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserBasicOperator userBasicOp,
        @Autowired MenuService menuService,
        @Autowired CertificateService certificateService
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.ADMIN_ACCOUNT_ID = protoService.getConfig().getUser().getAdminAccountId();
        this.ADMIN_ROLE_ID = protoService.getConfig().getUser().getAdminRoleId();

        this.menuPermissions = new HashMap<>();
        for (MenuPermissionPB menuPermPb : protoService.getConfig().getUser().getMenuPermissionList()) {
            this.menuPermissions.put(menuPermPb.getMenuGroupId(), menuPermPb.getPermissionId());
        }
        this.fullMenu = protoService.getConfig().getUser().getMenu();

        this.protoService = protoService;
        this.userBasicOp = userBasicOp;
        this.menuService = menuService;
        this.certificateService = certificateService;
    }

    public String getCtxAccountId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal().equals("anonymousUser"))
            return "";
        return auth.getName();
    }

    public Pair<String/*accountAutoId*/, String/*accountName*/> getAccountWithAutoId() {
        String accountId = getCtxAccountId();
        if (StrUtils.isBlank(accountId)) return null;

        RbacAccount rbacAccount = userBasicOp.getRbacAccount(accountId);
        if (rbacAccount == null) return null;
        Pair<String, String> fallbackAcctPair = new Pair<>(accountId, rbacAccount.getAccountName());

        Account account = userBasicOp.getAccount(accountId);
        if (account == null) return fallbackAcctPair;
        return new Pair<>(account.getId().toString(), account.getName());
    }

    public String getNameByAutoId(String accountAutoId) {
        if (StrUtils.isBlank(accountAutoId)) return "";
        
        // 尝试从Account表中获取
        Account account = userBasicOp.getAccountByAutoId(accountAutoId);
        if (account != null) {
            return account.getName();
        }
        
        // 如果Account表中没有，则尝试从RbacAccount表中获取
        account = userBasicOp.getAccount(accountAutoId);
        if (account != null) {
            return account.getName();
        }
        return "";
    }

    public Pair<String/*primary_role_name*/, Boolean/*has_permission*/> hasPermission(String deptId,Integer permissionId) {
        String accountId = getCtxAccountId();
        if (StrUtils.isBlank(accountId)) return null;

        Boolean hasPermission = accountId.equals(ADMIN_ACCOUNT_ID) ||
            userBasicOp.getPermissionIds(accountId, deptId).contains(permissionId);
        return new Pair<>(userBasicOp.getPrimaryRoleName(accountId, deptId), hasPermission);
    }

    public GetUsernameResp getUsername() {
        RbacAccount account = userBasicOp.getRbacAccount(getCtxAccountId());
        final String accountName = account == null ? "" : account.getAccountName();
        return GetUsernameResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setUsername(accountName)
            .build();
    }

    public GetUserInfoResp getUserInfo() {
        // 获取相关用户
        RbacAccount account = userBasicOp.getRbacAccount(getCtxAccountId());
        if (account == null) {
            return GetUserInfoResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getAccountId();
        final String accountName = account.getAccountName();

        // 获取相关部门，部门用户权限
        List<Department> departments;
        Map<String, Set<Integer>> deptPermIdsMap = new HashMap<>();
        if (accountId.equals(ADMIN_ACCOUNT_ID)) {
            departments = userBasicOp.getAllDeptInfo();
            for (Department department : departments) {
                Set<Integer> adminRoleSet = new HashSet<>();
                adminRoleSet.add(ADMIN_ROLE_ID);
                deptPermIdsMap.put(department.getDeptId(), adminRoleSet);
            }
        } else {
            departments = userBasicOp.getAllDeptInfoByAccountId(accountId)
                .stream().sorted(Comparator.comparing(Department::getId)).toList();
            for (Department department : departments) {
                final String deptId = department.getDeptId();
                deptPermIdsMap.put(deptId, userBasicOp.getPermissionIds(accountId, deptId));
            }
        }

        // 获取部门用户菜单
        List<DeptInfoPB> deptInfos = new ArrayList<>();
        for (Department department : departments) {
            final String deptId = department.getDeptId();
            if (!certificateService.checkBedAvailable(deptId, 0)) {
                return GetUserInfoResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
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
                if (accountId.equals(ADMIN_ACCOUNT_ID)) {
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
            deptInfoBuilder.setPrimaryRoleName(userBasicOp.getPrimaryRoleName(accountId, deptId));
            deptInfos.add(deptInfoBuilder.setMenu(menuBuilder.build()).build());
        }

        return GetUserInfoResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setUser(StringIdEntityPB.newBuilder().setId(accountId).setName(accountName).build())
            .addAllDeptInfo(deptInfos)
            .build();
    }

    public GetAllDeptNamesResp getAllDeptNames(String getAllDeptNamesReqJson) {
        List<StringIdEntityPB> deptEntityIds = userBasicOp.getAllDeptInfo()
            .stream()
            .sorted(Comparator.comparing(Department::getId))
            .map(dept -> StringIdEntityPB.newBuilder().setId(dept.getDeptId()).setName(dept.getName()).build())
            .toList();
        return GetAllDeptNamesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDept(deptEntityIds)
            .build();
    }

    public GetAllDeptsResp getAllDepts(String getAllDeptsReqJson) {
        return GetAllDeptsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDepartment(userBasicOp.getAllDepartments())
            .build();
    }

    public AddDeptResp addDept(String addDeptReqJson) {
        final AddDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addDeptReqJson, AddDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddDeptResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return AddDeptResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }
        if (!accountId.equals(ADMIN_ACCOUNT_ID)) {
            return AddDeptResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        // 新增部门
        IcisDepartmentPB deptPb = req.getDepartment();
        StatusCode statusCode = userBasicOp.addDepartment(
            deptPb.getDeptId(), deptPb.getDeptName(), deptPb.getAbbreviation(),
            deptPb.getWardCode(), deptPb.getWardName(), deptPb.getHospitalName(),
            accountAutoId
        );
        if (statusCode != StatusCode.OK) {
            return AddDeptResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }

        // 查找部门Id
        Integer deptIntegerId = userBasicOp.getDepartmentId(deptPb.getDeptId());
        if (deptIntegerId == null) {
            log.error("Failed to find department ID for department id {}", deptPb.getDeptId());
            return AddDeptResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPARTMENT_NOT_FOUND))
                .build();
        }

        return AddDeptResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }
        if (!accountId.equals(ADMIN_ACCOUNT_ID)) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        IcisDepartmentPB deptPb = req.getDepartment();
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.updateDepartment(
                deptPb.getId(), deptPb.getDeptId(), deptPb.getDeptName(),
                deptPb.getAbbreviation(), deptPb.getWardCode(), deptPb.getWardName(),
                deptPb.getHospitalName(), accountAutoId
            ))).build();
    }

    public GenericResp deleteDept(String deleteDeptReqJson) {
        final DeleteDeptReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteDeptReqJson, DeleteDeptReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }
        if (!accountId.equals(ADMIN_ACCOUNT_ID)) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ONLY_ADMIN_IS_AUTHORIZED))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.deleteDepartment(
                req.getDeptId(), accountAutoId
            ))).build();
    }

    public GetAllAccountsResp getAllAccounts(String getAllAccountsReqJson) {
        final GetAllAccountsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getAllAccountsReqJson, GetAllAccountsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetAllAccountsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return GetAllAccountsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllAccount(userBasicOp.getAllAccounts(
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        // 检查用户权限
        Pair<String, Boolean> permPair = hasPermission(req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.addAccount(req.getAccount(), accountAutoId)))
            .build();
    }

    public GenericResp updateAccount(String updateAccountReqJson) {
        final AddAccountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateAccountReqJson, AddAccountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        // 检查用户权限
        if (!accountId.equals(req.getAccount().getAccountId())) {
            Pair<String, Boolean> permPair = hasPermission(req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
            if (permPair == null || !permPair.getSecond()) {
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PERMISSION_DENIED))
                    .build();
            }
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.updateAccount(req.getAccount(), accountAutoId)))
            .build();
    }

    public GenericResp deleteAccount(String deleteAccountReqJson) {
        final DeleteAccountReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteAccountReqJson, DeleteAccountReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        // 检查用户权限
        Pair<String, Boolean> permPair = hasPermission(req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PERMISSION_DENIED))
                .build();
        }
        if (accountId.equals(req.getAccountId())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.CANNOT_DELETE_SELF_ACCOUNT))
                .build();
        }

        // deleteAccount(String accountId, String deptId, String modifiedBy)
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.deleteAccount(
                req.getAccountId(), req.getDeptId(), accountAutoId
            )))
            .build();
    }

    public GenericResp changePassword(String changePasswordReqJson) {
        final ChangePasswordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(changePasswordReqJson, ChangePasswordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(
                userBasicOp.changePassword(accountId, req.getOldPassword(), req.getNewPassword())
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
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 检查用户权限
        Pair<String, Boolean> permPair = hasPermission(req.getOpDeptId(), Consts.PERM_ID_CONFIG_CHECKLIST);
        if (permPair == null || !permPair.getSecond()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PERMISSION_DENIED))
                .build();
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.resetPassword(req.getAccountId(), accountId, ADMIN_ROLE_ID)))
            .build();
    }

    public GetRolesResp getRoles(String getRolesReqJson) {
        return GetRolesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRole(userBasicOp.getPrimaryRoles()
                .stream()
                .filter(role -> role.getId() !=  ADMIN_ROLE_ID)
                .toList()
            )
            .build();
    }

    public GetPermissionsResp getPermissions(String getPermissionsReqJson) {
        final GetPermissionsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPermissionsReqJson, GetPermissionsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetPermissionsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDeptId();
        if (!userBasicOp.departmentExists(deptId)) {
            log.error("Department {} not found", deptId);
            return GetPermissionsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DEPARTMENT_NOT_FOUND))
                .build();
        }

        return GetPermissionsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllPermissionGroup(userBasicOp.getNonPrimaryRolePermissions(deptId))
            .build();
    }

    public GenericResp addRolePermission(String addRolePermissionReqJson) {
        final AddRolePermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addRolePermissionReqJson, AddRolePermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.addRolePermission(
                req.getRoleId(), req.getPermissionId(), accountAutoId
            )))
            .build();
    }

    public GenericResp revokeRolePermission(String revokeRolePermissionReqJson) {
        final AddRolePermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(revokeRolePermissionReqJson, AddRolePermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.revokeRolePermission(
                req.getRoleId(), req.getPermissionId(), accountAutoId
            )))
            .build();
    }

    public GenericResp addAccountPermission(String addAccountPermissionReqJson) {
        final AddAccountPermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addAccountPermissionReqJson, AddAccountPermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.addAccountPermission(
                req.getAccountId(), req.getDeptId(), req.getPermissionId(), accountAutoId
            )))
            .build();
    }

    public GenericResp revokeAccountPermission(String revokeAccountPermissionReqJson) {
        final AddAccountPermissionReq req;
        try {
            req = ProtoUtils.parseJsonToProto(revokeAccountPermissionReqJson, AddAccountPermissionReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String accountId = getCtxAccountId();
        if (accountId.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NOT_LOGGED_IN))
                .build();
        }

        // 获取accountAutoId
        Account account = userBasicOp.getAccount(accountId);
        String accountAutoId = account == null ? accountId : account.getId().toString();

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(userBasicOp.revokeAccountPermission(
                req.getAccountId(), req.getDeptId(), req.getPermissionId(), accountAutoId
            )))
            .build();
    }

    final String ZONE_ID;
    final String ADMIN_ACCOUNT_ID;
    final Integer ADMIN_ROLE_ID;
    final Map<Integer, Integer> menuPermissions;
    final Menu fullMenu;

    private ConfigProtoService protoService;
    private UserBasicOperator userBasicOp;
    private MenuService menuService;
    private CertificateService certificateService;
}