package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Slf4j
public class RbacUserDetails implements UserDetails {
    public RbacUserDetails(String username, String password) {
        this.username = username;
        this.password = password;
        this.authorities = new ArrayList<>();
        this.authorities.add(new SimpleGrantedAuthority(DEFAULT_ROLE));
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    private static final String DEFAULT_ROLE = "ROLE_AUTHENTICATED";

    private String username;
    private String password;
    private List<SimpleGrantedAuthority> authorities;
}