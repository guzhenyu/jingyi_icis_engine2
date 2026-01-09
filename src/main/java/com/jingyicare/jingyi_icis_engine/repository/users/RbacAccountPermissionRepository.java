package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccountPermission;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccountPermissionId;

public interface RbacAccountPermissionRepository extends JpaRepository<RbacAccountPermission, RbacAccountPermissionId> {
    List<RbacAccountPermission> findAll();
    List<RbacAccountPermission> findByIdAccountId(String accountId);
}