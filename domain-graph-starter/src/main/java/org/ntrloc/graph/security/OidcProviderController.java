package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.security.OidcProviderService.OidcProvider;
import org.ntrloc.graph.security.OidcProviderService.StoredOidcProvider;
import org.ntrloc.graph.security.OidcProviderService.TestResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.List;

// Admin-only, for the same reason as LdapConfigurationController: a provider decides which email --
// and so which ntrloc account -- a sign-in resolves to. The client secret goes in but never comes back.
@RestController
@RequestMapping("/api/admin/security/oidc")
public class OidcProviderController {

    // redirectUri and postLogoutRedirectUri are what to register at the provider, as this server is
    // being reached right now: where it sends people back after signing in, and after signing out
    // (SecurityConfig.logoutSuccessHandler).
    public record OidcProviderView(String registrationId, String displayName, String issuerUri, String clientId,
                                   String scopes, boolean enabled, String redirectUri, String postLogoutRedirectUri,
                                   OffsetDateTime updatedAt) {}

    // clientSecret: required on create; null or blank on update keeps the stored one.
    public record OidcProviderRequest(String registrationId, String displayName, String issuerUri, String clientId,
                                      String clientSecret, String scopes, boolean enabled) {

        OidcProvider provider() {
            return new OidcProvider(registrationId, displayName, issuerUri, clientId, scopes, enabled);
        }
    }

    public record TestRequest(String issuerUri) {}

    private final OidcProviderService providers;
    private final ExternalPrincipalResolver principals;

    public OidcProviderController(OidcProviderService providers, ExternalPrincipalResolver principals) {
        this.providers = providers;
        this.principals = principals;
    }

    @GetMapping
    List<OidcProviderView> list(Authentication authentication) {
        requireAdmin(authentication);
        return providers.list().stream().map(OidcProviderController::view).toList();
    }

    @PostMapping
    OidcProviderView create(@RequestBody OidcProviderRequest body, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(providers.create(body.provider(), body.clientSecret(), admin.id()));
    }

    @PutMapping("/{registrationId}")
    OidcProviderView update(@PathVariable("registrationId") String registrationId, @RequestBody OidcProviderRequest body,
                            Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(providers.update(registrationId, body.provider(), body.clientSecret(), admin.id()));
    }

    @DeleteMapping("/{registrationId}")
    ResponseEntity<Void> delete(@PathVariable("registrationId") String registrationId, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        providers.delete(registrationId, admin.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    TestResult test(@RequestBody TestRequest body, Authentication authentication) {
        requireAdmin(authentication);
        return providers.test(body.issuerUri());
    }

    private NtrlocPrincipal requireAdmin(Authentication authentication) {
        NtrlocPrincipal principal = principals.resolve(authentication);
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can configure sign-in providers");
        }
        return principal;
    }

    private static OidcProviderView view(StoredOidcProvider stored) {
        OidcProvider p = stored.provider();
        String redirectUri = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/login/oauth2/code/{registrationId}").buildAndExpand(p.registrationId()).toUriString();
        String postLogoutRedirectUri = ServletUriComponentsBuilder.fromCurrentContextPath().path("/login").toUriString();
        return new OidcProviderView(p.registrationId(), p.displayName(), p.issuerUri(), p.clientId(), p.scopes(),
                p.enabled(), redirectUri, postLogoutRedirectUri, stored.updatedAt());
    }
}
