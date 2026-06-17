package org.ntrloc.graph.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.DelegatingReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
                        .authenticationManager(compositeAuthenticationManager())
                )
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    public ReactiveAuthenticationManager compositeAuthenticationManager() {
        return new DelegatingReactiveAuthenticationManager(
                ldapAuthenticationManager(),
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService())
        );
    }

    public ReactiveAuthenticationManager ldapAuthenticationManager() {
        return authentication -> Mono.fromCallable(() -> {
            LdapContextSource contextSource = new LdapContextSource();
            contextSource.setUrl("ldap://localhost:389");
            contextSource.setBase("dc=ntrloc,dc=com");
            contextSource.setUserDn("cn=admin,dc=ntrloc,dc=com");
            contextSource.setPassword("admin");
            contextSource.afterPropertiesSet();

            LdapAuthenticationProvider provider = new LdapAuthenticationProvider(
                    new BindAuthenticator(contextSource) {{
                        setUserDnPatterns(new String[]{"uid={0},ou=people"});
                    }},
                    new DefaultLdapAuthoritiesPopulator(contextSource, "ou=people") {{
                        setDefaultRole("USER");
                        setIgnorePartialResultException(true);
                    }}
            );

            return provider.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            authentication.getPrincipal(),
                            authentication.getCredentials()
                    )
            );
        }).subscribeOn(Schedulers.boundedElastic());
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