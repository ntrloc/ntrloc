package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.ExternalUserProvisioner;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.authentication.LdapAuthenticator;

import java.util.List;

// LDAP login: the directory checks the password (search for the user, then bind as them), and the
// entry's email attribute decides which ntrloc account this is. An email with no ntrloc account is
// provisioned or rejected, per graph.security.auto-provision.enabled. Nothing else from the
// directory -- groups in particular -- affects what the user can do in ntrloc.
public class NtrlocLdapAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NtrlocLdapAuthenticationProvider.class);

    private final LdapAuthenticator authenticator;
    private final AuthProperties.LdapProperties properties;
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;

    public NtrlocLdapAuthenticationProvider(LdapAuthenticator authenticator, AuthProperties.LdapProperties properties,
                                      ExternalPrincipalResolver principals, ExternalUserProvisioner provisioner) {
        this.authenticator = authenticator;
        this.properties = properties;
        this.principals = principals;
        this.provisioner = provisioner;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        DirContextOperations entry = authenticator.authenticate((UsernamePasswordAuthenticationToken) authentication);

        String email = entry.getStringAttribute(properties.getEmailAttribute());
        if (email == null || email.isBlank()) {
            LOG.info("LDAP login for {} rejected: its entry has no {} attribute",
                    entry.getDn(), properties.getEmailAttribute());
            throw new BadCredentialsException("LDAP entry has no email");
        }

        NtrlocPrincipal principal = principals.byEmail(email)
                .or(() -> provisioner.provisionIfEnabled(email, displayName(entry, email)))
                .orElseThrow(() -> {
                    LOG.info("LDAP login for {} rejected: no ntrloc account, and auto-provisioning is off", email);
                    return new BadCredentialsException("No ntrloc account for this LDAP user");
                });
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private String displayName(DirContextOperations entry, String email) {
        for (String attribute : List.of(properties.getDisplayNameAttribute(), "cn")) {
            String value = entry.getStringAttribute(attribute);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return email;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
