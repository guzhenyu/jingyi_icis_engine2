package com.jingyicare.jingyi_icis_engine.service.users;

import jakarta.transaction.Transactional;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;

@Service
@Slf4j
public class RbacUserDetailsService implements UserDetailsService {
    public RbacUserDetailsService(@Autowired RbacAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        String normalizedUsername = username == null ? "" : username.trim();
        RbacAccount account = accountRepository.findById(normalizedUsername).orElse(null);
        if (account != null) {
            return new RbacUserDetails(account.getAccountId(), account.getPasswordHash());
        } else {
            log.warn("RBAC user not found: {}", normalizedUsername);
            throw new UsernameNotFoundException("User not found");
        }
    }

    private RbacAccountRepository accountRepository;
}
