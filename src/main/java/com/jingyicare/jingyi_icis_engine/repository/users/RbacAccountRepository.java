package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacAccount;

public interface RbacAccountRepository extends JpaRepository<RbacAccount, String> {
    List<RbacAccount> findAll();
    Optional<RbacAccount> findByAccountId(String accountId);
}