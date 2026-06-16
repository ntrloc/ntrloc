package org.ntrloc.graph.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(auth -> auth
                        .pathMatchers("/public", "/login", "/csrf").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2Login(oauth -> oauth
                        .loginPage("/login")
                )
                .formLogin(form -> form
                        .loginPage("/login")
                )
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public ReactiveUserDetailsService userDetailsService() {
        UserDetails user = User.builder()
                .username("localuser")
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode("password123"))
                .roles("USER")
                .build();
        return new MapReactiveUserDetailsService(user);
    }

}