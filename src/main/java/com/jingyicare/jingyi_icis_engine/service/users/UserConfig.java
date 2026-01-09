package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.LinkedList;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class UserConfig {
    public UserConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired UserBasicOperator userBasicOp,
        @Autowired RbacPermissionRepository permRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired RbacRolePermissionRepository rolePermRepo,
        @Autowired RbacRoleRoleRepository roleRoleRepo,
        @Autowired RbacAccountRepository accountRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired RbacDepartmentAccountRepository deptAccountRepo,
        @Autowired RbacDepartmentAccountRoleRepository deptAccountRoleRepo,
        @Autowired RbacDepartmentAccountPermissionRepository deptAccountPermRepo
    ) {
        this.context = context;

        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.configPb = protoService.getConfig().getUser();
        this.ADMIN_ROLE_ID = configPb.getAdminRoleId();

        this.userBasicOp = userBasicOp;
        this.permRepo = permRepo;
        this.roleRepo = roleRepo;
        this.rolePermRepo = rolePermRepo;
        this.roleRoleRepo = roleRoleRepo;
        this.accountRepo = accountRepo;
        this.deptRepo = deptRepo;
        this.deptAccountRepo = deptAccountRepo;
        this.deptAccountRoleRepo = deptAccountRoleRepo;
        this.deptAccountPermRepo = deptAccountPermRepo;
    };

    public void initialize() {
        initMenuPermissions();
        initRoles();
        initAccountsAndDepartments();

        log.info("UserConfig initialized");
    }

    public void checkIntegrity() {
        if (!checkMenuPermissions()) {
            log.error("MenuPermissions are not valid, please check the configuration file and db");
            LogUtils.flushAndQuit(context);
        }
        if (!checkRoles()) {
            log.error("Roles are not valid, please check the configuration file and db");
            LogUtils.flushAndQuit(context);
        }
        if (!checkAccountsAndDepartments()) {
            log.error("Accounts and Departments are not valid, please check the configuration file and db");
            LogUtils.flushAndQuit(context);
        }

        log.info("UserConfig is valid");
    }

    @Transactional
    public void refresh() {
    }

    @Transactional
    private void initMenuPermissions() {
        List<RbacPermission> perms = permRepo.findAll();
        if (perms.size() > 0) return;

        // 检查系统的配置文件是否合法
        if (!checkMenuPermissionPB()) {
            log.error("MenuPermissions are not valid, please check the configuration file");
            LogUtils.flushAndQuit(context);
        }

        // 初始化
        for (PermissionPB perm : configPb.getPermissionList()) {
            if (perm.getName().equals("科研管理菜单")) continue;  // todo(guzhenyu): 当有科研管理的内容时，在开放该权限
            perms.add(new RbacPermission(perm.getId(), perm.getName()));
        }
        permRepo.saveAll(perms);
    }

    @Transactional
    private Boolean checkMenuPermissions() {
        // 检查系统的配置文件是否合法
        if (!checkMenuPermissionPB()) return false;

        // 需要的菜单权限
        Set<Integer> menuPermIds = new HashSet<>();
        menuPermIds = configPb.getMenuPermissionList()
            .stream().map(MenuPermissionPB::getPermissionId)
            .collect(Collectors.toSet());

        // 检查数据库中的基础权限是否包含所有的菜单权限
        List<RbacPermission> perms = permRepo.findAll();
        for (RbacPermission perm : perms) {
            if (menuPermIds.contains(perm.getId())) {
                menuPermIds.remove(perm.getId());
            }
        }

        if (menuPermIds.size() > 0) {
            log.error("MenuPermission has missing permissionId {}", menuPermIds);
            return false;
        }

        return true;
    }

    @Transactional
    private void initRoles() {
        List<RbacRole> roles = roleRepo.findAll();
        if (roles.size() > 0) return;

        Map<Integer, String> roleMap = new HashMap<>();
        List<Map.Entry<Integer, Integer>> parentChildRoles = new ArrayList<>();
        List<Map.Entry<Integer, Integer>> rolePermIds = new ArrayList<>();
        for (RolePB role : configPb.getRoleList()) {
            roleMap.put(role.getId(), role.getName());
            for (Integer childRoleId : role.getChildRoleIdList()) {
                parentChildRoles.add(new AbstractMap.SimpleEntry<>(role.getId(), childRoleId));
            }
            for (Integer permId : role.getPermissionIdList()) {
                rolePermIds.add(new AbstractMap.SimpleEntry<>(role.getId(), permId));
            }
        }

        if (!checkRoleCircles(roleMap, parentChildRoles)) {
            log.error("Roles are not valid, please check the configuration file");
            LogUtils.flushAndQuit(context);
        }

        roles = new ArrayList<>();
        for (RolePB rolePb : configPb.getRoleList()) {
            RbacRole role = new RbacRole();
            role.setId(rolePb.getId());
            role.setName(rolePb.getName());
            role.setIsPrimary(rolePb.getIsPrimary());
            roles.add(role);
        }
        roleRepo.saveAll(roles);

        List<RbacRoleRole> roleRoles = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : parentChildRoles) {
            RbacRoleRole roleRole = new RbacRoleRole();
            roleRole.setId(new RbacRoleRoleId(entry.getKey(), entry.getValue()));
            roleRoles.add(roleRole);
        }
        roleRoleRepo.saveAll(roleRoles);

        List<RbacRolePermission> rolePerms = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : rolePermIds) {
            RbacRolePermission rolePerm = new RbacRolePermission();
            rolePerm.setId(new RbacRolePermissionId(entry.getKey(), entry.getValue()));
            rolePerms.add(rolePerm);
        }
        rolePermRepo.saveAll(rolePerms);
    }

    @Transactional
    private Boolean checkRoles() {
        Map<Integer, String> roleMap = new HashMap<>();
        for (RbacRole role : roleRepo.findAll()) {
            roleMap.put(role.getId(), role.getName());
        }
        if (!roleMap.containsKey(ADMIN_ROLE_ID)) {
            log.error("Admin role {} is not found", ADMIN_ROLE_ID);
            return false;
        }

        List<Map.Entry<Integer, Integer>> parentChildRoles = new ArrayList<>();
        for (RbacRoleRole roleRole : roleRoleRepo.findAll()) {
            RbacRoleRoleId id = roleRole.getId();
            parentChildRoles.add(new AbstractMap.SimpleEntry<>(id.getParentRoleId(), id.getChildRoleId()));
        }
        return checkRoleCircles(roleMap, parentChildRoles);
    }

    private void initAccountsAndDepartments() {
        List<RbacAccount> accounts = accountRepo.findAll();
        if (accounts.size() <= 0) {
            for (AccountPB account : configPb.getAccountList()) {
                userBasicOp.addAccount(account);
            }
        }

        List<RbacDepartment> departments = deptRepo.findAll();
        if (departments.size() <= 0) {
            for (DepartmentPB dept : configPb.getDepartmentList()) {
                userBasicOp.addDepartment(dept);
            }
        }

        List<RbacDepartmentAccount> deptAccounts = deptAccountRepo.findAll();
        if (deptAccounts.size() <= 0) {
            for (DepartmentAccountPB deptAccount : configPb.getDepartmentAccountList()) {
                userBasicOp.addDepartmentAccount(deptAccount);
            }
        }

        List<RbacDepartmentAccountRole> deptAccountRoles = deptAccountRoleRepo.findAll();
        if (deptAccountRoles.size() <= 0) {
            for (DepartmentAccountRolePB deptAccountRole : configPb.getDepartmentAccountRoleList()) {
                userBasicOp.addDepartmentAccountRole(deptAccountRole);
            }
        }
    }

    private Boolean checkAccountsAndDepartments() {
        // 检查是否包含管理员账号等
        return true;
    }

    private Boolean checkMenuPermissionPB() {
        // 检查MenuPermission的menuGroupId是否合法
        Set<Integer> menuGroupIds = new HashSet<>();
        menuGroupIds = configPb.getMenu().getGroupList()
            .stream().map(MenuGroup::getGroupId)
            .collect(Collectors.toSet());
        for (MenuPermissionPB mp : configPb.getMenuPermissionList()) {
            if (!menuGroupIds.contains(mp.getMenuGroupId())) {
                log.error("MenuPermission {} has invalid menuGroupId {}", mp.getMenuGroupId(), mp.getMenuGroupId());
                return false;
            }
            menuGroupIds.remove(mp.getMenuGroupId());
        }

        // 检查MenuPermission的permissionId是否合法
        Set<Integer> permIds = configPb.getPermissionList().stream()
            .map(PermissionPB::getId)
            .collect(Collectors.toSet());
        for (MenuPermissionPB mp : configPb.getMenuPermissionList()) {
            if (!permIds.contains(mp.getPermissionId())) {
                log.error("MenuPermission {} has invalid permissionId {}", mp.getMenuGroupId(), mp.getPermissionId());
                return false;
            }
        }

        // 所有菜单组都必须被相应的MenuPermission引用
        if (menuGroupIds.size() > 0) {
            log.error("MenuPermission has missing menuGroupId {}", menuGroupIds);
            return false;
        }

        return true;
    }

    public Boolean checkRoleCircles(
        Map<Integer, String> roleMap,
        List<Map.Entry<Integer, Integer>> parentChildRoles
    ) {
        Map<Integer, Set<Integer>> childParentsMap = new HashMap<>();
        Map<Integer, Set<Integer>> parentChildrenMap = new HashMap<>();

        for (Map.Entry<Integer, Integer> entry : parentChildRoles) {
            Integer parentRoleId = entry.getKey();
            Integer childRoleId = entry.getValue();
            if (!childParentsMap.containsKey(childRoleId)) {
                childParentsMap.put(childRoleId, new HashSet<>());
            }
            childParentsMap.get(childRoleId).add(parentRoleId);

            if (!parentChildrenMap.containsKey(parentRoleId)) {
                parentChildrenMap.put(parentRoleId, new HashSet<>());
            }
            parentChildrenMap.get(parentRoleId).add(childRoleId);
        }

        // 从入度为零的角色开始遍历
        Queue<Integer> queue = new LinkedList<>();
        for (Integer roleId : roleMap.keySet()) {
            if (!childParentsMap.containsKey(roleId)) {
                queue.offer(roleId);
            }
        }
        while (!queue.isEmpty()) {
            Integer roleId = queue.poll();
            if (parentChildrenMap.containsKey(roleId)) {
                for (Integer childRoleId : parentChildrenMap.get(roleId)) {
                    childParentsMap.get(childRoleId).remove(roleId);
                    if (childParentsMap.get(childRoleId).isEmpty()) {
                        queue.offer(childRoleId);
                        childParentsMap.remove(childRoleId);
                    }
                }
                parentChildrenMap.remove(roleId);
            }
        }

        // 输出存在循环引用的角色
        if (childParentsMap.size() > 0) {
            StringBuilder circleNodes = new StringBuilder();
            for (Map.Entry<Integer, Set<Integer>> entry : childParentsMap.entrySet()) {
                String roleName = roleMap.get(entry.getKey());
                circleNodes.append(roleName).append("(").append(entry.getKey()).append(")\n");
            }
            log.error("Role has circle reference: {}", circleNodes.toString());
            return false;
        }

        return true;
    }

    private final String ZONE_ID;
    private final Integer ADMIN_ROLE_ID;

    private ConfigurableApplicationContext context;
    private UserConfigPB configPb;

    private UserBasicOperator userBasicOp;

    private RbacPermissionRepository permRepo;
    private RbacRoleRepository roleRepo;
    private RbacRolePermissionRepository rolePermRepo;
    private RbacRoleRoleRepository roleRoleRepo;
    private RbacAccountRepository accountRepo;
    private RbacDepartmentRepository deptRepo;
    private RbacDepartmentAccountRepository deptAccountRepo;
    private RbacDepartmentAccountRoleRepository deptAccountRoleRepo;
    private RbacDepartmentAccountPermissionRepository deptAccountPermRepo;
}