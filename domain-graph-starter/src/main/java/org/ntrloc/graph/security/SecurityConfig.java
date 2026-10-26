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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.web.authentication.logout.OpenSaml5LogoutRequestResolver;
import org.springframework.security.saml2.provider.service.web.authentication.logout.Saml2RelyingPartyInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String LOGIN_PATH = "/login";

    private final AuthProperties authProperties;
    private final SecurityRepository securityRepository;
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;
    private final LdapConfigurationService ldapConfiguration;
    private final OidcProviderService oidcProviders;
    private final SamlProviderService samlProviders;

    public SecurityConfig(AuthProperties authProperties, SecurityRepository securityRepository,
                           ExternalPrincipalResolver principals, ExternalUserProvisioner provisioner,
                           LdapConfigurationService ldapConfiguration, OidcProviderService oidcProviders,
                           SamlProviderService samlProviders) {
        this.authProperties = authProperties;
        this.securityRepository = securityRepository;
        this.principals = principals;
        this.provisioner = provisioner;
        this.ldapConfiguration = ldapConfiguration;
        this.oidcProviders = oidcProviders;
        this.samlProviders = samlProviders;
    }

    // LDAP, OIDC and SAML are configured in the admin UI, and a bad save there can leave no one able to
    // sign in. graph.auth.local.enabled is the way back: turn it on and restart.
    @PostConstruct
    public void warnWithoutLocalLogin() {
        if (!authProperties.getLocal().isEnabled()) {
            LOG.warn("Local login is off (graph.auth.local.enabled): if LDAP, OIDC and SAML sign-in stop working, "
                    + "turn it back on and restart to reach the admin UI");
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
        http.authenticationProvider(new NtrlocLdapAuthenticationProvider(ldapConfiguration, principals, provisioner));
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

        // OpenID Connect only: a plain OAuth2 provider has no verified email to resolve a user by.
        // Unconditional, like formLogin below: providers come and go in the admin UI without a restart.
        http.oauth2Login(oauth -> oauth
                .loginPage(LOGIN_PATH)
                .clientRegistrationRepository(oidcProviders)
                // A disabled or deleted provider (an old bookmark, a stale login page) ends at the
                // login page's error, not a 500.
                .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                    @Override
                    public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                        filter.setAuthenticationFailureHandler(new SimpleUrlAuthenticationFailureHandler(LOGIN_PATH + "?error"));
                        return filter;
                    }
                })
                .userInfoEndpoint(userInfo -> userInfo
                        .oidcUserService(new NtrlocOidcUserService(principals, provisioner))
                        .userService(_ -> {
                            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_client"),
                                    "Only OpenID Connect providers are supported (request the openid scope)");
                        })));

        // SAML 2.0, unconditional for the same reason. Its own AuthenticationManager: the SAML provider
        // only ever handles an IdP's response, and NtrlocSamlResponseConverter turns that into the
        // ntrloc account. ntrloc's metadata for each IdP -- entity ID, ACS URL, signing certificate --
        // is served at SamlProviderService.METADATA_PATH. A failed sign-in ends at /login?error.
        OpenSaml5AuthenticationProvider samlAuthentication = new OpenSaml5AuthenticationProvider();
        samlAuthentication.setResponseAuthenticationConverter(
                new NtrlocSamlResponseConverter(samlProviders, principals, provisioner));
        http.saml2Login(saml -> saml
                .loginPage(LOGIN_PATH)
                .relyingPartyRegistrationRepository(samlProviders)
                .authenticationManager(new ProviderManager(samlAuthentication)));
        http.saml2Metadata(metadata -> metadata.metadataUrl(SamlProviderService.METADATA_PATH));
        // The IdP's side of single logout, at SamlProviderService.SLO_PATH: its response to ntrloc's
        // LogoutRequest, and its own LogoutRequests when someone signs out elsewhere. Starting a
        // logout is logoutSuccessHandler()'s job, for GET and POST /logout alike, so Spring's own
        // POST-only trigger is moved off /logout, where it would answer an IdP without single
        // logout with a bare 401.
        http.saml2Logout(logout -> logout
                .logoutUrl("/logout/saml2")
                .logoutRequest(request -> request.logoutUrl(SamlProviderService.SLO_PATH))
                .logoutResponse(response -> response.logoutUrl(SamlProviderService.SLO_PATH)));

        http.formLogin(form -> form.loginPage(LOGIN_PATH));

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

    // Someone who signed in through an external provider is signed out of it too, so its session
    // can't sign them straight back in on their next "Sign In with ...": an OIDC provider through
    // RP-initiated logout, a SAML IdP through a signed LogoutRequest (single logout), each returning
    // the browser to the login page. Everyone else -- and a provider with no logout endpoint -- goes
    // straight to the login page. Handles GET and POST /logout alike.
    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        OidcClientInitiatedLogoutSuccessHandler oidc = new OidcClientInitiatedLogoutSuccessHandler(oidcProviders);
        oidc.setPostLogoutRedirectUri("{baseUrl}" + LOGIN_PATH);
        oidc.setDefaultTargetUrl(LOGIN_PATH);
        LogoutSuccessHandler saml = new Saml2RelyingPartyInitiatedLogoutSuccessHandler(
                new OpenSaml5LogoutRequestResolver(samlProviders));
        return (request, response, authentication) -> {
            if (authentication instanceof Saml2AssertionAuthentication signedIn
                    && samlProviders.supportsSingleLogout(signedIn.getRelyingPartyRegistrationId())) {
                saml.onLogoutSuccess(request, response, authentication);
            } else {
                oidc.onLogoutSuccess(request, response, authentication);
            }
        };
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
