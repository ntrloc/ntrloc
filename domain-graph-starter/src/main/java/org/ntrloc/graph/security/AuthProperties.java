package org.ntrloc.graph.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "graph.auth")
public class AuthProperties {

    private LocalAuthProperties local = new LocalAuthProperties();
    private OAuthProperties oauth = new OAuthProperties();
    // Ant-style path patterns (e.g. "/webhooks/**") an application built on ntrloc wants open,
    // bypassing SecurityConfig's own authenticated-by-default rule -- entirely optional, left null
    // (not an empty list) when unset rather than forced to a default, since "not configured" and
    // "configured empty" are the same thing here and SecurityConfig already has to null-check this
    // regardless. Never a substitute for "/public"/"/login" -- see SecurityConfig's own comment on
    // why those are appended unconditionally, not something an application's list can affect.
    private List<String> openPaths;

    public LocalAuthProperties getLocal() {
        return local;
    }

    public void setLocal(LocalAuthProperties local) {
        this.local = local;
    }

    public OAuthProperties getOauth() {
        return oauth;
    }

    public void setOauth(OAuthProperties oauth) {
        this.oauth = oauth;
    }

    public List<String> getOpenPaths() {
        return openPaths;
    }

    public void setOpenPaths(List<String> openPaths) {
        this.openPaths = openPaths;
    }

    public static class LocalAuthProperties {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    // OpenID Connect providers themselves are Spring Boot's own
    // spring.security.oauth2.client.registration.* / provider.* properties; this only switches SSO on.
    public static class OAuthProperties {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
