package com.jingyicare.jingyi_icis_engine.service.users;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.utils.OperationRecorder;

@Service
@Slf4j
public class RbacUserDetailsService implements UserDetailsService {
    public RbacUserDetailsService(@Autowired RbacAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        RbacAccount account = accountRepository.findById(username).orElse(null);
        if (account != null) {
            return new RbacUserDetails(account.getAccountId(), account.getPasswordHash());
        } else {
            log.warn("RBAC - user " + username + " not found\n");
            throw new UsernameNotFoundException("User not found");
        }
    }

    private RbacAccountRepository accountRepository;
}