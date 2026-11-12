package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class UserQuery {
    public UserQuery(
        Config config,
        RbacAccountRepository accountRepo,
        AccountRepository accountInfoRepo,
        RbacDepartmentRepository deptRepo,
        DepartmentRepository deptInfoRepo,
        RbacDepartmentAccountRepository deptAccountRepo,
        RbacRoleRepository roleRepo,
        RbacDepartmentAccountPermissionRepository deptAccountPermRepo,
        RbacDepartmentAccountRoleRepository deptAccountRoleRepo,
        RbacRoleRoleRepository roleRoleRepo,
        RbacRolePermissionRepository rolePermRepo,
        RbacPermissionRepository permRepo,
        PlatformTransactionManager transactionManager
    ) {
        this.ADMIN_ACCOUNT_ID = config.getUser().getAdminAccountId();
        this.ZONE_ID = config.getZoneId();
        this.accountRepo = accountRepo;
        this.accountInfoRepo = accountInfoRepo;
        this.deptRepo = deptRepo;
        this.deptInfoRepo = deptInfoRepo;
        this.deptAccountRepo = deptAccountRepo;
        this.roleRepo = roleRepo;
        this.deptAccountPermRepo = deptAccountPermRepo;
        this.deptAccountRoleRepo = deptAccountRoleRepo;
        this.roleRoleRepo = roleRoleRepo;
        this.rolePermRepo = rolePermRepo;
        this.permRepo = permRepo;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
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

        RbacAccount rbacAccount = getRbacAccount(accountId);
        if (rbacAccount == null) return null;
        Pair<String, String> fallbackAcctPair = new Pair<>(accountId, rbacAccount.getAccountName());

        Account account = getAccount(accountId);
        if (account == null) return fallbackAcctPair;
        return new Pair<>(account.getId().toString(), account.getName());
    }

    public String getAccountAutoId() {
        Pair<String, String> account = getAccountWithAutoId();
        return account == null ? "" : account.getFirst();
    }

    public String getAccountAutoId(String accountId) {
        Account account = getAccount(accountId);
        return account == null ? accountId : account.getId().toString();
    }

    public String getNameByAutoId(String accountAutoId) {
        if (StrUtils.isBlank(accountAutoId)) return "";

        // 尝试从Account表中获取
        Account account = getAccountByAutoId(accountAutoId);
        if (account != null) {
            return account.getName();
        }

        // 如果自增 ID 未找到账号，则兼容按账号编码查询。
        account = getAccount(accountAutoId);
        if (account != null) {
            return account.getName();
        }
        return "";
    }

    public String getNameByAccountId(String accountId) {
        if (StrUtils.isBlank(accountId)) return "";

        Account account = getAccount(accountId);
        if (account != null) {
            return account.getName();
        }

        return "";
    }

    public Pair<String/*primary_role_name*/, Boolean/*has_permission*/> hasPermission(
        String deptId, Integer permissionId
    ) {
        String accountId = getCtxAccountId();
        if (StrUtils.isBlank(accountId)) return null;

        Boolean hasPermission = accountId.equals(ADMIN_ACCOUNT_ID) ||
            getPermissionIds(accountId, deptId).contains(permissionId);
        return new Pair<>(getPrimaryRoleName(accountId, deptId), hasPermission);
    }

    public boolean canCurrentUserAccessDept(String deptId) {
        String callerAccountId = getCtxAccountId();
        if (StrUtils.isBlank(callerAccountId) || StrUtils.isBlank(deptId)) return false;
        return ADMIN_ACCOUNT_ID.equals(callerAccountId)
            || accountBelongsToDept(callerAccountId, deptId);
    }

    public boolean accountBelongsToDept(String accountId, String deptId) {
        if (StrUtils.isBlank(accountId) || StrUtils.isBlank(deptId)) return false;
        return deptAccountRepo.findById(new RbacDepartmentAccountId(deptId, accountId)).isPresent();
    }

    public List<IcisAccountPB> getDeptAccounts(String deptId) {
        return getAllAccounts(deptId, "", "", ZONE_ID);
    }

    List<IcisAccountPB> getAllAccounts(
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
            deptAccountMap.computeIfAbsent(accountId, k -> new HashMap<>())
                .put(deptId, deptAccount);
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
            if (!StrUtils.isBlank(queryAccountName)
                && !accountInfo.getName().contains(queryAccountName)) continue;

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
            if (!StrUtils.isBlank(accountInfo.getCaSignPic())) {
                accountPbBuilder.setCaSignPic(accountInfo.getCaSignPic());
            }
            if (accountInfo.getIsDisabled() != null) {
                accountPbBuilder.setIsDisabled(accountInfo.getIsDisabled());
            }

            // 组装部门相关信息
            Map<String, RbacDepartmentAccount> deptAccountMapForAccount =
                deptAccountMap.get(accountId);
            if (!StrUtils.isBlank(queryDeptId) &&
                (deptAccountMapForAccount == null
                    || !deptAccountMapForAccount.containsKey(queryDeptId))
            ) {
                continue;
            }

            if (deptAccountMapForAccount == null) {
                accountPbs.add(accountPbBuilder.build());
                continue;
            }

            List<IcisAccountDepartmentPB> accountDeptPbs = new ArrayList<>();
            for (Map.Entry<String, RbacDepartmentAccount> entry
                : deptAccountMapForAccount.entrySet()) {
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
            accountDeptPbs = accountDeptPbs.stream()
                .sorted(Comparator.comparing(IcisAccountDepartmentPB::getDeptId)).toList();
            accountPbs.add(accountPbBuilder.addAllDepartment(accountDeptPbs).build());
        }
        return accountPbs.stream()
            .sorted(Comparator.comparing(IcisAccountPB::getAccountId)).toList();
    }

    RbacAccount getRbacAccount(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        return accountRepo.findByAccountId(accountId).orElse(null);
    }

    Account getAccount(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        return accountInfoRepo.findByAccountIdAndIsDeletedFalse(accountId).orElse(null);
    }

    Account getAccountByAutoId(String accountAutoIdStr) {
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

    public Integer getDepartmentId(String deptId) {
        Department deptInfo = deptInfoRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (deptInfo == null) {
            log.error("Department not found: {}", deptId);
            return null;
        }
        return deptInfo.getId();
    }

    List<Department> getAllDeptInfo() {
        return deptInfoRepo.findByIsDeletedFalse();
    }

    public List<Department> getAllDeptInfoByAccountId(String accountId) {
        List<String> deptIds = deptAccountRepo.findByIdAccountId(accountId).stream()
            .map(deptAccount -> deptAccount.getId().getDeptId())
            .toList();
        return deptInfoRepo.findByDeptIdInAndIsDeletedFalse(deptIds);
    }

    public List<IcisRolePB> getPrimaryRoles() {
        return roleRepo.findByIsPrimaryTrue().stream()
            .map(role -> IcisRolePB.newBuilder()
                .setId(role.getId())
                .setName(role.getName())
                .build())
            .sorted(Comparator.comparing(IcisRolePB::getId))
            .toList();
    }

    public Boolean departmentExists(String deptId) {
        return deptRepo.existsByDeptId(deptId);
    }

    public List<IcisPermissionGroupPB> getNonPrimaryRolePermissions(String deptId) {
        // 在RbacRole中找到所有非主要角色，及其对应的权限
        List<RbacRole> groupRoles = roleRepo.findByIsPrimaryFalse();
        List<Integer> groupRoleIds = groupRoles.stream().map(RbacRole::getId).toList();

        List<RbacRolePermission> groupRolePermissions = rolePermRepo.findByIdRoleIdIn(groupRoleIds);
        Map<Integer, List<Integer>> roleIdToPermissionIds = groupRolePermissions.stream()
            .collect(Collectors.groupingBy(
                rolePermission -> rolePermission.getId().getRoleId(),
                Collectors.mapping(rolePermission -> rolePermission.getId().getPermissionId(),
                    Collectors.toList())
            ));

        // 获取权限对应的主要角色
        Set<Integer> permissionIds = groupRolePermissions.stream()
            .map(rolePermission -> rolePermission.getId().getPermissionId())
            .collect(Collectors.toSet());
        Map<Integer, RbacPermission> allPermissions = permRepo
            .findByIdIn(new ArrayList<>(permissionIds))
            .stream().collect(Collectors.toMap(RbacPermission::getId, permission -> permission));
        Map<Integer, List<Integer>> permissionIdToRoleIds = rolePermRepo
            .findByIdPermissionIdIn(new ArrayList<>(permissionIds))
            .stream()
            .collect(Collectors.groupingBy(
                rolePermission -> rolePermission.getId().getPermissionId(),
                Collectors.mapping(rolePermission -> rolePermission.getId().getRoleId(),
                    Collectors.toList())
            ));
        Map<Integer, RbacRole> allRoles = roleRepo.findAll().stream()
            .collect(Collectors.toMap(RbacRole::getId, role -> role));

        // 获取权限对应的部门用户
        Map<Integer, List<String>> permissionIdToAccountIds = deptAccountPermRepo
            .findByIdDeptIdAndIdPermissionIdIn(deptId, new ArrayList<>(permissionIds))
            .stream()
            .collect(Collectors.groupingBy(
                deptAccountPermission -> deptAccountPermission.getId().getPermissionId(),
                Collectors.mapping(
                    deptAccountPermission -> deptAccountPermission.getId().getAccountId(),
                    Collectors.toList())
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
            List<Integer> roleIds = permissionIdToRoleIds
                .getOrDefault(permissionId, new ArrayList<>());
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
            List<String> accountIds = permissionIdToAccountIds
                .getOrDefault(permissionId, new ArrayList<>());
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
            IcisPermissionGroupPB.Builder permissionGroupBuilder = IcisPermissionGroupPB
                .newBuilder()
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

    Set<Integer> getPermissionIds(String accountId, String deptId) {
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
        List<RbacRolePermission> rolePerms = rolePermRepo
            .findByIdRoleIdIn(roleIds.stream().toList());
        for (RbacRolePermission rolePerm : rolePerms) {
            permIds.add(rolePerm.getId().getPermissionId());
        }

        return permIds;
    }

    String getPrimaryRoleName(String accountId, String deptId) {
        // 保留原主要角色查询的 REQUIRED 事务边界，内部调用也通过事务模板执行。
        return transactionTemplate.execute(status ->
            getPrimaryRoleNameInTransaction(accountId, deptId));
    }

    private String getPrimaryRoleNameInTransaction(String accountId, String deptId) {
        if (ADMIN_ACCOUNT_ID.equals(accountId)) return Consts.ADMIN_ROLE;
        RbacDepartmentAccount deptAccount = deptAccountRepo
            .findById(new RbacDepartmentAccountId(deptId, accountId)).orElse(null);
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

    private final String ADMIN_ACCOUNT_ID;
    private final String ZONE_ID;

    private final RbacAccountRepository accountRepo;
    private final AccountRepository accountInfoRepo;
    private final RbacDepartmentRepository deptRepo;
    private final DepartmentRepository deptInfoRepo;
    private final RbacDepartmentAccountRepository deptAccountRepo;
    private final RbacRoleRepository roleRepo;
    private final RbacDepartmentAccountPermissionRepository deptAccountPermRepo;
    private final RbacDepartmentAccountRoleRepository deptAccountRoleRepo;
    private final RbacRoleRoleRepository roleRoleRepo;
    private final RbacRolePermissionRepository rolePermRepo;
    private final RbacPermissionRepository permRepo;
    private final TransactionTemplate transactionTemplate;
}
