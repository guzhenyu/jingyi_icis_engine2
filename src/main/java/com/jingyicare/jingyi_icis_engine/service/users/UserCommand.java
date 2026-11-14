package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.users.auth.Encoder;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserCommand {
    public UserCommand(
        @Autowired RbacPermissionRepository permRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired RbacRolePermissionRepository rolePermRepo,
        @Autowired RbacAccountRepository accountRepo,
        @Autowired AccountRepository accountInfoRepo,
        @Autowired RbacAccountRoleRepository accountRoleRepo,
        @Autowired RbacAccountPermissionRepository accountPermRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired RbacDepartmentAccountRepository rbacDeptAcctRepo,
        @Autowired DepartmentAccountRepository deptAcctRepo,
        @Autowired RbacDepartmentAccountRoleRepository deptAccountRoleRepo,
        @Autowired RbacDepartmentAccountPermissionRepository deptAccountPermRepo,
        @Autowired Encoder encoder,
        @Autowired LockExecutor lockExecutor,
        @Autowired JdbcTemplate jdbcTemplate
    ) {
        this.permRepo = permRepo;
        this.roleRepo = roleRepo;
        this.rolePermRepo = rolePermRepo;
        this.accountRepo = accountRepo;
        this.accountInfoRepo = accountInfoRepo;
        this.accountRoleRepo = accountRoleRepo;
        this.accountPermRepo = accountPermRepo;
        this.deptRepo = deptRepo;
        this.rbacDeptAcctRepo = rbacDeptAcctRepo;
        this.deptAcctRepo = deptAcctRepo;
        this.deptAccountRoleRepo = deptAccountRoleRepo;
        this.deptAccountPermRepo = deptAccountPermRepo;

        this.encoder = encoder.get();
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    // 账号与密码：维护 accounts、rbac_accounts 及账号角色关系。
    // modifiedByAutoAcctId: 优先使用 accounts.id，缺失时回退账号编码；系统操作使用 system。
    public StatusCode addAccount(AccountPB accountPb, String modifiedByAutoAcctId) {
        final String rbacAcctId = accountPb.getAccountId();
        if (rbacAcctId.isBlank()) return StatusCode.ACCOUNT_NOT_FOUND;

        Set<String> deptIds = new HashSet<>();
        List<String> resourceKeys = new ArrayList<>();
        resourceKeys.add(LockKeys.account(rbacAcctId));
        for (AccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
            String deptId = acctDeptPb.getDeptId();
            if (deptId.isBlank()) return StatusCode.DEPT_IS_EMPTY;

            if (!deptIds.add(deptId)) {
                log.warn("Duplicate account department: {} {}", rbacAcctId, deptId);
                return StatusCode.ACCOUNT_DEPARTMENT_DUPLICATE;
            }
            resourceKeys.add(LockKeys.department(deptId));
        }

        return lockExecutor.execute(resourceKeys, () -> {
            if (accountRepo.findByAccountId(rbacAcctId).isPresent()) {
                log.warn("RbacAccount already exists: {}", rbacAcctId);
                return StatusCode.ACCOUNT_ALREADY_EXISTS;
            }

            if (accountInfoRepo.findByAccountIdAndIsDeletedFalse(rbacAcctId).isPresent()) {
                log.warn("Account already exists: {}", rbacAcctId);
                return StatusCode.ACCOUNT_ALREADY_EXISTS;
            }

            for (AccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
                final String deptId = acctDeptPb.getDeptId();
                final Integer roleId = acctDeptPb.getRoleId();

                if (!deptRepo.findByDeptId(deptId).isPresent()) {
                    log.warn("RbacDepartment not found: {}", deptId);
                    return StatusCode.DEPARTMENT_NOT_FOUND;
                }

                RbacRole role = roleRepo.findById(roleId).orElse(null);
                if (role == null) {
                    log.warn("RbacRole not found: {}", roleId);
                    return StatusCode.ROLE_NOT_FOUND;
                }
                if (!role.getIsPrimary()) {
                    log.warn("RbacRole is not primary: {}", roleId);
                    return StatusCode.ROLE_NOT_PRIMARY;
                }

                if (rbacDeptAcctRepo.findById(
                    new RbacDepartmentAccountId(deptId, rbacAcctId)).isPresent()) {
                    log.warn("RbacDepartmentAccount already exists: {} {}", deptId, rbacAcctId);
                    return StatusCode.DEPARTMENT_ACCOUNT_ALREADY_EXISTS;
                }
            }

            RbacAccount account = UserMapper.toRbacAccount(accountPb);
            String passwordHash = StrUtils.isBlank(accountPb.getPassword())
                ? RsaUtils.encodePassword(rbacAcctId, encoder)
                : encoder.encode(accountPb.getPassword());
            account.setPasswordHash(passwordHash);
            accountRepo.save(account);

            Account accountInfo = UserMapper.toAccount(accountPb);
            accountInfo.setModifiedBy(modifiedByAutoAcctId);
            accountInfo.setModifiedAt(TimeUtils.getNowUtc());
            accountInfo = accountInfoRepo.save(accountInfo);

            for (AccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
                final String deptId = acctDeptPb.getDeptId();
                final Integer roleId = acctDeptPb.getRoleId();

                RbacDepartmentAccount rbacDeptAcct = new RbacDepartmentAccount();
                rbacDeptAcct.setId(new RbacDepartmentAccountId(deptId, rbacAcctId));
                rbacDeptAcct.setPrimaryRoleId(roleId);
                rbacDeptAcctRepo.save(rbacDeptAcct);

                DepartmentAccount deptAcct = DepartmentAccount.builder()
                    .employeeId(accountInfo.getId())
                    .accountId(rbacAcctId)
                    .deptId(deptId)
                    .primaryRoleId(roleId)
                    .startDate(TimeUtils.getNowUtc())
                    .isDeleted(false)
                    .build();
                deptAcctRepo.save(deptAcct);

                RbacDepartmentAccountRole deptAccountRole = new RbacDepartmentAccountRole();
                deptAccountRole.setId(new RbacDepartmentAccountRoleId(
                    deptId, rbacAcctId, roleId));
                deptAccountRoleRepo.save(deptAccountRole);
            }

            log.info("Account added: {}, by {}", rbacAcctId, modifiedByAutoAcctId);
            return StatusCode.OK;
        });
    }

    public StatusCode updateAccount(AccountPB accountPb, String modifiedByAutoAcctId) {
        final String rbacAcctId = accountPb.getAccountId();
        if (rbacAcctId.isBlank()) return StatusCode.ACCOUNT_NOT_FOUND;

        Set<String> deptIdsToUpdate = new HashSet<>();
        List<String> requestedResourceKeys = new ArrayList<>();
        requestedResourceKeys.add(LockKeys.account(rbacAcctId));
        for (AccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
            String deptId = acctDeptPb.getDeptId();
            if (deptId.isBlank()) return StatusCode.DEPT_IS_EMPTY;

            if (!deptIdsToUpdate.add(deptId)) {
                log.warn("Duplicate account department: {} {}", rbacAcctId, deptId);
                return StatusCode.ACCOUNT_DEPARTMENT_DUPLICATE;
            }
            requestedResourceKeys.add(LockKeys.department(deptId));
        }

        return lockExecutor.executePlanned(
            () -> RbacDepartmentAccountJdbc.findDepartmentIds(jdbcTemplate, rbacAcctId),
            currentDeptIds -> {
                Set<String> resourceKeys = new HashSet<>(requestedResourceKeys);
                for (String deptId : currentDeptIds) {
                    resourceKeys.add(LockKeys.department(deptId));
                }
                return resourceKeys;
            },
            currentDeptIds -> currentDeptIds.equals(
                RbacDepartmentAccountJdbc.findDepartmentIds(jdbcTemplate, rbacAcctId)),
            currentDeptIds -> {
                Account accountInfo = accountInfoRepo
                    .findByAccountIdAndIsDeletedFalse(rbacAcctId).orElse(null);
                if (accountInfo == null) {
                    log.warn("Account not found: {}", rbacAcctId);
                    return StatusCode.ACCOUNT_NOT_FOUND;
                }
                RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
                if (account == null) {
                    log.warn("RbacAccount not found: {}", rbacAcctId);
                    return StatusCode.ACCOUNT_NOT_FOUND;
                }

                Map<String, RbacDepartmentAccount> deptAccountsToDelete = new LinkedHashMap<>();
                for (RbacDepartmentAccount deptAccount
                    : rbacDeptAcctRepo.findByIdAccountId(rbacAcctId)) {
                    deptAccountsToDelete.put(deptAccount.getId().getDeptId(), deptAccount);
                }
                List<AccountDepartmentPB> newDeptAccountPbs = new ArrayList<>();
                List<Map.Entry<RbacDepartmentAccount, Integer>> changedDeptAccounts =
                    new ArrayList<>();
                Map<String, RbacDepartmentAccountRole> oldPrimaryRoles = new HashMap<>();

                // 全部校验通过后再修改托管实体，避免错误响应提交部分变更。
                for (AccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
                    String deptId = acctDeptPb.getDeptId();
                    Integer roleId = acctDeptPb.getRoleId();
                    if (deptRepo.findByDeptId(deptId).isEmpty()) {
                        log.warn("RbacDepartment not found: {}", deptId);
                        return StatusCode.DEPARTMENT_NOT_FOUND;
                    }
                    RbacRole role = roleRepo.findById(roleId).orElse(null);
                    if (role == null) {
                        log.warn("RbacRole not found: {}", roleId);
                        return StatusCode.ROLE_NOT_FOUND;
                    }
                    if (!role.getIsPrimary()) {
                        log.warn("RbacRole is not primary: {}", roleId);
                        return StatusCode.ROLE_NOT_PRIMARY;
                    }

                    RbacDepartmentAccount deptAccount = deptAccountsToDelete.remove(deptId);
                    if (deptAccount == null) {
                        newDeptAccountPbs.add(acctDeptPb);
                    } else if (deptAccount.getPrimaryRoleId() != acctDeptPb.getRoleId()) {
                        RbacDepartmentAccountRoleId oldRoleId = new RbacDepartmentAccountRoleId(
                            deptId, rbacAcctId, deptAccount.getPrimaryRoleId());
                        RbacDepartmentAccountRole oldRole = deptAccountRoleRepo
                            .findById(oldRoleId).orElse(null);
                        if (oldRole == null) {
                            log.warn("RbacDepartmentAccountRole not found: {}", oldRoleId);
                            return StatusCode.DEPARTMENT_ACCOUNT_ROLE_NOT_FOUND;
                        }
                        RbacDepartmentAccountRoleId newRoleId = new RbacDepartmentAccountRoleId(
                            deptId, rbacAcctId, roleId);
                        if (deptAccountRoleRepo.existsById(newRoleId)) {
                            log.warn("RbacDepartmentAccountRole already exists: {}", newRoleId);
                            return StatusCode.DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS;
                        }
                        oldPrimaryRoles.put(deptId, oldRole);
                        changedDeptAccounts.add(Map.entry(deptAccount, roleId));
                    }
                }

                accountInfo.setName(accountPb.getAccountName());
                accountInfo.setGender(accountPb.getGender());
                accountInfo.setDateOfBirth(
                    TimeUtils.fromIso8601String(accountPb.getDateOfBirthIso8601(), "UTC"));
                accountInfo.setTitle(accountPb.getTitle());
                accountInfo.setPhone(accountPb.getPhone());
                accountInfo.setIdCardNumber(accountPb.getIdCardNumber());
                accountInfo.setSignPic(accountPb.getSignPic());
                accountInfoRepo.save(accountInfo);
                account.setAccountName(accountPb.getAccountName());
                accountRepo.save(account);

                for (AccountDepartmentPB acctDeptPb : newDeptAccountPbs) {
                    String deptId = acctDeptPb.getDeptId();
                    Integer roleId = acctDeptPb.getRoleId();
                    RbacDepartmentAccount deptAccount = new RbacDepartmentAccount();
                    deptAccount.setId(new RbacDepartmentAccountId(deptId, rbacAcctId));
                    deptAccount.setPrimaryRoleId(roleId);
                    rbacDeptAcctRepo.save(deptAccount);

                    DepartmentAccount deptAcct = DepartmentAccount.builder()
                        .employeeId(accountInfo.getId())
                        .accountId(rbacAcctId)
                        .deptId(deptId)
                        .primaryRoleId(roleId)
                        .startDate(TimeUtils.getNowUtc())
                        .isDeleted(false)
                        .build();
                    deptAcctRepo.save(deptAcct);

                    RbacDepartmentAccountRole deptAccountRole = new RbacDepartmentAccountRole();
                    deptAccountRole.setId(new RbacDepartmentAccountRoleId(
                        deptId, rbacAcctId, roleId));
                    deptAccountRoleRepo.save(deptAccountRole);
                }
                log.info("Account updated, dept added: {}, by {}",
                    newDeptAccountPbs, modifiedByAutoAcctId);

                for (Map.Entry<RbacDepartmentAccount, Integer> entry : changedDeptAccounts) {
                    RbacDepartmentAccount rbacDeptAcct = entry.getKey();
                    String deptId = rbacDeptAcct.getId().getDeptId();
                    Integer roleId = entry.getValue();

                    // 修正 accounts_departments 对应的角色
                    deptAccountRoleRepo.delete(oldPrimaryRoles.get(deptId));
                    RbacDepartmentAccountRole deptAccountRole = new RbacDepartmentAccountRole();
                    deptAccountRole.setId(new RbacDepartmentAccountRoleId(
                        deptId, rbacAcctId, roleId));
                    deptAccountRoleRepo.save(deptAccountRole);

                    // 调整 rbac_accounts_departments, accounts_departments
                    rbacDeptAcct.setPrimaryRoleId(roleId);
                    rbacDeptAcctRepo.save(rbacDeptAcct);
                    DepartmentAccount deptAcct = deptAcctRepo
                        .findByAccountIdAndDeptIdAndIsDeletedFalse(rbacAcctId, deptId).orElse(null);
                    if (deptAcct != null) {
                        deptAcct.setPrimaryRoleId(roleId);
                        deptAcctRepo.save(deptAcct);
                    } else {
                        log.warn("DepartmentAccount not found for update: {} {}", rbacAcctId, deptId);
                    }
                }
                log.info("Account updated, dept changed: {}, by {}",
                    changedDeptAccounts, modifiedByAutoAcctId);

                for (RbacDepartmentAccount deptAccount : deptAccountsToDelete.values()) {
                    deleteDepartmentAccount(
                        deptAccount.getId().getDeptId(), rbacAcctId, modifiedByAutoAcctId);
                }
                log.info("Account updated, dept deleted: {}, by {}",
                    deptAccountsToDelete.values(), modifiedByAutoAcctId);
                return StatusCode.OK;
            });
    }

    public StatusCode deleteAccount(String rbacAcctId, String deptId, String modifiedByAutoAcctId) {
        if (rbacAcctId == null || rbacAcctId.isBlank()) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.executePlanned(
            () -> RbacDepartmentAccountJdbc.findDeletePlan(jdbcTemplate, rbacAcctId),
            plan -> {
                Set<String> resourceKeys = new HashSet<>();
                resourceKeys.add(LockKeys.account(rbacAcctId));
                for (String currentDeptId : plan.departmentIds()) {
                    resourceKeys.add(LockKeys.department(currentDeptId));
                }
                if (!StrUtils.isBlank(deptId)) resourceKeys.add(LockKeys.department(deptId));
                return resourceKeys;
            },
            plan -> plan.equals(RbacDepartmentAccountJdbc.findDeletePlan(jdbcTemplate, rbacAcctId)),
            plan -> {
                Account accountInfo = accountInfoRepo
                    .findByAccountIdAndIsDeletedFalse(rbacAcctId).orElse(null);
                RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
                if (accountInfo == null || account == null) {
                    log.warn("Account not found: {}", rbacAcctId);
                    return StatusCode.ACCOUNT_NOT_FOUND;
                }

                Set<String> remainingDeptIds = plan.departmentIds();
                Set<String> deptIdsToDelete = StrUtils.isBlank(deptId)
                    ? Set.copyOf(remainingDeptIds) : Set.of(deptId);
                for (String deptIdToDelete : deptIdsToDelete) {
                    deleteDepartmentAccount(deptIdToDelete, rbacAcctId, modifiedByAutoAcctId);
                }
                remainingDeptIds.removeAll(deptIdsToDelete);

                if (remainingDeptIds.isEmpty()) {
                    // 清理历史全局授权，避免删除 RBAC 账号时触发外键约束。
                    accountRoleRepo.deleteByIdAccountId(rbacAcctId);
                    accountPermRepo.deleteByIdAccountId(rbacAcctId);
                    accountInfo.setIsDeleted(true);
                    accountInfo.setDeletedBy(modifiedByAutoAcctId);
                    accountInfo.setDeletedAt(TimeUtils.getNowUtc());
                    accountInfoRepo.save(accountInfo);
                    accountRepo.delete(account);
                }
                log.info("Account departments deleted: {} {}, by {}",
                    rbacAcctId, deptIdsToDelete, modifiedByAutoAcctId);
                return StatusCode.OK;
            });
    }

    public StatusCode changePassword(String rbacAcctId, String oldPassword, String newPassword) {
        if (StrUtils.isBlank(rbacAcctId)) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.account(rbacAcctId)), () -> {
            RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
            if (account == null) {
                log.warn("RbacAccount not found: {}", rbacAcctId);
                return StatusCode.ACCOUNT_NOT_FOUND;
            }

            String oldPasswordHash = account.getPasswordHash();
            boolean matchesEncodedInput = encoder.matches(oldPassword, oldPasswordHash);
            boolean matchesRawInput = false;
            if (!matchesEncodedInput) {
                // 兼容历史调用传入原始密码；当前客户端传入加密后的密码。
                matchesRawInput = encoder.matches(
                    RsaUtils.encodePassword(oldPassword), oldPasswordHash);
            }

            if (!matchesEncodedInput && !matchesRawInput) {
                log.warn("RbacAccount password mismatch: {}", rbacAcctId);
                return StatusCode.WRONG_PASSWORD;
            }

            String newPasswordHash = matchesRawInput
                ? RsaUtils.encodePassword(newPassword, encoder)
                : encoder.encode(newPassword);
            account.setPasswordHash(newPasswordHash);
            accountRepo.save(account);

            log.info("RbacAccount password changed: {}", rbacAcctId);
            return StatusCode.OK;
        });
    }

    public StatusCode resetPassword(String rbacAcctId, String operatorRbacAcctId) {
        if (StrUtils.isBlank(rbacAcctId)) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.account(rbacAcctId)), () -> {
            RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
            if (account == null) {
                log.warn("RbacAccount not found: {}", rbacAcctId);
                return StatusCode.ACCOUNT_NOT_FOUND;
            }

            if (!UserQuery.ADMIN_ACCOUNT_ID.equals(operatorRbacAcctId)) {
                log.warn("RbacAccount not admin: {}", operatorRbacAcctId);
                return StatusCode.NO_PERMISSION_TO_OPERATE;
            }

            account.setPasswordHash(RsaUtils.encodePassword(rbacAcctId, encoder));
            accountRepo.save(account);

            log.info("RbacAccount password reset: {}, by {}", rbacAcctId, operatorRbacAcctId);
            return StatusCode.OK;
        });
    }

    private void deleteDepartmentAccount(
        String deptId, String rbacAcctId, String modifiedByAutoAcctId
    ) {
        deptAccountPermRepo.deleteByIdAccountIdAndIdDeptId(rbacAcctId, deptId);
        deptAccountRoleRepo.deleteByIdAccountIdAndIdDeptId(rbacAcctId, deptId);
        rbacDeptAcctRepo.deleteById(new RbacDepartmentAccountId(deptId, rbacAcctId));

        DepartmentAccount deptAcct = deptAcctRepo.findByAccountIdAndDeptIdAndIsDeletedFalse(
            rbacAcctId, deptId).orElse(null);
        if (deptAcct == null) return;

        deptAcct.setIsDeleted(true);
        deptAcct.setDeletedBy(modifiedByAutoAcctId);
        deptAcct.setDeletedAt(TimeUtils.getNowUtc());
        deptAcctRepo.save(deptAcct);
    }

    // 角色权限：维护 rbac_roles_permissions。
    public StatusCode addRolePermission(
        Integer roleId, Integer permissionId, String modifiedByAutoAcctId
    ) {
        if (roleId == null || roleId <= 0) return StatusCode.ROLE_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.role(roleId)), () -> {
            RbacRole role = roleRepo.findById(roleId).orElse(null);
            if (role == null || !role.getIsPrimary()) {
                log.error("RbacRole not found: {}", roleId);
                return StatusCode.ROLE_NOT_FOUND;
            }

            RbacPermission permission = permRepo.findById(permissionId).orElse(null);
            if (permission == null) {
                log.error("RbacPermission not found: {}", permissionId);
                return StatusCode.PERMISSION_NOT_FOUND;
            }

            RbacRolePermissionId id = new RbacRolePermissionId(roleId, permissionId);
            RbacRolePermission rolePermission = rolePermRepo.findById(id).orElse(null);
            if (rolePermission != null) {
                log.error("RbacRolePermission already exists: {} {}", roleId, permissionId);
                return StatusCode.OK;
            }

            rolePermission = new RbacRolePermission();
            rolePermission.setId(id);
            rolePermRepo.save(rolePermission);
            log.info("Role permission added: {} {}, by {}",
                roleId, permissionId, modifiedByAutoAcctId);

            return StatusCode.OK;
        });
    }

    public StatusCode revokeRolePermission(
        Integer roleId, Integer permissionId, String modifiedByAutoAcctId
    ) {
        if (roleId == null || roleId <= 0) return StatusCode.ROLE_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.role(roleId)), () -> {
            RbacRole role = roleRepo.findById(roleId).orElse(null);
            if (role == null || !role.getIsPrimary()) {
                log.error("RbacRole not found: {}", roleId);
                return StatusCode.ROLE_NOT_FOUND;
            }

            RbacPermission permission = permRepo.findById(permissionId).orElse(null);
            if (permission == null) {
                log.error("RbacPermission not found: {}", permissionId);
                return StatusCode.PERMISSION_NOT_FOUND;
            }

            RbacRolePermissionId id = new RbacRolePermissionId(roleId, permissionId);
            RbacRolePermission rolePermission = rolePermRepo.findById(id).orElse(null);
            if (rolePermission == null) {
                log.error("RbacRolePermission not found: {} {}", roleId, permissionId);
                return StatusCode.OK;
            }

            rolePermRepo.delete(rolePermission);
            log.info("Role permission revoked: {} {}, by {}",
                roleId, permissionId, modifiedByAutoAcctId);

            return StatusCode.OK;
        });
    }

    // 科室账号权限：维护 rbac_accounts_departments_permissions。
    public StatusCode addAccountPermission(
        String rbacAcctId, String deptId, Integer permissionId,
        String modifiedByAutoAcctId
    ) {
        if (StrUtils.isBlank(rbacAcctId)) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.account(rbacAcctId)), () -> {
            RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
            if (account == null) {
                log.error("RbacAccount not found: {}", rbacAcctId);
                return StatusCode.ACCOUNT_NOT_FOUND;
            }

            RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
            if (dept == null) {
                log.error("RbacDepartment not found: {}", deptId);
                return StatusCode.DEPARTMENT_NOT_FOUND;
            }

            RbacPermission permission = permRepo.findById(permissionId).orElse(null);
            if (permission == null) {
                log.error("RbacPermission not found: {}", permissionId);
                return StatusCode.PERMISSION_NOT_FOUND;
            }

            if (!rbacDeptAcctRepo.existsById(new RbacDepartmentAccountId(deptId, rbacAcctId))) {
                log.warn("RbacDepartmentAccount not found: {} {}", deptId, rbacAcctId);
                return StatusCode.DEPARTMENT_ACCOUNT_NOT_FOUND;
            }

            RbacDepartmentAccountPermissionId id = new RbacDepartmentAccountPermissionId(
                deptId, rbacAcctId, permissionId);
            RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo
                .findById(id).orElse(null);
            if (deptAccountPerm != null) {
                log.error("RbacDepartmentAccountPermission already exists: {} {} {}",
                    deptId, rbacAcctId, permissionId);
                return StatusCode.OK;
            }

            deptAccountPerm = new RbacDepartmentAccountPermission();
            deptAccountPerm.setId(id);
            deptAccountPermRepo.save(deptAccountPerm);
            log.info("Account permission added: {} {} {}, by {}",
                deptId, rbacAcctId, permissionId, modifiedByAutoAcctId);

            return StatusCode.OK;
        });
    }

    public StatusCode revokeAccountPermission(
        String rbacAcctId, String deptId, Integer permissionId,
        String modifiedByAutoAcctId
    ) {
        if (StrUtils.isBlank(rbacAcctId)) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.account(rbacAcctId)), () -> {
            RbacAccount account = accountRepo.findByAccountId(rbacAcctId).orElse(null);
            if (account == null) {
                log.error("RbacAccount not found: {}", rbacAcctId);
                return StatusCode.ACCOUNT_NOT_FOUND;
            }

            RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
            if (dept == null) {
                log.error("RbacDepartment not found: {}", deptId);
                return StatusCode.DEPARTMENT_NOT_FOUND;
            }

            RbacPermission permission = permRepo.findById(permissionId).orElse(null);
            if (permission == null) {
                log.error("RbacPermission not found: {}", permissionId);
                return StatusCode.PERMISSION_NOT_FOUND;
            }

            RbacDepartmentAccountPermissionId id = new RbacDepartmentAccountPermissionId(
                deptId, rbacAcctId, permissionId);
            RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo
                .findById(id).orElse(null);
            if (deptAccountPerm == null) {
                log.error("RbacDepartmentAccountPermission not found: {} {} {}",
                    deptId, rbacAcctId, permissionId);
                return StatusCode.OK;
            }

            deptAccountPermRepo.delete(deptAccountPerm);
            log.info("Account permission revoked: {} {} {}, by {}",
                deptId, rbacAcctId, permissionId, modifiedByAutoAcctId);

            return StatusCode.OK;
        });
    }

    private RbacPermissionRepository permRepo;
    private RbacRoleRepository roleRepo;
    private RbacRolePermissionRepository rolePermRepo;
    private RbacAccountRepository accountRepo;
    private AccountRepository accountInfoRepo;
    private RbacAccountRoleRepository accountRoleRepo;
    private RbacAccountPermissionRepository accountPermRepo;
    private RbacDepartmentRepository deptRepo;
    private RbacDepartmentAccountRepository rbacDeptAcctRepo;
    private DepartmentAccountRepository deptAcctRepo;
    private RbacDepartmentAccountRoleRepository deptAccountRoleRepo;
    private RbacDepartmentAccountPermissionRepository deptAccountPermRepo;

    private BCryptPasswordEncoder encoder;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
}
