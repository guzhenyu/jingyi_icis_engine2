package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountPermission;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountPermissionId;

public interface RbacDepartmentAccountPermissionRepository extends JpaRepository<RbacDepartmentAccountPermission, RbacDepartmentAccountPermissionId> {
    List<RbacDepartmentAccountPermission> findAll();
    List<RbacDepartmentAccountPermission> findByIdAccountId(String accountId);
    List<RbacDepartmentAccountPermission> findByIdAccountIdAndIdDeptId(String accountId, String deptId);
    List<RbacDepartmentAccountPermission> findByIdDeptIdAndIdPermissionIdIn(String deptId, List<Integer> permissionIds);
    void deleteByIdAccountIdAndIdDeptId(String accountId, String deptId);
    void deleteByIdAccountId(String accountId);
}