package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.ExternalUserProvisioner;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.security.LdapConfigurationService.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.NamingException;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;

// LDAP login: the directory checks the password (search for the user, then bind as them), and the
// entry's email attribute decides which ntrloc account this is. An email with no ntrloc account is
// provisioned or rejected, per graph.security.auto-provision.enabled. Nothing else from the
// directory -- groups in particular -- affects what the user can do in ntrloc.
//
// Always registered; the directory itself comes from LdapConfigurationService on each login, so an
// admin's change applies to the next one. With LDAP disabled this abstains, and when the directory
// can't be reached it fails with an exception the AuthenticationManager moves past -- either way,
// local login still gets its turn, so a bad LDAP configuration can't lock admins out.
public class NtrlocLdapAuthenticationProvider implements AuthenticationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(NtrlocLdapAuthenticationProvider.class);

    private final LdapConfigurationService ldap;
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;

    public NtrlocLdapAuthenticationProvider(LdapConfigurationService ldap, ExternalPrincipalResolver principals,
                                            ExternalUserProvisioner provisioner) {
        this.ldap = ldap;
        this.principals = principals;
        this.provisioner = provisioner;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        Optional<Directory> directory = ldap.directory();
        if (directory.isEmpty()) {
            return null;
        }
        DirContextOperations entry;
        try {
            entry = directory.get().authenticator().authenticate((UsernamePasswordAuthenticationToken) authentication);
        } catch (NamingException e) {
            LOG.warn("LDAP login for {} failed: the directory couldn't be used ({})", authentication.getName(), e.getMessage());
            throw new AuthenticationServiceException("LDAP directory unavailable", e);
        }

        String emailAttribute = directory.get().settings().emailAttribute();
        String email = entry.getStringAttribute(emailAttribute);
        if (email == null || email.isBlank()) {
            LOG.info("LDAP login for {} rejected: its entry has no {} attribute", entry.getDn(), emailAttribute);
            throw new BadCredentialsException("LDAP entry has no email");
        }

        String displayNameAttribute = directory.get().settings().displayNameAttribute();
        NtrlocPrincipal principal = principals.byEmail(email)
                .or(() -> provisioner.provisionIfEnabled(email, displayName(entry, displayNameAttribute, email)))
                .orElseThrow(() -> {
                    LOG.info("LDAP login for {} rejected: no ntrloc account, and auto-provisioning is off", email);
                    return new BadCredentialsException("No ntrloc account for this LDAP user");
                });
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
    }

    private static String displayName(DirContextOperations entry, String displayNameAttribute, String email) {
        for (String attribute : List.of(displayNameAttribute, "cn")) {
            String value = attribute.isEmpty() ? null : entry.getStringAttribute(attribute);
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
