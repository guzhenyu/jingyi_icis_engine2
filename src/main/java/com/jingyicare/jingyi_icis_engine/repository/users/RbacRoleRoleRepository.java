package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacRoleRole;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacRoleRoleId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RbacRoleRoleRepository extends JpaRepository<RbacRoleRole, RbacRoleRoleId> {
    List<RbacRoleRole> findByIdParentRoleIdIn(List<Integer> parentRoleIds);
}