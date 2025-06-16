package org.nterloc.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class ResourceServerConfig {

    /*
    @ConditionalOnMissingBean
    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) throws Exception {
        http.securityMatcher("/articles/**")
                .authorizeHttpRequests(authorize -> authorize.anyRequest()
                        .hasAuthority("SCOPE_articles.read"))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

     */

    @ConditionalOnMissingBean
    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) throws Exception {
        http

                .authorizeExchange(customizer ->
                        customizer.anyExchange().permitAll()
                ).csrf(customizer -> customizer.disable());


        return http.build();
    }



}
