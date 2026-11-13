package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccount;

public interface RbacAccountRepository extends JpaRepository<RbacAccount, String> {
    List<RbacAccount> findAll();
    Optional<RbacAccount> findByAccountId(String accountId);

    // 仅投影所需字段；软删除条件放在关联中，保留业务账号缺失时的回退行为。
    @Query("""
        SELECT new com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount(
            r.accountId,
            COALESCE(CAST(a.id AS string), r.accountId),
            CASE WHEN a.id IS NULL THEN r.accountName ELSE a.name END
        )
        FROM RbacAccount r
        LEFT JOIN Account a ON a.accountId = r.accountId AND a.isDeleted = false
        WHERE r.accountId = :accountId
        """)
    Optional<CurrentAccount> findCurrentAccountByAccountId(@Param("accountId") String accountId);
}
