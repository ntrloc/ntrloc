package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository.KeyRole;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.ntrloc.graph.security.SamlProviderService.StoredKey;
import org.ntrloc.graph.security.SamlProviderService.StoredSamlProvider;
import org.ntrloc.graph.security.SamlProviderService.TestResult;
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

// Admin-only, for the same reason as LdapConfigurationController: an IdP decides which email -- and
// so which ntrloc account -- a sign-in resolves to. ntrloc's private keys never leave the server
// (uploaded ones only go in); their certificates are in ntrloc's metadata.
@RestController
@RequestMapping("/api/admin/security/saml")
public class SamlProviderController {

    // entityId, acsUrl and sloUrl are what to register at the IdP, as this server is being reached
    // right now. ntrloc's entity ID is its metadata URL, which carries the other two and its
    // certificate (for signing and encryption) for an IdP that imports metadata.
    public record SamlProviderView(String registrationId, String displayName, String metadataUri, String emailAttribute,
                                   String displayNameAttribute, boolean enabled, String entityId, String acsUrl,
                                   String sloUrl, List<StoredKey> keys, OffsetDateTime updatedAt) {}

    public record SamlProviderRequest(String registrationId, String displayName, String metadataUri,
                                      String emailAttribute, String displayNameAttribute, boolean enabled) {

        SamlProvider provider() {
            return new SamlProvider(registrationId, displayName, metadataUri, emailAttribute, displayNameAttribute, enabled);
        }
    }

    public record TestRequest(String metadataUri) {}

    // Both blank: generate a key pair. Otherwise the admin's own, as PEM.
    public record KeyRequest(String privateKey, String certificate) {}

    private final SamlProviderService providers;
    private final ExternalPrincipalResolver principals;

    public SamlProviderController(SamlProviderService providers, ExternalPrincipalResolver principals) {
        this.providers = providers;
        this.principals = principals;
    }

    @GetMapping
    List<SamlProviderView> list(Authentication authentication) {
        requireAdmin(authentication);
        return providers.list().stream().map(SamlProviderController::view).toList();
    }

    @PostMapping
    SamlProviderView create(@RequestBody SamlProviderRequest body, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(providers.create(body.provider(), admin.id()));
    }

    @PutMapping("/{registrationId}")
    SamlProviderView update(@PathVariable("registrationId") String registrationId, @RequestBody SamlProviderRequest body,
                            Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(providers.update(registrationId, body.provider(), admin.id()));
    }

    @DeleteMapping("/{registrationId}")
    ResponseEntity<Void> delete(@PathVariable("registrationId") String registrationId, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        providers.delete(registrationId, admin.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{registrationId}/keys")
    SamlProviderView addNextKey(@PathVariable("registrationId") String registrationId,
                                @RequestBody(required = false) KeyRequest body, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        KeyRequest key = body == null ? new KeyRequest(null, null) : body;
        return view(providers.addNextKey(registrationId, key.privateKey(), key.certificate(), admin.id()));
    }

    @PostMapping("/{registrationId}/keys/activate")
    SamlProviderView activateNextKey(@PathVariable("registrationId") String registrationId, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(providers.activateNextKey(registrationId, admin.id()));
    }

    // next cancels a pending key; retiring retires the previous one.
    @DeleteMapping("/{registrationId}/keys/{role}")
    SamlProviderView removeKey(@PathVariable("registrationId") String registrationId, @PathVariable("role") String role,
                               Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        KeyRole keyRole = switch (role) {
            case "next" -> KeyRole.NEXT;
            case "retiring" -> KeyRole.RETIRING;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + role + " key");
        };
        return view(providers.removeKey(registrationId, keyRole, admin.id()));
    }

    @PostMapping("/test")
    TestResult test(@RequestBody TestRequest body, Authentication authentication) {
        requireAdmin(authentication);
        return providers.test(body.metadataUri());
    }

    private NtrlocPrincipal requireAdmin(Authentication authentication) {
        NtrlocPrincipal principal = principals.resolve(authentication);
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can configure sign-in providers");
        }
        return principal;
    }

    private static SamlProviderView view(StoredSamlProvider stored) {
        SamlProvider p = stored.provider();
        return new SamlProviderView(p.registrationId(), p.displayName(), p.metadataUri(), p.emailAttribute(),
                p.displayNameAttribute(), p.enabled(), url(SamlProviderService.METADATA_PATH, p.registrationId()),
                url(SamlProviderService.ACS_PATH, p.registrationId()), url(SamlProviderService.SLO_PATH, p.registrationId()),
                stored.keys(), stored.updatedAt());
    }

    private static String url(String path, String registrationId) {
        return ServletUriComponentsBuilder.fromCurrentContextPath().path(path).buildAndExpand(registrationId).toUriString();
    }
}
