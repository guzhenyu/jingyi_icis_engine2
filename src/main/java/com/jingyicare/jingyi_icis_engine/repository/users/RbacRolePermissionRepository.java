package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacRolePermission;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacRolePermissionId;

public interface RbacRolePermissionRepository extends JpaRepository<RbacRolePermission, RbacRolePermissionId> {
    List<RbacRolePermission> findAll();
    List<RbacRolePermission> findByIdRoleIdIn(List<Integer> roleIds);
    List<RbacRolePermission> findByIdPermissionIdIn(List<Integer> permissionIds);
    Optional<RbacRolePermission> findById(RbacRolePermissionId id);
}