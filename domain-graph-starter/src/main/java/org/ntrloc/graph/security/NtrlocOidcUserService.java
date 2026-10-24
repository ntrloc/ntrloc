package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.ExternalUserProvisioner;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

// OIDC login: Spring's own OidcUserService validates the ID token and loads the claims; the
// provider's *verified* email then decides which ntrloc account this is. An unverified email is
// never trusted -- otherwise anyone at a provider that lets users set their own email could sign
// in as an existing ntrloc user. An email with no ntrloc account is provisioned or rejected, per
// graph.security.auto-provision.enabled. Nothing else from the provider affects what the user can
// do in ntrloc.
class NtrlocOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

    private static final Logger LOG = LoggerFactory.getLogger(NtrlocOidcUserService.class);

    private final OidcUserService delegate = new OidcUserService();
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;

    NtrlocOidcUserService(ExternalPrincipalResolver principals, ExternalUserProvisioner provisioner) {
        this.principals = principals;
        this.provisioner = provisioner;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest request) {
        OidcUser oidcUser = delegate.loadUser(request);
        String provider = request.getClientRegistration().getRegistrationId();

        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            throw rejected(provider, oidcUser.getSubject(), "the provider sent no email");
        }
        if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
            throw rejected(provider, email, "the provider hasn't verified the email");
        }

        NtrlocPrincipal principal = principals.byEmail(email)
                .or(() -> provisioner.provisionIfEnabled(email, displayName(oidcUser, email)))
                .orElseThrow(() -> rejected(provider, email, "no ntrloc account, and auto-provisioning is off"));
        return new NtrlocOidcUser(oidcUser, principal);
    }

    private static String displayName(OidcUser oidcUser, String email) {
        for (String value : new String[]{oidcUser.getFullName(), oidcUser.getPreferredUsername()}) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return email;
    }

    private static OAuth2AuthenticationException rejected(String provider, String who, String reason) {
        LOG.info("OIDC login via {} for {} rejected: {}", provider, who, reason);
        return new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "OIDC login rejected: " + reason);
    }
}
