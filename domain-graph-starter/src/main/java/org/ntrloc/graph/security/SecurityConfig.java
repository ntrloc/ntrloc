package org.ntrloc.graph.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ntrloc.graph.db.partition.security.LocalUserDetailsService;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String LOGIN_PATH = "/login";

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
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint entryPoint) throws Exception {
        // Shared by httpBasic() and formLogin() below -- without this explicit wiring, HttpSecurity
        // builds its own AuthenticationManagerBuilder from auto-detected beans instead of picking up
        // compositeAuthenticationManager() the way WebFlux's equivalent auto-wired a lone
        // ReactiveAuthenticationManager bean (the servlet side has no equivalent implicit pickup).
        http
                .authenticationManager(compositeAuthenticationManager())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // An error response from any filter below (e.g. patFilter()'s
                        // sendError(401) on an invalid bearer token) triggers Boot's own /error
                        // forward, which is a second, internal dispatch back through this same
                        // chain -- without this, that forward has no Authentication either, gets
                        // denied too, and the AuthenticationEntryPoint below (a redirect to
                        // /login) fires on it, turning the client's real response into a 302
                        // instead of the original 401. Found empirically: three PAT rejection
                        // tests returned 302 instead of the expected 4xx until this was added.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(openPathPatterns()).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessHandler(logoutSuccessHandler())
                );

        if (authProperties.getOauth().isEnabled()) {
            http.oauth2Login(oauth -> oauth.loginPage(LOGIN_PATH));
        }

        if (authProperties.getLocal().isEnabled() ||
                authProperties.getLdap().isEnabled()) {
            // No per-mechanism .authenticationManager(...) override here (FormLoginConfigurer
            // doesn't expose one in this Spring Security version, confirmed by compile error when
            // one was tried) -- the top-level http.authenticationManager(...) call above already
            // covers this filter too, same shared manager.
            http.formLogin(form -> form.loginPage(LOGIN_PATH));
        }

        http.httpBasic(Customizer.withDefaults());

        // Bearer-token (PAT) auth is checked fresh on every request via the Authorization header,
        // no session involved — a different shape than form login, so it gets its own filter
        // rather than folding into compositeAuthenticationManager() (which is specifically for
        // the POST /login form submission). Unconditional, same as httpBasic() above: PATs are
        // always available once security is enabled, no AuthProperties toggle.
        http.addFilterAt(patFilter(), BasicAuthenticationFilter.class);

        return http.build();
    }

    // "/public" and "/login" are appended unconditionally, never sourced from
    // authProperties.getOpenPaths() itself -- an application's own list is additive only, never a
    // replacement for these two, regardless of what it does or doesn't contain (including leaving
    // openPaths null, the default when it's not configured at all).
    private String[] openPathPatterns() {
        List<String> patterns = new ArrayList<>(List.of("/public", LOGIN_PATH));
        if (authProperties.getOpenPaths() != null) {
            patterns.addAll(authProperties.getOpenPaths());
        }
        return patterns.toArray(new String[0]);
    }

    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint() {
        return new LoginUrlAuthenticationEntryPoint(LOGIN_PATH);
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        SimpleUrlLogoutSuccessHandler handler = new SimpleUrlLogoutSuccessHandler();
        handler.setDefaultTargetUrl(LOGIN_PATH);
        return handler;
    }

    private AuthenticationManager compositeAuthenticationManager() {
        List<AuthenticationProvider> providers = new ArrayList<>();

        if (authProperties.getLdap().isEnabled()) {
            providers.add(ldapAuthenticationProvider());
        }
        if (authProperties.getLocal().isEnabled()) {
            DaoAuthenticationProvider daoProvider = new DaoAuthenticationProvider(localUserDetailsService);
            providers.add(daoProvider);
        }

        // ProviderManager tries each provider in turn, moving to the next on an
        // AuthenticationException (e.g. BadCredentialsException) rather than requiring every
        // provider to "know" about every other one -- the direct servlet equivalent of
        // DelegatingReactiveAuthenticationManager's "empty means try the next" contract, just
        // signaled by exception instead of an empty result.
        return new ProviderManager(providers);
    }

    private AuthenticationProvider ldapAuthenticationProvider() {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(authProperties.getLdap().getUrl());
        contextSource.setBase(authProperties.getLdap().getBase());
        contextSource.setUserDn(authProperties.getLdap().getUsername());
        contextSource.setPassword(authProperties.getLdap().getPassword());
        contextSource.afterPropertiesSet();

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserDnPatterns(new String[]{authProperties.getLdap().getUserDnPattern()});

        DefaultLdapAuthoritiesPopulator authoritiesPopulator =
                new DefaultLdapAuthoritiesPopulator(contextSource, "ou=people");
        authoritiesPopulator.setDefaultRole("USER");
        authoritiesPopulator.setIgnorePartialResultException(true);

        return new LdapAuthenticationProvider(authenticator, authoritiesPopulator);
    }

    // The principal set here is the full NtrlocPrincipal PersonalAccessTokenService.authenticate
    // already resolved (id, groupIds, isSuperuser, displayName included) -- not just an externalId
    // string. Same "already-resolved, no second SecurityRepository round-trip" shape as
    // NtrlocUserDetails gives local-credential logins; PrincipalResolver's fast path picks either
    // up identically via instanceof NtrlocPrincipal. The converter returns without setting anything
    // when there's no Bearer header, so ordinary session-based requests pass through untouched.
    private final SecurityContextRepository patSecurityContextRepository = new RequestAttributeSecurityContextRepository();

    private OncePerRequestFilter patFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && header.startsWith("Bearer ")) {
                    String token = header.substring("Bearer ".length());
                    Optional<NtrlocPrincipal> principal = personalAccessTokenService.authenticate(token);
                    if (principal.isPresent()) {
                        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal.get(), null, List.of());
                        SecurityContext context = SecurityContextHolder.createEmptyContext();
                        context.setAuthentication(auth);
                        SecurityContextHolder.setContext(context);
                        // Saved, not just set: an endpoint that completes asynchronously (a Mono
                        // or SseEmitter return) finishes on a second dispatch, which this
                        // OncePerRequestFilter skips -- the context has to be restorable there.
                        patSecurityContextRepository.saveContext(context, request, response);
                    } else {
                        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired personal access token");
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }
}
