package org.ntrloc.graph.security;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.security.LocalUserDetailsService;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DelegatingReactiveAuthenticationManager;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
@ConditionalOnProperty(name = "ntrloc.security.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityConfig {

    private final AuthProperties authProperties;
    private final LocalUserDetailsService localUserDetailsService;
    private final PersonalAccessTokenService personalAccessTokenService;

    public SecurityConfig(AuthProperties authProperties, LocalUserDetailsService localUserDetailsService,
                           PersonalAccessTokenService personalAccessTokenService) {
        this.authProperties = authProperties;
        this.localUserDetailsService = localUserDetailsService;
        this.personalAccessTokenService = personalAccessTokenService;
    }

    @PostConstruct
    public void validateAuthConfig() {
        boolean anyEnabled = authProperties.getLocal().isEnabled()
                || authProperties.getOauth().isEnabled()
                || authProperties.getLdap().isEnabled();
        if (!anyEnabled) {
            throw new IllegalStateException(
                    "At least one authentication method must be enabled");
        }
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(
            ServerHttpSecurity http,
            ServerAuthenticationEntryPoint entryPoint) {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeExchange(auth -> auth
                        .pathMatchers(openPathPatterns()).permitAll()
                        .anyExchange().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(logoutSuccessHandler())
                );

        if (authProperties.getOauth().isEnabled()) {
            http.oauth2Login(oauth -> oauth.loginPage("/login"));
        }

        if (authProperties.getLocal().isEnabled() ||
                authProperties.getLdap().isEnabled()) {
            http.formLogin(form -> form
                    .loginPage("/login")
                    .authenticationManager(compositeAuthenticationManager())
            );
        }

        http.httpBasic(Customizer.withDefaults());

        // Bearer-token (PAT) auth is checked fresh on every request via the Authorization header,
        // no session involved — a different shape than form login, so it gets its own filter
        // rather than folding into compositeAuthenticationManager() (which is specifically for
        // the POST /login form submission). Unconditional, same as httpBasic() above: PATs are
        // always available once security is enabled, no AuthProperties toggle. The converter
        // returns empty when there's no Bearer header, so ordinary session-based requests pass
        // through untouched.
        AuthenticationWebFilter patFilter = new AuthenticationWebFilter(personalAccessTokenAuthenticationManager());
        patFilter.setServerAuthenticationConverter(bearerTokenConverter());
        http.addFilterAt(patFilter, SecurityWebFiltersOrder.HTTP_BASIC);

        return http.build();
    }

    // "/public" and "/login" are appended unconditionally, never sourced from
    // authProperties.getOpenPaths() itself -- an application's own list is additive only, never a
    // replacement for these two, regardless of what it does or doesn't contain (including leaving
    // openPaths null, the default when it's not configured at all).
    private String[] openPathPatterns() {
        List<String> patterns = new ArrayList<>(List.of("/public", "/login"));
        if (authProperties.getOpenPaths() != null) {
            patterns.addAll(authProperties.getOpenPaths());
        }
        return patterns.toArray(new String[0]);
    }

    @Bean
    public ServerAuthenticationEntryPoint authenticationEntryPoint() {
        return new RedirectServerAuthenticationEntryPoint("/login");
    }

    @Bean
    public ServerLogoutSuccessHandler logoutSuccessHandler() {
        RedirectServerLogoutSuccessHandler handler =
                new RedirectServerLogoutSuccessHandler();
        handler.setLogoutSuccessUrl(URI.create("/login"));
        return handler;
    }

    @Bean
    public ReactiveAuthenticationManager compositeAuthenticationManager() {
        List<ReactiveAuthenticationManager> managers = new ArrayList<>();

        if (authProperties.getLdap().isEnabled()) {
            managers.add(ldapAuthenticationManager());
        }
        if (authProperties.getLocal().isEnabled()) {
            managers.add(new UserDetailsRepositoryReactiveAuthenticationManager(
                    localUserDetailsService));
        }

        return new DelegatingReactiveAuthenticationManager(managers);
    }

    private ReactiveAuthenticationManager ldapAuthenticationManager() {
        return authentication -> Mono.fromCallable(() -> {
                    LdapContextSource contextSource = new LdapContextSource();
                    contextSource.setUrl(authProperties.getLdap().getUrl());
                    contextSource.setBase(authProperties.getLdap().getBase());
                    contextSource.setUserDn(authProperties.getLdap().getUsername());
                    contextSource.setPassword(authProperties.getLdap().getPassword());
                    contextSource.afterPropertiesSet();

                    LdapAuthenticationProvider provider = new LdapAuthenticationProvider(
                            new BindAuthenticator(contextSource) {{
                                setUserDnPatterns(new String[]{
                                        authProperties.getLdap().getUserDnPattern()
                                });
                            }},
                            new DefaultLdapAuthoritiesPopulator(contextSource, "ou=people") {{
                                setDefaultRole("USER");
                                setIgnorePartialResultException(true);
                            }}
                    );

                    try {
                        return provider.authenticate(
                                new UsernamePasswordAuthenticationToken(
                                        authentication.getPrincipal(),
                                        authentication.getCredentials()
                                )
                        );
                    } catch (BadCredentialsException e) {
                        return null;  // signal empty to fall through
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(auth -> auth != null
                        ? Mono.just(auth)
                        : Mono.empty()  // empty Mono causes DelegatingReactiveAuthenticationManager to try next
                );
    }

    private ReactiveAuthenticationManager personalAccessTokenAuthenticationManager() {
        // Unlike ldapAuthenticationManager() above, this manager runs standalone in its own
        // AuthenticationWebFilter rather than inside a DelegatingReactiveAuthenticationManager —
        // "empty means try the next manager" doesn't apply here, so failure must be signaled by
        // erroring, not by completing empty (which AuthenticationWebFilter does not treat as a
        // normal rejection when used this way).
        // The principal set here is the full NtrlocPrincipal PersonalAccessTokenService.authenticate
        // already resolved (id, groupIds, isSuperuser, displayName included) -- not just the
        // externalId string this used to pass. Same "already-resolved, no second SecurityRepository
        // round-trip" shape as NtrlocUserDetails gives local-credential logins; PrincipalResolver's
        // fast path picks either up identically via instanceof NtrlocPrincipal.
        return authentication -> Mono.fromCallable(() ->
                        personalAccessTokenService.authenticate((String) authentication.getCredentials()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(opt -> opt
                        .<org.springframework.security.core.Authentication>map(principal ->
                                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()))
                        .map(Mono::just)
                        .orElseGet(() -> Mono.error(new BadCredentialsException("Invalid or expired personal access token"))));
    }

    private ServerAuthenticationConverter bearerTokenConverter() {
        return exchange -> {
            String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (header == null || !header.startsWith("Bearer ")) {
                return Mono.empty();
            }
            String token = header.substring("Bearer ".length());
            return Mono.just(UsernamePasswordAuthenticationToken.unauthenticated(token, token));
        };
    }
}