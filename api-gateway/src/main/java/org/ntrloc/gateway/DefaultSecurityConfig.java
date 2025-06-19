package org.ntrloc.gateway;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
//@EnableWebFluxSecurity
@Profile("default")
public class DefaultSecurityConfig {

    /*
    @Bean
    @ConditionalOnMissingBean
    public SecurityWebFilterChain filterChain(ServerHttpSecurity http) throws Exception {
        http
                .authorizeExchange(exchangeSpec -> exchangeSpec
                        .anyExchange().permitAll()
                );
        return http.build();
    }

     */
}
