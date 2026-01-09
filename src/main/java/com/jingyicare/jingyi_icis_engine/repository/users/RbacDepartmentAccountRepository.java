package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccount;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccountId;

public interface RbacDepartmentAccountRepository extends JpaRepository<RbacDepartmentAccount, RbacDepartmentAccountId> {
    List<RbacDepartmentAccount> findAll();
    List<RbacDepartmentAccount> findByIdAccountId(String accountId);
    List<RbacDepartmentAccount> findByIdDeptId(String deptId);

    Optional<RbacDepartmentAccount> findById(RbacDepartmentAccountId id);
    void deleteByIdAccountId(String accountId);
}