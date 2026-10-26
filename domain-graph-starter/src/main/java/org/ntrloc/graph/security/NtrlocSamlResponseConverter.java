package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.ExternalPrincipalResolver;
import org.ntrloc.graph.db.partition.security.ExternalUserProvisioner;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.saml2.core.Saml2Error;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.ResponseAuthenticationConverter;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider.ResponseToken;
import org.springframework.security.saml2.provider.service.authentication.Saml2AssertionAuthentication;
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticationException;
import org.springframework.security.saml2.provider.service.authentication.Saml2ResponseAssertionAccessor;

import java.util.List;

// SAML login: Spring's OpenSaml5AuthenticationProvider has already verified the IdP's signed
// response; the email it asserts -- the provider's email attribute, or the NameID -- then decides
// which ntrloc account this is. SAML has no equivalent of OIDC's email_verified, so the IdP is
// trusted as an admin configured it, like an LDAP directory. An email with no ntrloc account is
// provisioned or rejected, per graph.security.auto-provision.enabled. Nothing else from the IdP
// affects what the user can do in ntrloc.
class NtrlocSamlResponseConverter implements Converter<ResponseToken, Saml2AssertionAuthentication> {

    private static final Logger LOG = LoggerFactory.getLogger(NtrlocSamlResponseConverter.class);
    private static final String REJECTED = "ntrloc_rejected";

    private final ResponseAuthenticationConverter defaults = new ResponseAuthenticationConverter();
    private final SamlProviderService providers;
    private final ExternalPrincipalResolver principals;
    private final ExternalUserProvisioner provisioner;

    NtrlocSamlResponseConverter(SamlProviderService providers, ExternalPrincipalResolver principals,
                                ExternalUserProvisioner provisioner) {
        this.providers = providers;
        this.principals = principals;
        this.provisioner = provisioner;
    }

    @Override
    public Saml2AssertionAuthentication convert(ResponseToken token) {
        Saml2AssertionAuthentication verified = (Saml2AssertionAuthentication) defaults.convert(token);
        String registrationId = verified.getRelyingPartyRegistrationId();
        SamlProvider provider = providers.enabledProvider(registrationId)
                .orElseThrow(() -> rejected(registrationId, "?", "the provider is disabled"));
        Saml2ResponseAssertionAccessor assertion = verified.getCredentials();

        String email = provider.emailAttribute().isEmpty()
                ? assertion.getNameId() : firstValue(assertion, provider.emailAttribute());
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw rejected(registrationId, assertion.getNameId(), "the IdP sent no email");
        }

        NtrlocPrincipal principal = principals.byEmail(email)
                .or(() -> provisioner.provisionIfEnabled(email, displayName(assertion, provider, email)))
                .orElseThrow(() -> rejected(registrationId, email, "no ntrloc account, and auto-provisioning is off"));
        return new Saml2AssertionAuthentication(principal, assertion, List.of(), registrationId);
    }

    private static String displayName(Saml2ResponseAssertionAccessor assertion, SamlProvider provider, String email) {
        String name = provider.displayNameAttribute().isEmpty() ? null : firstValue(assertion, provider.displayNameAttribute());
        return name == null || name.isBlank() ? email : name;
    }

    private static String firstValue(Saml2ResponseAssertionAccessor assertion, String attribute) {
        Object value = assertion.getFirstAttribute(attribute);
        return value == null ? null : value.toString();
    }

    private static Saml2AuthenticationException rejected(String provider, String who, String reason) {
        LOG.info("SAML login through {} for {} rejected: {}", provider, who, reason);
        return new Saml2AuthenticationException(new Saml2Error(REJECTED, "SAML login rejected: " + reason));
    }
}
