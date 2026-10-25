package org.ntrloc.graph.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.ExternalUserProvisioner;
import org.ntrloc.graph.db.partition.security.LocalAuthenticationProvider;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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
    private final SecurityRepository securityRepository;
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;
    private final ObjectProvider<ClientRegistrationRepository> clientRegistrations;

    public SecurityConfig(AuthProperties authProperties, SecurityRepository securityRepository,
                           ExternalPrincipalResolver principals, ExternalUserProvisioner provisioner,
                           ObjectProvider<ClientRegistrationRepository> clientRegistrations) {
        this.authProperties = authProperties;
        this.securityRepository = securityRepository;
        this.principals = principals;
        this.provisioner = provisioner;
        this.clientRegistrations = clientRegistrations;
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
        if (authProperties.getOauth().isEnabled() && clientRegistrations.getIfAvailable() == null) {
            throw new IllegalStateException("graph.auth.oauth.enabled is set, but no OpenID Connect provider is "
                    + "configured under spring.security.oauth2.client.registration");
        }
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationEntryPoint entryPoint) throws Exception {
        // Username/password providers, tried in this order by the one AuthenticationManager that
        // HttpSecurity builds -- which also holds the OAuth2 login providers oauth2Login() registers
        // (so this must never be replaced by an explicit http.authenticationManager(...)). A
        // BadCredentialsException from one moves on to the next.
        if (authProperties.getLdap().isEnabled()) {
            http.authenticationProvider(ldapAuthenticationProvider());
        }
        if (authProperties.getLocal().isEnabled()) {
            http.authenticationProvider(new LocalAuthenticationProvider(principals, securityRepository));
        }

        http
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
                        // Both admin UIs. Admin comes only from security_user.is_admin, read off the
                        // principal like every controller's own admin check -- never from granted
                        // authorities.
                        .requestMatchers("/admin/**", "/admin-react/**").access((authentication, _) ->
                                new AuthorizationDecision(principals.fromAuthentication(authentication.get())
                                        .map(NtrlocPrincipal::isAdmin).orElse(false)))
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
            // OpenID Connect only: a plain OAuth2 provider has no verified email to resolve a user by.
            http.oauth2Login(oauth -> oauth
                    .loginPage(LOGIN_PATH)
                    .userInfoEndpoint(userInfo -> userInfo
                            .oidcUserService(new NtrlocOidcUserService(principals, provisioner))
                            .userService(request -> {
                                throw new OAuth2AuthenticationException(new OAuth2Error("invalid_client"),
                                        "Only OpenID Connect providers are supported (request the openid scope)");
                            })));
        }

        if (authProperties.getLocal().isEnabled() ||
                authProperties.getLdap().isEnabled()) {
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

    // Search for the user's entry with the configured filter, then bind as it to check the password.
    private AuthenticationProvider ldapAuthenticationProvider() {
        AuthProperties.LdapProperties ldap = authProperties.getLdap();
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(ldap.getUrl());
        contextSource.setBase(ldap.getBase());
        if (ldap.getUsername() == null || ldap.getUsername().isBlank()) {
            contextSource.setAnonymousReadOnly(true);
        } else {
            contextSource.setUserDn(ldap.getUsername());
            contextSource.setPassword(ldap.getPassword());
        }
        contextSource.afterPropertiesSet();

        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(ldap.getUserSearchBase(), ldap.getUserSearchFilter(), contextSource));

        return new NtrlocLdapAuthenticationProvider(authenticator, ldap, principals, provisioner);
    }

    // The principal set here is the token owner's full NtrlocPrincipal, the same shape a local
    // login's Authentication carries. Requests without a Bearer header pass through untouched.
    private final SecurityContextRepository patSecurityContextRepository = new RequestAttributeSecurityContextRepository();

    private OncePerRequestFilter patFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String header = request.getHeader(HttpHeaders.AUTHORIZATION);
                if (header != null && header.startsWith("Bearer ")) {
                    String token = header.substring("Bearer ".length());
                    Optional<NtrlocPrincipal> principal = principals.byToken(token);
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
