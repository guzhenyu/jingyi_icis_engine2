package com.jingyicare.jingyi_icis_engine.repository.users;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.entity.users.DepartmentAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DepartmentAccountRepository extends JpaRepository<DepartmentAccount, Integer> {
    List<DepartmentAccount> findByAccountIdAndIsDeletedFalse(String accountId);

    List<DepartmentAccount> findByDeptIdAndPrimaryRoleIdIn(String deptId, List<Integer> primaryRoleIds);

    Optional<DepartmentAccount> findByAccountIdAndDeptIdAndIsDeletedFalse(
            String accountId, String deptId);
}