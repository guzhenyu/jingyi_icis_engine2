package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacPermission;

public interface RbacPermissionRepository extends JpaRepository<RbacPermission, Integer> {
  List<RbacPermission> findAll();
  List<RbacPermission> findByIdIn(List<Integer> ids);
}