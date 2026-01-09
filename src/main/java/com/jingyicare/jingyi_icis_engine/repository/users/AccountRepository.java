package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByIsDeletedFalse();
    List<Account> findByNameAndIsDeletedFalse(String name);
    List<Account> findByIdIn(List<Long> ids);

    Optional<Account> findByAccountIdAndIsDeletedFalse(String accountId);

    Optional<Account> findByNameContainingAndIsDeletedFalse(String name);

    Optional<Account> findByIdAndIsDeletedFalse(Long id);
}