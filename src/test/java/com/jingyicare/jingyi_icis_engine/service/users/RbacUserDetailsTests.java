package com.jingyicare.jingyi_icis_engine.service.users;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class RbacUserDetailsTest {

    @Test
    void testRbacUserDetails() {
        String username = "testUser";
        String password = "testPassword";

        RbacUserDetails userDetails = new RbacUserDetails(username, password);

        assertEquals(username, userDetails.getUsername());
        assertEquals(password, userDetails.getPassword());
        assertTrue(userDetails.isAccountNonExpired());

        Collection<? extends GrantedAuthority> grantedAuthorities = userDetails.getAuthorities();
        assertEquals(1, grantedAuthorities.size());
    }
}