package com.jingyicare.jingyi_icis_engine.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import com.jingyicare.jingyi_icis_engine.service.users.Encoder;
import com.jingyicare.jingyi_icis_engine.service.users.RbacUserDetailsService;


@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() {
        return encoder.get();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider(RbacUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider authenticationProvider = new DaoAuthenticationProvider();
        authenticationProvider.setUserDetailsService(userDetailsService);
        authenticationProvider.setPasswordEncoder(passwordEncoder);
        return authenticationProvider;
    }

    @Bean
    public AuthenticationManager authManager(HttpSecurity http, AuthenticationProvider provider) throws Exception {
        AuthenticationManagerBuilder builder = http.getSharedObject(AuthenticationManagerBuilder.class);
        builder.authenticationProvider(provider);
        return builder.build();
    }

    // todo(guzhenyu):
    // 1. anti-csrf
    // 2. when a login user logined in other devices, the current user should be logout
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.headers()
                .frameOptions().sameOrigin()  // 设置 X-Frame-Options 为 SAMEORIGIN
                .and()
            .authorizeHttpRequests(authorize -> 
                authorize
                    .requestMatchers((request) -> request.getServletPath().startsWith("/favicon")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/login")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/access")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/common")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/main")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/react-vendor")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/vendors")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/api/user/getusername")).permitAll()
                    .requestMatchers((request) -> request.getServletPath().matches("^/\\d+/[^/]+\\.pdf$")).permitAll()
                    .requestMatchers("/actuator/prometheus").permitAll()
                    .requestMatchers((request) -> request.getServletPath().startsWith("/admin")).hasRole("1")
                    .anyRequest().authenticated()
            )
            .formLogin(formLogin ->
                formLogin
                    .loginPage("/login.html")
                    .loginProcessingUrl("/perform_login")
                    .defaultSuccessUrl("/home.html", true)
                    .failureHandler(new IcisAuthenticationFailureHandler())
            )
            .logout(logout ->
                logout
                    .logoutUrl("/perform_logout")
                    .logoutSuccessUrl("/login.html")
            )
            .csrf().disable();
            /*
            .sessionManagement(session -> 
                session
                    .maximumSessions(1)
                    .maxSessionsPreventsLogin(false)
                    .expiredUrl("/login.html?expired")
            )
            .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
            */
        return http.build();
    }

    @Autowired Encoder encoder;
}
