package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacRole;

public interface RbacRoleRepository extends JpaRepository<RbacRole, Integer> {
    List<RbacRole> findAll();
    List<RbacRole> findByIsPrimaryTrue();
    List<RbacRole> findByIsPrimaryFalse();
    Optional<RbacRole> findByName(String name);
    Optional<RbacRole> findById(Integer id);
}