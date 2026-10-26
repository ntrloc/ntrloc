package org.ntrloc.graph.security;

import java.util.regex.Pattern;

// A sign-in provider's registration ID (OIDC or SAML) is part of ntrloc's URLs registered at the
// provider, such as /login/oauth2/code/{registrationId}, so it's kept URL-safe and lowercase.
final class ProviderRegistrationIds {

    private static final Pattern REGISTRATION_ID = Pattern.compile("[a-z0-9]([a-z0-9-]{0,48}[a-z0-9])?");

    private ProviderRegistrationIds() {
    }

    static void validate(String registrationId) {
        if (!REGISTRATION_ID.matcher(registrationId).matches()) {
            throw new IllegalArgumentException("The registration ID must be lowercase letters, digits and hyphens");
        }
    }
}
