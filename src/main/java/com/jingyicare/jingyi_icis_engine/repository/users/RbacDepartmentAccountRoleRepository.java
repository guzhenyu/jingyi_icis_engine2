package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountRole;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountRoleId;

public interface RbacDepartmentAccountRoleRepository extends JpaRepository<RbacDepartmentAccountRole, RbacDepartmentAccountRoleId> {
    List<RbacDepartmentAccountRole> findAll();
    List<RbacDepartmentAccountRole> findByIdAccountId(String accountId);
    List<RbacDepartmentAccountRole> findByIdAccountIdAndIdDeptId(String accountId, String deptId);
    Optional<RbacDepartmentAccountRole> findById(RbacDepartmentAccountRoleId id);

    void deleteByIdAccountIdAndIdDeptId(String accountId, String deptId);
    void deleteByIdAccountId(String accountId);
}