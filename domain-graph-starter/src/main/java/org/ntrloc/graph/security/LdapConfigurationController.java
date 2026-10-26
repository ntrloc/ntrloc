package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.security.LdapConfigurationService.LdapSettings;
import org.ntrloc.graph.security.LdapConfigurationService.StoredLdapSettings;
import org.ntrloc.graph.security.LdapConfigurationService.TestResult;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

// Admin-only. Whoever controls this configuration controls who LDAP says someone is -- and logins
// resolve to ntrloc accounts by the directory's email -- so it's as privileged as making admins.
// The bind password goes in (password) but never comes back out (bindPasswordSet).
@RestController
@RequestMapping("/api/admin/security/ldap")
public class LdapConfigurationController {

    public record LdapConfigurationView(boolean enabled, String url, String baseDn, String bindDn,
                                        boolean bindPasswordSet, String userSearchBase, String userSearchFilter,
                                        String emailAttribute, String displayNameAttribute, OffsetDateTime updatedAt) {}

    // password: null or blank keeps the stored one.
    public record LdapConfigurationRequest(boolean enabled, String url, String baseDn, String bindDn, String password,
                                           String userSearchBase, String userSearchFilter, String emailAttribute,
                                           String displayNameAttribute) {

        LdapSettings settings() {
            return new LdapSettings(enabled, url, baseDn, bindDn, userSearchBase, userSearchFilter, emailAttribute,
                    displayNameAttribute);
        }
    }

    private final LdapConfigurationService ldap;
    private final ExternalPrincipalResolver principals;

    public LdapConfigurationController(LdapConfigurationService ldap, ExternalPrincipalResolver principals) {
        this.ldap = ldap;
        this.principals = principals;
    }

    @GetMapping
    LdapConfigurationView get(Authentication authentication) {
        requireAdmin(authentication);
        return view(ldap.stored());
    }

    @PutMapping
    LdapConfigurationView save(@RequestBody LdapConfigurationRequest body, Authentication authentication) {
        NtrlocPrincipal admin = requireAdmin(authentication);
        return view(ldap.save(body.settings(), body.password(), admin.id()));
    }

    @PostMapping("/test")
    TestResult test(@RequestBody LdapConfigurationRequest body, Authentication authentication) {
        requireAdmin(authentication);
        return ldap.test(body.settings(), body.password());
    }

    private NtrlocPrincipal requireAdmin(Authentication authentication) {
        NtrlocPrincipal principal = principals.resolve(authentication);
        if (!principal.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can configure LDAP");
        }
        return principal;
    }

    private static LdapConfigurationView view(StoredLdapSettings stored) {
        LdapSettings s = stored.settings();
        return new LdapConfigurationView(s.enabled(), s.url(), s.baseDn(), s.bindDn(), stored.bindPasswordSet(),
                s.userSearchBase(), s.userSearchFilter(), s.emailAttribute(), s.displayNameAttribute(),
                stored.updatedAt());
    }
}
