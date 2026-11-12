package com.jingyicare.jingyi_icis_engine.service.users;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BgaMetaService;
import com.jingyicare.jingyi_icis_engine.service.users.auth.Encoder;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserCmdService {
    public UserCmdService(
        @Autowired RbacPermissionRepository permRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired RbacRolePermissionRepository rolePermRepo,
        @Autowired RbacAccountRepository accountRepo,
        @Autowired AccountRepository accountInfoRepo,
        @Autowired RbacAccountRoleRepository accountRoleRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired DepartmentRepository deptInfoRepo,
        @Autowired RbacDepartmentAccountRepository deptAccountRepo,
        @Autowired DepartmentAccountRepository deptAcctRepo,
        @Autowired RbacDepartmentAccountRoleRepository deptAccountRoleRepo,
        @Autowired RbacDepartmentAccountPermissionRepository deptAccountPermRepo,
        @Autowired Encoder encoder,
        @Autowired BgaMetaService bgaMetaService,
        @Autowired LockExecutor lockExecutor
    ) {
        this.permRepo = permRepo;
        this.roleRepo = roleRepo;
        this.rolePermRepo = rolePermRepo;
        this.accountRepo = accountRepo;
        this.accountInfoRepo = accountInfoRepo;
        this.accountRoleRepo = accountRoleRepo;
        this.deptRepo = deptRepo;
        this.deptInfoRepo = deptInfoRepo;
        this.deptAccountRepo = deptAccountRepo;
        this.deptAcctRepo = deptAcctRepo;
        this.deptAccountRoleRepo = deptAccountRoleRepo;
        this.deptAccountPermRepo = deptAccountPermRepo;

        this.encoder = encoder.get();
        this.bgaMetaService = bgaMetaService;
        this.lockExecutor = lockExecutor;
    }

    // 账号与密码：维护 accounts、rbac_accounts 及账号角色关系。
    @Transactional
    public StatusCode addAccount(AccountPB accountPb) {
        final String accountId = accountPb.getId();

        final StatusCode statusCode = checkAddAccount(accountId);
        if (statusCode != StatusCode.OK) return statusCode;

        for (Integer roleId : accountPb.getRoleIdList()) {
            RbacRole role = roleRepo.findById(roleId).orElse(null);
            if (role == null) {
                log.error("RbacRole not found: {}", roleId);
                return StatusCode.ROLE_NOT_FOUND;
            }
            RbacAccountRoleId accountRoleId = new RbacAccountRoleId(accountId, roleId);
            RbacAccountRole accountRole = accountRoleRepo.findById(accountRoleId).orElse(null);
            if (accountRole != null) {
                log.error("RbacAccountRole already exists: {} {}", accountId, roleId);
                return StatusCode.ACCOUNT_ROLE_ALREADY_EXISTS;
            }
        }

        IcisAccountPB icisAccountPb = IcisAccountPB.newBuilder()
            .setAccountId(accountId)
            .setAccountName(accountPb.getName())
            .setGender(accountPb.getGender())
            .setDateOfBirthIso8601(accountPb.getDateOfBirthIso8601())
            .build();
        addAccountImpl(icisAccountPb, "System");

        for (Integer roleId : accountPb.getRoleIdList()) {
            RbacAccountRoleId accountRoleId = new RbacAccountRoleId(accountId, roleId);
            RbacAccountRole accountRole = new RbacAccountRole();
            accountRole.setId(accountRoleId);
            accountRoleRepo.save(accountRole);
        }

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode addAccount(IcisAccountPB accountPb, String modifiedBy) {
        final String accountId = accountPb.getAccountId();

        StatusCode statusCode = checkAddAccount(accountId);
        if (statusCode != StatusCode.OK) return statusCode;

        for (IcisAccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
            statusCode = checkDepartmentAndRole(acctDeptPb.getDeptId(), acctDeptPb.getRoleId());
            if (statusCode != StatusCode.OK) return statusCode;
        }

        addAccountImpl(accountPb, modifiedBy);

        for (IcisAccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
            statusCode = addDepartmentAccount(
                acctDeptPb.getDeptId(), accountId, acctDeptPb.getRoleId());
            if (statusCode != StatusCode.OK) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return statusCode;
            }
        }

        log.info("Account added: {}, by {}", accountId, modifiedBy);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode updateAccount(IcisAccountPB accountPb, String modifiedBy) {
        final String accountId = accountPb.getAccountId();

        Account accountInfo = accountInfoRepo
            .findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
        if (accountInfo == null) {
            log.error("Account not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        } else {
            accountInfo.setName(accountPb.getAccountName());
            accountInfo.setGender(accountPb.getGender());
            accountInfo.setDateOfBirth(
                TimeUtils.fromIso8601String(accountPb.getDateOfBirthIso8601(), "UTC")
            );
            accountInfo.setTitle(accountPb.getTitle());
            accountInfo.setPhone(accountPb.getPhone());
            accountInfo.setIdCardNumber(accountPb.getIdCardNumber());
            accountInfo.setSignPic(accountPb.getSignPic());
            accountInfoRepo.save(accountInfo);
        }

        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return StatusCode.ACCOUNT_NOT_FOUND;
        } else {
            account.setAccountName(accountPb.getAccountName());
            accountRepo.save(account);
        }

        Set<String> deptIdsToUpdate = new HashSet<>();
        // 统计新增部门
        List<IcisAccountDepartmentPB> newDeptAccountPbs = new ArrayList<>();
        // 统计修改的部门
        List<Map.Entry<RbacDepartmentAccount, Integer>> changedDeptAccounts = new ArrayList<>();
        for (IcisAccountDepartmentPB acctDeptPb : accountPb.getDepartmentList()) {
            StatusCode statusCode = checkDepartmentAndRole(
                acctDeptPb.getDeptId(), acctDeptPb.getRoleId());
            if (statusCode != StatusCode.OK) return statusCode;
            // 用于追踪被删除的部门
            deptIdsToUpdate.add(acctDeptPb.getDeptId());

            RbacDepartmentAccount deptAccount = deptAccountRepo.findById(
                new RbacDepartmentAccountId(acctDeptPb.getDeptId(), accountId)
            ).orElse(null);
            if (deptAccount == null) {
                // 用于追踪新增的部门
                newDeptAccountPbs.add(acctDeptPb);
            } else if (deptAccount.getPrimaryRoleId() != acctDeptPb.getRoleId()) {
                // 用于追踪修改的部门
                changedDeptAccounts.add(Map.entry(deptAccount, acctDeptPb.getRoleId()));
            }
        }

        // 统计删除部门
        List<RbacDepartmentAccount> deptAccountsToDelete = new ArrayList<>();
        for (RbacDepartmentAccount deptAccount : deptAccountRepo.findByIdAccountId(accountId)) {
            if (!deptIdsToUpdate.contains(deptAccount.getId().getDeptId())) {
                deptAccountsToDelete.add(deptAccount);
            }
        }

        // 新增
        for (IcisAccountDepartmentPB acctDeptPb : newDeptAccountPbs) {
            StatusCode statusCode = addDepartmentAccount(
                acctDeptPb.getDeptId(), accountId, acctDeptPb.getRoleId()
            );
            if (statusCode != StatusCode.OK) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return statusCode;
            }
        }
        log.info("Account updated, dept added: {}, by {}", newDeptAccountPbs, modifiedBy);

        // 更新
        for (Map.Entry<RbacDepartmentAccount, Integer> entry : changedDeptAccounts) {
            StatusCode statusCode = updateDepartmentAccount(entry.getKey(), entry.getValue());
            if (statusCode != StatusCode.OK) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return statusCode;
            }
        }
        log.info("Account updated, dept changed: {}, by {}", changedDeptAccounts, modifiedBy);

        // 删除
        for (RbacDepartmentAccount deptAccount : deptAccountsToDelete) {
            deleteDepartmentAccount(deptAccount.getId().getDeptId(), accountId, modifiedBy);
        }
        log.info("Account updated, dept deleted: {}, by {}", deptAccountsToDelete, modifiedBy);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode deleteAccount(String accountId, String deptId, String modifiedBy) {
        Account accountInfo = accountInfoRepo
            .findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
        if (accountInfo == null) {
            log.error("Account not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }

        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }

        // 删除账号部门关联
        if (!StrUtils.isBlank(deptId)) {
            deleteDepartmentAccount(deptId, accountId, modifiedBy);
            log.info("Account deleted: {} from dept {}, by {}", accountId, deptId, modifiedBy);
        } else {
            // 删除RbacDepartmentAccountRole
            deptAccountRoleRepo.deleteByIdAccountId(accountId);
            // 删除RbacDepartmentAccountPermission
            deptAccountPermRepo.deleteByIdAccountId(accountId);
            // 删除RbacDepartmentAccount
            deptAccountRepo.deleteByIdAccountId(accountId);

            // 删除账号角色
            accountRoleRepo.deleteByIdAccountId(accountId);
            deleteRawDepartmentAccount(accountId, null, modifiedBy);

            // 删除账号
            accountInfo.setIsDeleted(true);
            accountInfo.setDeletedBy(modifiedBy);
            accountInfo.setDeletedAt(TimeUtils.getNowUtc());
            accountInfoRepo.save(accountInfo);

            accountRepo.delete(account);
            log.info("Account deleted: {}, by {}", accountId, modifiedBy);
        }

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode changePassword(String accountId, String oldPassword, String newPassword) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
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
            log.error("RbacAccount password mismatch: {}", accountId);
            return StatusCode.WRONG_PASSWORD;
        }

        String newPasswordHash = matchesRawInput
            ? RsaUtils.encodePassword(newPassword, encoder)
            : encoder.encode(newPassword);
        account.setPasswordHash(newPasswordHash);
        accountRepo.save(account);

        log.info("RbacAccount password changed: {}", accountId);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode resetPassword(String accountId, String modifiedBy, Integer adminRoleId) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }

        List<RbacAccountRole> modifierRoles = accountRoleRepo.findByIdAccountId(modifiedBy)
            .stream().filter(accountRole -> accountRole.getId().getRoleId().equals(adminRoleId))
            .toList();
        if (modifierRoles.isEmpty()) {
            log.error("RbacAccount not admin: {}", modifiedBy);
            return StatusCode.NO_PERMISSION_TO_OPERATE;
        }


        account.setPasswordHash(RsaUtils.encodePassword(accountId, encoder));
        accountRepo.save(account);

        log.info("RbacAccount password reset: {}", accountId);

        return StatusCode.OK;
    }

    private StatusCode checkAddAccount(String accountId) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account != null) {
            log.error("RbacAccount already exists: {}", accountId);
            return StatusCode.ACCOUNT_ALREADY_EXISTS;
        }

        Account accountInfo = accountInfoRepo
            .findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
        if (accountInfo != null) {
            log.error("Account already exists: {}", accountId);
            return StatusCode.ACCOUNT_ALREADY_EXISTS;
        }

        return StatusCode.OK;
    }

    private void addAccountImpl(IcisAccountPB accountPb, String modifiedBy) {
        RbacAccount account = new RbacAccount();
        account.setAccountId(accountPb.getAccountId());
        account.setAccountName(accountPb.getAccountName());
        String newPasswdHash = StrUtils.isBlank(accountPb.getPassword()) ?
            RsaUtils.encodePassword(accountPb.getAccountId(), encoder) :
            encoder.encode(accountPb.getPassword()/*前端已经加密并做了base64编码*/);
        account.setPasswordHash(newPasswdHash);
        accountRepo.save(account);

        Account accountInfo = new Account();
        accountInfo.setAccountId(accountPb.getAccountId());
        accountInfo.setName(accountPb.getAccountName());
        accountInfo.setGender(accountPb.getGender());
        final LocalDateTime dateOfBirth = StrUtils.isBlank(accountPb.getDateOfBirthIso8601()) ?
            null : TimeUtils.fromIso8601String(accountPb.getDateOfBirthIso8601(), "UTC");
        if (dateOfBirth != null) {
            accountInfo.setDateOfBirth(dateOfBirth);
        }
        if (!StrUtils.isBlank(accountPb.getTitle())) accountInfo.setTitle(accountPb.getTitle());
        if (!StrUtils.isBlank(accountPb.getEducationLevel()))
            accountInfo.setEducationLevel(accountPb.getEducationLevel());
        if (!StrUtils.isBlank(accountPb.getPhone())) accountInfo.setPhone(accountPb.getPhone());
        if (!StrUtils.isBlank(accountPb.getIdCardNumber()))
            accountInfo.setIdCardNumber(accountPb.getIdCardNumber());
        if (!StrUtils.isBlank(accountPb.getSignPic()))
            accountInfo.setSignPic(accountPb.getSignPic());
        accountInfo.setIsDeleted(false);
        accountInfo.setModifiedBy(modifiedBy);
        accountInfo.setModifiedAt(TimeUtils.getNowUtc());
        accountInfoRepo.save(accountInfo);
    }

    // 科室：维护业务科室与 RBAC 科室，并在创建成功后初始化血气参数。
    public StatusCode addDepartment(DepartmentPB deptPb) {
        return addDepartment(
            deptPb.getId(), deptPb.getName(), deptPb.getAbbreviation(),
            deptPb.getWardCode(), deptPb.getWardName(), deptPb.getHospitalName(),
            "System"/*modified by*/
        );
    }

    public StatusCode addDepartment(
        String deptId, String deptName, String abbreviation,
        String wardCode, String wardName, String hospitalName,
        String modifiedBy
    ) {
        StatusCode statusCode = lockExecutor.execute(
            List.of(LockKeys.department(deptId)), () -> addDepartmentInTransaction(
                deptId, deptName, abbreviation, wardCode, wardName, hospitalName, modifiedBy));
        if (statusCode == StatusCode.OK) bgaMetaService.initBgaParams(deptId);
        return statusCode;
    }

    private StatusCode addDepartmentInTransaction(
        String deptId, String deptName, String abbreviation,
        String wardCode, String wardName, String hospitalName,
        String modifiedBy
    ) {
        // 处理部门信息表
        Department deptInfo = deptInfoRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (deptInfo != null) {
            log.error("Department id already exists: {}", deptId);
            return StatusCode.DEPARTMENT_ALREADY_EXISTS;
        }
        deptInfo = deptInfoRepo.findByNameAndIsDeletedFalse(deptName).orElse(null);
        if (deptInfo != null) {
            log.error("Department name already exists: {}", deptName);
            return StatusCode.DEPARTMENT_ALREADY_EXISTS;
        }

        deptInfo = new Department();
        deptInfo.setDeptId(deptId);
        deptInfo.setName(deptName);
        deptInfo.setAbbreviation(abbreviation);
        deptInfo.setWardCode(wardCode);
        deptInfo.setWardName(wardName);
        deptInfo.setHospitalName(hospitalName);
        deptInfo.setIsDeleted(false);
        deptInfo.setModifiedBy(modifiedBy);
        deptInfo.setModifiedAt(TimeUtils.getNowUtc());
        deptInfo = deptInfoRepo.save(deptInfo);
        log.info("Department added: {}, by {}", deptInfo, modifiedBy);

        // 处理部门表
        addOrUpdateRbacDept(deptId, deptName);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode updateDepartment(
        Integer deptIntegerId, String deptId, String deptName,
        String abbreviation, String wardCode, String wardName,
        String hospitalName, String modifiedBy
    ) {
        Department deptInfo = deptInfoRepo.findByIdAndIsDeletedFalse(deptIntegerId).orElse(null);
        if (deptInfo == null) {
            log.error("Department not found: {}", deptIntegerId);
            return StatusCode.DEPARTMENT_NOT_FOUND;
        }

        deptInfo.setDeptId(deptId);
        deptInfo.setName(deptName);
        deptInfo.setAbbreviation(abbreviation);
        deptInfo.setWardCode(wardCode);
        deptInfo.setWardName(wardName);
        deptInfo.setHospitalName(hospitalName);
        deptInfo.setModifiedBy(modifiedBy);
        deptInfo.setModifiedAt(TimeUtils.getNowUtc());
        deptInfoRepo.save(deptInfo);
        log.info("Department updated: {}, by {}", deptInfo, modifiedBy);

        // 处理部门表
        addOrUpdateRbacDept(deptId, deptName);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode deleteDepartment(String deptId, String modifiedBy) {
        Department deptInfo = deptInfoRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (deptInfo == null) {
            log.error("Department not found: {}", deptId);
            return StatusCode.DEPARTMENT_NOT_FOUND;
        }

        if (deptInfoRepo.findByIsDeletedFalse().size() <= 1) {
            log.error("At least one department should be kept");
            return StatusCode.DEPARTMENT_AT_LEAST_ONE;
        }

        deptInfo.setIsDeleted(true);
        deptInfo.setDeletedBy(modifiedBy);
        deptInfo.setDeletedAt(TimeUtils.getNowUtc());
        deptInfoRepo.save(deptInfo);
        log.info("Department deleted: {}, by {}", deptInfo, modifiedBy);

        return StatusCode.OK;
    }

    private void addOrUpdateRbacDept(String deptId, String deptName) {
        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            dept = new RbacDepartment();
            dept.setDeptId(deptId);
        }
        dept.setDeptName(deptName);
        deptRepo.save(dept);
    }

    // 账号科室关系：维护业务关联、RBAC 关联及科室内角色。
    @Transactional
    public StatusCode addDepartmentAccount(DepartmentAccountPB deptAccountPb) {
        return addDepartmentAccount(
            deptAccountPb.getDepartmentId(), deptAccountPb.getAccountId(),
            deptAccountPb.getPrimaryRoleId()
        );
    }

    @Transactional
    public StatusCode addDepartmentAccountRole(DepartmentAccountRolePB deptAccountRolePb) {
        String deptId = deptAccountRolePb.getDepartmentId();
        final String accountId = deptAccountRolePb.getAccountId();

        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            log.error("RbacDepartment not found: deptId={}", deptId);
            return StatusCode.DEPARTMENT_NOT_FOUND;
        }

        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }

        List<RbacDepartmentAccountRole> deptAccountRoles = new ArrayList<>();
        for (Integer roleId : deptAccountRolePb.getRoleIdList()) {
            RbacRole role = roleRepo.findById(roleId).orElse(null);
            if (role == null) {
                log.error("RbacRole not found: {}", roleId);
                return StatusCode.ROLE_NOT_FOUND;
            }

            RbacDepartmentAccountRoleId deptAccountRoleId = new RbacDepartmentAccountRoleId(
                deptId, accountId, roleId);
            RbacDepartmentAccountRole deptAccountRole = deptAccountRoleRepo
                .findById(deptAccountRoleId).orElse(null);
            if (deptAccountRole != null) {
                log.error("RbacDepartmentAccountRole already exists: {} {} {}",
                    deptId, accountId, roleId);
                return StatusCode.DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS;
            }
            deptAccountRole = new RbacDepartmentAccountRole();
            deptAccountRole.setId(deptAccountRoleId);
            deptAccountRoles.add(deptAccountRole);
        }
        deptAccountRoleRepo.saveAll(deptAccountRoles);

        return StatusCode.OK;
    }

    private StatusCode addDepartmentAccount(
        String deptId, String accountId, Integer primaryRoleId
    ) {
        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            log.error("RbacDepartment not found: deptId={}", deptId);
            return StatusCode.DEPARTMENT_NOT_FOUND;
        }

        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }
        Account accountInfo = accountInfoRepo
            .findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
        if (accountInfo == null) {
            log.error("Account not found: {}", accountId);
            return StatusCode.ACCOUNT_NOT_FOUND;
        }

        RbacDepartmentAccountId deptAccountId = new RbacDepartmentAccountId(deptId, accountId);
        RbacDepartmentAccount deptAccount = deptAccountRepo.findById(deptAccountId).orElse(null);
        if (deptAccount != null) {
            log.error("RbacDepartmentAccount already exists: {} {}", deptId, accountId);
            return StatusCode.DEPARTMENT_ACCOUNT_ALREADY_EXISTS;
        }

        RbacRole primaryRole = roleRepo.findById(primaryRoleId).orElse(null);
        if (primaryRole == null) {
            log.error("RbacRole not found: {}", primaryRoleId);
            return StatusCode.ROLE_NOT_FOUND;
        }
        if (!primaryRole.getIsPrimary()) {
            log.error("RbacRole is not primary: {}", primaryRoleId);
            return StatusCode.ROLE_NOT_PRIMARY;
        }

        deptAccount = new RbacDepartmentAccount();
        deptAccount.setId(deptAccountId);
        deptAccount.setPrimaryRoleId(primaryRoleId);
        deptAccountRepo.save(deptAccount);
        addRawDepartmentAccount(accountInfo.getId(), accountId, deptId, primaryRoleId);

        RbacDepartmentAccountRole deptAccountRole = new RbacDepartmentAccountRole();
        RbacDepartmentAccountRoleId deptAccountRoleId = new RbacDepartmentAccountRoleId(
            deptId, accountId, primaryRole.getId());
        deptAccountRole.setId(deptAccountRoleId);
        deptAccountRoleRepo.save(deptAccountRole);

        return StatusCode.OK;
    }

    private StatusCode updateDepartmentAccount(
        RbacDepartmentAccount deptAccount, Integer newPrimaryRoleId
    ) {
        // 删除旧的RbacDepartmentAccountRole
        String accountId = deptAccount.getId().getAccountId();
        String deptId = deptAccount.getId().getDeptId();
        RbacDepartmentAccountRoleId deptAccountRoleId = new RbacDepartmentAccountRoleId(
            deptId, accountId, deptAccount.getPrimaryRoleId()
        );
        RbacDepartmentAccountRole deptAccountRole = deptAccountRoleRepo
            .findById(deptAccountRoleId).orElse(null);
        if (deptAccountRole == null) {
            log.error("RbacDepartmentAccountRole not found: {} {} {}",
                deptAccountRoleId.getDeptId(), deptAccountRoleId.getAccountId(),
                deptAccountRoleId.getRoleId());
            return StatusCode.DEPARTMENT_ACCOUNT_ROLE_NOT_FOUND;
        }
        deptAccountRoleRepo.delete(deptAccountRole);

        // 添加新的RbacDepartmentAccountRole
        deptAccountRoleId = new RbacDepartmentAccountRoleId(
            deptId, accountId, newPrimaryRoleId
        );
        deptAccountRole = deptAccountRoleRepo.findById(deptAccountRoleId).orElse(null);
        if (deptAccountRole != null) {
            log.error("RbacDepartmentAccountRole already exists: {} {} {}",
                deptAccountRoleId.getDeptId(), deptAccountRoleId.getAccountId(),
                deptAccountRoleId.getRoleId()
            );
            return StatusCode.DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS;
        }
        deptAccountRole = new RbacDepartmentAccountRole();
        deptAccountRole.setId(deptAccountRoleId);
        deptAccountRoleRepo.save(deptAccountRole);

        // 更新RbacDepartmentAccount
        deptAccount.setPrimaryRoleId(newPrimaryRoleId);
        deptAccountRepo.save(deptAccount);
        updateRawDepartmentAccount(accountId, deptId, newPrimaryRoleId);

        return StatusCode.OK;
    }

    private void deleteDepartmentAccount(String deptId, String accountId, String modifiedBy) {
        // 删除RbacDepartmentAccountRole
        deptAccountRoleRepo.deleteByIdAccountIdAndIdDeptId(accountId, deptId);
        // 删除RbacDepartmentAccountPermission
        deptAccountPermRepo.deleteByIdAccountIdAndIdDeptId(accountId, deptId);
        // 删除RbacDepartmentAccount
        deptAccountRepo.deleteById(new RbacDepartmentAccountId(deptId, accountId));

        deleteRawDepartmentAccount(accountId, deptId, modifiedBy);
    }

    private StatusCode checkDepartmentAndRole(String deptId, Integer roleId) {
        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            log.error("RbacDepartment not found: {}", deptId);
            return StatusCode.DEPARTMENT_NOT_FOUND;
        }

        RbacRole role = roleRepo.findById(roleId).orElse(null);
        if (role == null) {
            log.error("RbacRole not found: {}", roleId);
            return StatusCode.ROLE_NOT_FOUND;
        }

        return StatusCode.OK;
    }

    private void addRawDepartmentAccount(
        Long employeeId, String accountId, String deptId, Integer primaryRoleId
    ) {
        DepartmentAccount deptAcct = DepartmentAccount.builder()
            .employeeId(employeeId)
            .accountId(accountId)
            .deptId(deptId)
            .primaryRoleId(primaryRoleId)
            .startDate(TimeUtils.getNowUtc())
            .isDeleted(false)
            .build();
        deptAcctRepo.save(deptAcct);
    }

    private void updateRawDepartmentAccount(
        String accountId, String deptId, Integer primaryRoleId
    ) {
        DepartmentAccount deptAcct = deptAcctRepo.findByAccountIdAndDeptIdAndIsDeletedFalse(
            accountId, deptId).orElse(null);
        if (deptAcct == null) {
            log.error("DepartmentAccount not found: {} {}", accountId, deptId);
            return;
        }
        deptAcct.setPrimaryRoleId(primaryRoleId);
        deptAcctRepo.save(deptAcct);
    }

    private void deleteRawDepartmentAccount(String accountId, String deptId, String modifiedBy) {
        List<DepartmentAccount> deptAccts = new ArrayList<>();
        if (StrUtils.isBlank(deptId)) {
            deptAccts = deptAcctRepo.findByAccountIdAndIsDeletedFalse(accountId);
        } else {
            DepartmentAccount deptAcct = deptAcctRepo.findByAccountIdAndDeptIdAndIsDeletedFalse(
                    accountId, deptId).orElse(null);
            if (deptAcct == null) {
                log.error("DepartmentAccount not found: {} {}", accountId, deptId);
                return;
            }
            deptAccts.add(deptAcct);
        }

        for (DepartmentAccount deptAcct : deptAccts) {
            deptAcct.setIsDeleted(true);
            deptAcct.setDeletedBy(modifiedBy);
            deptAcct.setDeletedAt(TimeUtils.getNowUtc());
        }
        deptAcctRepo.saveAll(deptAccts);
    }

    // 角色权限：维护 rbac_roles_permissions。
    @Transactional
    public StatusCode addRolePermission(Integer roleId, Integer permissionId, String modifiedBy) {
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
        log.info("Role permission added: {} {}, by {}", roleId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode revokeRolePermission(
        Integer roleId, Integer permissionId, String modifiedBy
    ) {
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
        log.info("Role permission revoked: {} {}, by {}", roleId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    // 科室账号权限：维护 rbac_accounts_departments_permissions。
    @Transactional
    public StatusCode addAccountPermission(
        String accountId, String deptId, Integer permissionId, String modifiedBy
    ) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
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
            deptId, accountId, permissionId);
        RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo
            .findById(id).orElse(null);
        if (deptAccountPerm != null) {
            log.error("RbacDepartmentAccountPermission already exists: {} {} {}",
                deptId, accountId, permissionId);
            return StatusCode.OK;
        }

        deptAccountPerm = new RbacDepartmentAccountPermission();
        deptAccountPerm.setId(id);
        deptAccountPermRepo.save(deptAccountPerm);
        log.info("Account permission added: {} {} {}, by {}",
            deptId, accountId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode revokeAccountPermission(
        String accountId, String deptId, Integer permissionId, String modifiedBy
    ) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account == null) {
            log.error("RbacAccount not found: {}", accountId);
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
            deptId, accountId, permissionId);
        RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo
            .findById(id).orElse(null);
        if (deptAccountPerm == null) {
            log.error("RbacDepartmentAccountPermission not found: {} {} {}",
                deptId, accountId, permissionId);
            return StatusCode.OK;
        }

        deptAccountPermRepo.delete(deptAccountPerm);
        log.info("Account permission revoked: {} {} {}, by {}",
            deptId, accountId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    private RbacPermissionRepository permRepo;
    private RbacRoleRepository roleRepo;
    private RbacRolePermissionRepository rolePermRepo;
    private RbacAccountRepository accountRepo;
    private AccountRepository accountInfoRepo;
    private RbacAccountRoleRepository accountRoleRepo;
    private RbacDepartmentRepository deptRepo;
    private DepartmentRepository deptInfoRepo;
    private RbacDepartmentAccountRepository deptAccountRepo;
    private DepartmentAccountRepository deptAcctRepo;
    private RbacDepartmentAccountRoleRepository deptAccountRoleRepo;
    private RbacDepartmentAccountPermissionRepository deptAccountPermRepo;

    private BCryptPasswordEncoder encoder;
    private final BgaMetaService bgaMetaService;
    private final LockExecutor lockExecutor;
}
