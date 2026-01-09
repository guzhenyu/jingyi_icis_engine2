package com.jingyicare.jingyi_icis_engine.service.users;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;

import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

public class RbacUserDetailsServiceTests extends TestsBase {
    public RbacUserDetailsServiceTests() {
    }

    @Test
    public void testFindAll() {
        // 测试loadUserByUsername("admin");
        UserDetails userDetails = rbacUserDetailsService.loadUserByUsername("admin");
        assertThat(userDetails.getUsername()).isEqualTo("admin");
        assertThat(userDetails.getPassword()).isNotNull();
        assertThat(userDetails.getAuthorities().size()).isEqualTo(1);
        assertThat(userDetails.getAuthorities().iterator().next().getAuthority()).isEqualTo("ROLE_AUTHENTICATED");
    }

    @Autowired
    private RbacUserDetailsService rbacUserDetailsService;
}