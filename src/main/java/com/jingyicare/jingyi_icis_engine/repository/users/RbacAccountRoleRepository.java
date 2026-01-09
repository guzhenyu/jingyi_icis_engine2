package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccountRole;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccountRoleId;

public interface RbacAccountRoleRepository extends JpaRepository<RbacAccountRole, RbacAccountRoleId> {
    List<RbacAccountRole> findAll();
    List<RbacAccountRole> findByIdAccountId(String accountId);
    Optional<RbacAccountRole> findById(RbacAccountRoleId id);
    void deleteByIdAccountId(String accountId);
}