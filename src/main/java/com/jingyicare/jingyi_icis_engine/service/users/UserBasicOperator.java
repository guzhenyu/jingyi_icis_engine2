package com.jingyicare.jingyi_icis_engine.service.users;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserBasicOperator {
    public UserBasicOperator(
        @Autowired ConfigProtoService protoService,
        @Autowired RbacPermissionRepository permRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired RbacRolePermissionRepository rolePermRepo,
        @Autowired RbacRoleRoleRepository roleRoleRepo,
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
        @Autowired MonitoringConfig monitoringConfig
    ) {
        this.ADMIN_ACCOUNT_ID = protoService.getConfig().getUser().getAdminAccountId();
        this.permRepo = permRepo;
        this.roleRepo = roleRepo;
        this.rolePermRepo = rolePermRepo;
        this.roleRoleRepo = roleRoleRepo;
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
        this.monitoringConfig = monitoringConfig;
    }

    @Transactional
    public List<IcisAccountPB> getAllAccounts(
        String queryDeptId, String queryAccountId, String queryAccountName, String zoneId
    ) {
        // 获取所有账号，及其部门，角色信息
        List<Account> accountInfoList = accountInfoRepo.findByIsDeletedFalse();

        Map<String, RbacDepartment> deptMap = new HashMap<>();
        for (RbacDepartment dept : deptRepo.findAll()) {
            deptMap.put(dept.getDeptId(), dept);
        }

        Map<String, Map<String, RbacDepartmentAccount>> deptAccountMap = new HashMap<>();
        for (RbacDepartmentAccount deptAccount : deptAccountRepo.findAll()) {
            String accountId = deptAccount.getId().getAccountId();
            String deptId = deptAccount.getId().getDeptId();
            deptAccountMap.computeIfAbsent(accountId, k -> new HashMap<>()).put(deptId, deptAccount);
        }

        Map<Integer, RbacRole> roleMap = new HashMap<>();
        for (RbacRole role : roleRepo.findAll()) {
            roleMap.put(role.getId(), role);
        }

        // 根据输入过滤，组装结果
        List<IcisAccountPB> accountPbs = new ArrayList<>();
        for (Account accountInfo : accountInfoList) {
            String accountId = accountInfo.getAccountId();
            if (!StrUtils.isBlank(queryAccountId) && !accountId.equals(queryAccountId)) continue;
            if (!StrUtils.isBlank(queryAccountName) && !accountInfo.getName().contains(queryAccountName)) continue;

            // 组装账号基本信息
            IcisAccountPB.Builder accountPbBuilder = IcisAccountPB.newBuilder()
                .setAccountId(accountId)
                .setAccountName(accountInfo.getName())
                .setId(accountInfo.getId());
            if (accountInfo.getGender() != null) {
                accountPbBuilder.setGender(accountInfo.getGender());
            }
            if (accountInfo.getDateOfBirth() != null) {
                accountPbBuilder.setDateOfBirthIso8601(
                    TimeUtils.toIso8601String(accountInfo.getDateOfBirth(), zoneId)
                );
            }
            if (!StrUtils.isBlank(accountInfo.getTitle())) {
                accountPbBuilder.setTitle(accountInfo.getTitle());
            }
            if (!StrUtils.isBlank(accountInfo.getEducationLevel())) {
                accountPbBuilder.setEducationLevel(accountInfo.getEducationLevel());
            }
            if (!StrUtils.isBlank(accountInfo.getPhone())) {
                accountPbBuilder.setPhone(accountInfo.getPhone());
            }
            if (!StrUtils.isBlank(accountInfo.getIdCardNumber())) {
                accountPbBuilder.setIdCardNumber(accountInfo.getIdCardNumber());
            }
            if (!StrUtils.isBlank(accountInfo.getSignPic())) {
                accountPbBuilder.setSignPic(accountInfo.getSignPic());
            }

            // 组装部门相关信息
            Map<String, RbacDepartmentAccount> deptAccountMapForAccount = deptAccountMap.get(accountId);
            if (!StrUtils.isBlank(queryDeptId) &&
                (deptAccountMapForAccount == null || !deptAccountMapForAccount.containsKey(queryDeptId))
            ) {
                continue;
            }

            if (deptAccountMapForAccount == null) {
                accountPbs.add(accountPbBuilder.build());
                continue;
            }

            List<IcisAccountDepartmentPB> accountDeptPbs = new ArrayList<>();
            for (Map.Entry<String, RbacDepartmentAccount> entry : deptAccountMapForAccount.entrySet()) {
                String deptId = entry.getKey();
                RbacDepartment dept = deptMap.get(deptId);
                if (dept == null) {
                    log.error("RbacDepartment not found: {}", deptId);
                    continue;
                }

                RbacDepartmentAccount deptAccount = entry.getValue();
                Integer primaryRoleId = deptAccount.getPrimaryRoleId();
                RbacRole primaryRole = roleMap.get(primaryRoleId);
                if (primaryRole == null) {
                    log.error("RbacRole not found: {}", primaryRoleId);
                    continue;
                }

                accountDeptPbs.add(IcisAccountDepartmentPB.newBuilder()
                    .setDeptId(deptId)
                    .setDeptName(dept.getDeptName())
                    .setRoleId(primaryRole.getId())
                    .setRoleName(primaryRole.getName())
                    .build()
                );
            }
            accountDeptPbs = accountDeptPbs.stream().sorted(Comparator.comparing(IcisAccountDepartmentPB::getDeptId)).toList();
            accountPbs.add(accountPbBuilder.addAllDepartment(accountDeptPbs).build());
        }
        return accountPbs.stream().sorted(Comparator.comparing(IcisAccountPB::getAccountId)).toList();
    }

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
            statusCode = addDepartmentAccount(acctDeptPb.getDeptId(), accountId, acctDeptPb.getRoleId());
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

        Account accountInfo = accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
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
            StatusCode statusCode = checkDepartmentAndRole(acctDeptPb.getDeptId(), acctDeptPb.getRoleId());
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
        Account accountInfo = accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
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

        if (!encoder.matches(oldPassword, account.getPasswordHash())) {
            log.error("RbacAccount password mismatch: {}", accountId);
            return StatusCode.WRONG_PASSWORD;
        }

        account.setPasswordHash(RsaUtils.encodePassword(newPassword, encoder));
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

    public RbacAccount getRbacAccount(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        return accountRepo.findByAccountId(accountId).orElse(null);
    }

    public Account getAccount(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        return accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
    }

    public Account getAccountByAutoId(String accountAutoIdStr) {
        if (accountAutoIdStr == null) return null;
        Long accountAutoId = null;
        try {
            accountAutoId = Long.parseLong(accountAutoIdStr);
        } catch (NumberFormatException e) {
            log.error("Invalid account auto ID: {}", accountAutoIdStr);
            return null;
        }
        return accountInfoRepo.findByIdAndIsDeletedFalse(accountAutoId).orElse(null);
    }

    public List<IcisDepartmentPB> getAllDepartments() {
        return deptInfoRepo.findByIsDeletedFalse().stream()
            .map(deptInfo -> IcisDepartmentPB.newBuilder()
                .setId(deptInfo.getId())
                .setDeptId(deptInfo.getDeptId())
                .setDeptName(deptInfo.getName())
                .setAbbreviation(deptInfo.getAbbreviation())
                .setWardCode(deptInfo.getWardCode())
                .setWardName(deptInfo.getWardName())
                .setHospitalName(deptInfo.getHospitalName())
                .build())
            .sorted(Comparator.comparing(IcisDepartmentPB::getId))
            .toList();
    }

    @Transactional
    public StatusCode addDepartment(DepartmentPB deptPb) {
        return addDepartment(
            deptPb.getId(), deptPb.getName(), deptPb.getAbbreviation(),
            deptPb.getWardCode(), deptPb.getWardName(), deptPb.getHospitalName(),
            "System"/*modified by*/
        );
    }

    @Transactional
    public StatusCode addDepartment(
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

        // 初始化部门
        monitoringConfig.getBgaParamList(deptId);

        return StatusCode.OK;
    }

    @Transactional
    public Integer getDepartmentId(String deptId) {
        Department deptInfo = deptInfoRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (deptInfo == null) {
            log.error("Department not found: {}", deptId);
            return null;
        }
        return deptInfo.getId();
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

    @Transactional
    public StatusCode addDepartmentAccount(DepartmentAccountPB deptAccountPb) {
        return addDepartmentAccount(
            deptAccountPb.getDepartmentId(), deptAccountPb.getAccountId(),
            deptAccountPb.getPrimaryRoleId()
        );
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
        Account accountInfo = accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
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

    private StatusCode updateDepartmentAccount(RbacDepartmentAccount deptAccount, Integer newPrimaryRoleId) {
        // 删除旧的RbacDepartmentAccountRole
        String accountId = deptAccount.getId().getAccountId();
        String deptId = deptAccount.getId().getDeptId();
        RbacDepartmentAccountRoleId deptAccountRoleId = new RbacDepartmentAccountRoleId(
            deptId, accountId, deptAccount.getPrimaryRoleId()
        );
        RbacDepartmentAccountRole deptAccountRole = deptAccountRoleRepo.findById(deptAccountRoleId).orElse(null);
        if (deptAccountRole == null) {
            log.error("RbacDepartmentAccountRole not found: {} {} {}", deptAccountRoleId.getDeptId(), deptAccountRoleId.getAccountId(), deptAccountRoleId.getRoleId());
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
                deptAccountRoleId.getDeptId(), deptAccountRoleId.getAccountId(), deptAccountRoleId.getRoleId()
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

    @Transactional
    List<Department> getAllDeptInfo() {
        return deptInfoRepo.findByIsDeletedFalse();
    }

    @Transactional
    public List<Department> getAllDeptInfoByAccountId(String accountId) {
        List<String> deptIds = deptAccountRepo.findByIdAccountId(accountId).stream()
            .map(deptAccount -> deptAccount.getId().getDeptId())
            .toList();
        return deptInfoRepo.findByDeptIdInAndIsDeletedFalse(deptIds);
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

            RbacDepartmentAccountRoleId deptAccountRoleId = new RbacDepartmentAccountRoleId(deptId, accountId, roleId);
            RbacDepartmentAccountRole deptAccountRole = deptAccountRoleRepo.findById(deptAccountRoleId).orElse(null);
            if (deptAccountRole != null) {
                log.error("RbacDepartmentAccountRole already exists: {} {} {}", deptId, accountId, roleId);
                return StatusCode.DEPARTMENT_ACCOUNT_ROLE_ALREADY_EXISTS;
            }
            deptAccountRole = new RbacDepartmentAccountRole();
            deptAccountRole.setId(deptAccountRoleId);
            deptAccountRoles.add(deptAccountRole);
        }
        deptAccountRoleRepo.saveAll(deptAccountRoles);

        return StatusCode.OK;
    }

    @Transactional
    public List<IcisRolePB> getPrimaryRoles() {
        return roleRepo.findByIsPrimaryTrue().stream()
            .map(role -> IcisRolePB.newBuilder()
                .setId(role.getId())
                .setName(role.getName())
                .build())
            .sorted(Comparator.comparing(IcisRolePB::getId))
            .toList();
    }

    @Transactional
    public Boolean departmentExists(String deptId) {
        return deptRepo.existsByDeptId(deptId);
    }

    /*
        => List<nonPrimaryRoleId, roleName> => List<nonPrimaryRoleId> => List<nonPrimaryRoleId, permissionId> => Map<nonPrimaryRoleId, List<permissionId>> ===============> List<IcisPermissionGroupPB>
           (groupRoles)                        (groupRoleIds)            (groupRolePermissions)                  roleIdToPermissionIds                                      permissionGroups
                                                                                      ||                                                                                              ^
                                                                                      |=> Set<nonPrimaryRolePermissionId> => Map<permissionId, List<primaryRoleId>>                  / \
                                                                                          (permissionIds)                            (permissionIdToRoleIds)                          ||
                                                                                            ||    ||                                                    ||                            ||
                                                                                            ||    |=> Map<permissionId, List<accountId>>=======|        ||                            ||
                                                                                            ||        (permissionIdToAccountIds)              ||        ||                            ||
                                                                                            ||                                                ||        ||                            ||
                                                                                            |=> Map<permissionId, RbacPermission>             ||        ||                            ||
                                                                                                (allPermissions)                              ||        ||                            ||
                                                                                                    ||                                        ||        ||                            ||
                                                                                                    |=> IcisPermissionPB.id(permissionId)     ||        ||                            ||
                                                                                                            ||          .name(permissionName) ||        ||                            ||
                                                                                                            ||          .roleList             ||    <====|                            ||
                                                                                                            ||          .accountIdList     <===|                                      ||
                                                                                                            ||                                                                        ||
                                                                                                            |==========================================================================|
    */
    @Transactional
    public List<IcisPermissionGroupPB> getNonPrimaryRolePermissions(String deptId) {
        // 在RbacRole中找到所有非主要角色，及其对应的权限
        List<RbacRole> groupRoles = roleRepo.findByIsPrimaryFalse();
        List<Integer> groupRoleIds = groupRoles.stream().map(RbacRole::getId).toList();

        List<RbacRolePermission> groupRolePermissions = rolePermRepo.findByIdRoleIdIn(groupRoleIds);
        Map<Integer, List<Integer>> roleIdToPermissionIds = groupRolePermissions.stream()
            .collect(Collectors.groupingBy(
                rolePermission -> rolePermission.getId().getRoleId(),
                Collectors.mapping(rolePermission -> rolePermission.getId().getPermissionId(), Collectors.toList())
            ));

        // 获取权限对应的主要角色
        Set<Integer> permissionIds = groupRolePermissions.stream()
            .map(rolePermission -> rolePermission.getId().getPermissionId())
            .collect(Collectors.toSet());
        Map<Integer, RbacPermission> allPermissions = permRepo.findByIdIn(new ArrayList<>(permissionIds))
            .stream().collect(Collectors.toMap(RbacPermission::getId, permission -> permission));
        Map<Integer, List<Integer>> permissionIdToRoleIds = rolePermRepo
            .findByIdPermissionIdIn(new ArrayList<>(permissionIds))
            .stream()
            .collect(Collectors.groupingBy(
                rolePermission -> rolePermission.getId().getPermissionId(),
                Collectors.mapping(rolePermission -> rolePermission.getId().getRoleId(), Collectors.toList())
            ));
        Map<Integer, RbacRole> allRoles = roleRepo.findAll().stream()
            .collect(Collectors.toMap(RbacRole::getId, role -> role));

        // 获取权限对应的部门用户
        Map<Integer, List<String>> permissionIdToAccountIds = deptAccountPermRepo
            .findByIdDeptIdAndIdPermissionIdIn(deptId, new ArrayList<>(permissionIds))
            .stream()
            .collect(Collectors.groupingBy(
                deptAccountPermission -> deptAccountPermission.getId().getPermissionId(),
                Collectors.mapping(deptAccountPermission -> deptAccountPermission.getId().getAccountId(), Collectors.toList())
            ));
        Map<String, RbacAccount> allAccounts = accountRepo.findAll().stream()
            .collect(Collectors.toMap(RbacAccount::getAccountId, account -> account));

        // 组装IcisPermissionPB
        Map<Integer, IcisPermissionPB> permissionMap = new HashMap<>();
        for (Map.Entry<Integer, RbacPermission> entry : allPermissions.entrySet()) {
            Integer permissionId = entry.getKey();
            RbacPermission permission = entry.getValue();

            // 设置权限id和name
            IcisPermissionPB.Builder permissionBuilder = IcisPermissionPB.newBuilder()
                .setId(permissionId)
                .setName(permission.getName());

            // 设置角色
            List<IcisRolePB> rolePbs = new ArrayList<>();
            List<Integer> roleIds = permissionIdToRoleIds.getOrDefault(permissionId, new ArrayList<>());
            for (Integer roleId : roleIds) {
                RbacRole role = allRoles.get(roleId);
                if (role != null && role.getIsPrimary()) {
                    rolePbs.add(IcisRolePB.newBuilder()
                        .setId(role.getId())
                        .setName(role.getName())
                        .build());
                }
            }
            permissionBuilder.addAllRole(
                rolePbs.stream().sorted(Comparator.comparing(IcisRolePB::getId)).toList()
            );

            // 设置部门用户
            List<StringIdEntityPB> accountPbs = new ArrayList<>();
            List<String> accountIds = permissionIdToAccountIds.getOrDefault(permissionId, new ArrayList<>());
            for (String accountId : accountIds) {
                RbacAccount account = allAccounts.get(accountId);
                if (account != null) {
                    accountPbs.add(StringIdEntityPB.newBuilder()
                        .setId(account.getAccountId())
                        .setName(account.getAccountName())
                        .build());
                }
            }
            permissionBuilder.addAllAccountId(
                accountPbs.stream().sorted(Comparator.comparing(StringIdEntityPB::getId)).toList()
            );
            permissionMap.put(permissionId, permissionBuilder.build());
        }

        // 组装IcisPermissionGroupPB
        List<IcisPermissionGroupPB> permissionGroups = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> entry : roleIdToPermissionIds.entrySet()) {
            Integer roleId = entry.getKey();
            List<Integer> groupPermIds = entry.getValue();

            RbacRole role = allRoles.get(roleId);
            if (role == null) {
                log.error("RbacRole not found: {}", roleId);
                continue;
            }

            // 组装分组id和name
            IcisPermissionGroupPB.Builder permissionGroupBuilder = IcisPermissionGroupPB.newBuilder()
                .setId(roleId)
                .setName(role.getName());

            // 组装权限
            List<IcisPermissionPB> permissions = new ArrayList<>();
            for (Integer permissionId : groupPermIds) {
                IcisPermissionPB permission = permissionMap.get(permissionId);
                if (permission == null) {
                    log.error("RbacPermission not found: {}", permissionId);
                    continue;
                }
                permissions.add(permission);
            }
            permissionGroupBuilder.addAllPermission(
                permissions.stream().sorted(Comparator.comparing(IcisPermissionPB::getId)).toList()
            );

            permissionGroups.add(permissionGroupBuilder.build());
        }

        return permissionGroups;
    }

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
    public StatusCode revokeRolePermission(Integer roleId, Integer permissionId, String modifiedBy) {
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

    @Transactional
    public StatusCode addAccountPermission(String accountId, String deptId, Integer permissionId, String modifiedBy) {
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

        RbacDepartmentAccountPermissionId id = new RbacDepartmentAccountPermissionId(deptId, accountId, permissionId);
        RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo.findById(id).orElse(null);
        if (deptAccountPerm != null) {
            log.error("RbacDepartmentAccountPermission already exists: {} {} {}", deptId, accountId, permissionId);
            return StatusCode.OK;
        }

        deptAccountPerm = new RbacDepartmentAccountPermission();
        deptAccountPerm.setId(id);
        deptAccountPermRepo.save(deptAccountPerm);
        log.info("Account permission added: {} {} {}, by {}", deptId, accountId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    @Transactional
    public StatusCode revokeAccountPermission(String accountId, String deptId, Integer permissionId, String modifiedBy) {
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

        RbacDepartmentAccountPermissionId id = new RbacDepartmentAccountPermissionId(deptId, accountId, permissionId);
        RbacDepartmentAccountPermission deptAccountPerm = deptAccountPermRepo.findById(id).orElse(null);
        if (deptAccountPerm == null) {
            log.error("RbacDepartmentAccountPermission not found: {} {} {}", deptId, accountId, permissionId);
            return StatusCode.OK;
        }

        deptAccountPermRepo.delete(deptAccountPerm);
        log.info("Account permission revoked: {} {} {}, by {}", deptId, accountId, permissionId, modifiedBy);

        return StatusCode.OK;
    }

    public Set<Integer> getPermissionIds(String accountId, String deptId) {
        Set<Integer> permIds = new HashSet<>();

        // 获取权限
        List<RbacDepartmentAccountPermission> deptAccountPerms = deptAccountPermRepo
            .findByIdAccountIdAndIdDeptId(accountId, deptId);
        for (RbacDepartmentAccountPermission deptAccountPerm : deptAccountPerms) {
            permIds.add(deptAccountPerm.getId().getPermissionId());
        }

        // 获取角色权限
        Set<Integer> roleIds = new HashSet<>();
        List<RbacDepartmentAccountRole> deptAccountRoles = deptAccountRoleRepo
            .findByIdAccountIdAndIdDeptId(accountId, deptId);
        for (RbacDepartmentAccountRole deptAccountRole : deptAccountRoles) {
            roleIds.add(deptAccountRole.getId().getRoleId());
        }

        Queue<Integer> roleQueue = new LinkedList<>(roleIds);
        while (!roleQueue.isEmpty()) {
            List<Integer> parentRoleIds = roleQueue.stream().toList();
            roleQueue.clear();

            List<RbacRoleRole> roleRoles = roleRoleRepo.findByIdParentRoleIdIn(parentRoleIds);
            for (RbacRoleRole roleRole : roleRoles) {
                Integer childRoleId = roleRole.getId().getChildRoleId();
                if (!roleIds.contains(childRoleId)) {
                    roleIds.add(childRoleId);
                    roleQueue.add(childRoleId);
                }
            }
        }

        // 获取角色权限
        List<RbacRolePermission> rolePerms = rolePermRepo.findByIdRoleIdIn(roleIds.stream().toList());
        for (RbacRolePermission rolePerm : rolePerms) {
            permIds.add(rolePerm.getId().getPermissionId());
        }

        return permIds;
    }

    @Transactional
    public String getPrimaryRoleName(String accountId, String deptId) {
        if (ADMIN_ACCOUNT_ID.equals(accountId)) return Consts.ADMIN_ROLE;
        RbacDepartmentAccount deptAccount = deptAccountRepo.findById(new RbacDepartmentAccountId(deptId, accountId)).orElse(null);
        if (deptAccount == null) {
            log.error("RbacDepartmentAccount not found: {} {}", deptId, accountId);
            return Consts.VOID_ROLE;
        }

        RbacRole primaryRole = roleRepo.findById(deptAccount.getPrimaryRoleId()).orElse(null);
        if (primaryRole == null) {
            log.error("RbacRole not found: {} {}", deptAccount.getPrimaryRoleId(), accountId);
            return Consts.VOID_ROLE;
        }

        return primaryRole.getName();
    }

    private StatusCode checkAddAccount(String accountId) {
        RbacAccount account = accountRepo.findByAccountId(accountId).orElse(null);
        if (account != null) {
            log.error("RbacAccount already exists: {}", accountId);
            return StatusCode.ACCOUNT_ALREADY_EXISTS;
        }

        Account accountInfo = accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
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
        if (!StrUtils.isBlank(accountPb.getEducationLevel())) accountInfo.setEducationLevel(accountPb.getEducationLevel());
        if (!StrUtils.isBlank(accountPb.getPhone())) accountInfo.setPhone(accountPb.getPhone());
        if (!StrUtils.isBlank(accountPb.getIdCardNumber())) accountInfo.setIdCardNumber(accountPb.getIdCardNumber());
        if (!StrUtils.isBlank(accountPb.getSignPic())) accountInfo.setSignPic(accountPb.getSignPic());
        accountInfo.setIsDeleted(false);
        accountInfo.setModifiedBy(modifiedBy);
        accountInfo.setModifiedAt(TimeUtils.getNowUtc());
        accountInfoRepo.save(accountInfo);
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

    private void addOrUpdateRbacDept(String deptId, String deptName) {
        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            dept = new RbacDepartment();
            dept.setDeptId(deptId);
        }
        dept.setDeptName(deptName);
        deptRepo.save(dept);
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

    private void addRawDepartmentAccount(Long employeeId, String accountId, String deptId, Integer primaryRoleId) {
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

    private void updateRawDepartmentAccount(String accountId, String deptId, Integer primaryRoleId) {
        DepartmentAccount deptAcct = deptAcctRepo.findByAccountIdAndDeptIdAndIsDeletedFalse(
            accountId, deptId).orElse(null);
        if (deptAcct == null) {
            log.error("DepartmentAccount not found: {} {}", accountId, deptId);
            return;
        }
        deptAcct.setPrimaryRoleId(primaryRoleId);
        deptAcctRepo.save(deptAcct);
    }

    private final String ADMIN_ACCOUNT_ID;

    private RbacPermissionRepository permRepo;
    private RbacRoleRepository roleRepo;
    private RbacRolePermissionRepository rolePermRepo;
    private RbacRoleRoleRepository roleRoleRepo;
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
    private MonitoringConfig monitoringConfig;
}