package org.ntrloc.graph.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ntrloc.auth")
public class AuthProperties {

    private LocalAuthProperties local = new LocalAuthProperties();
    private OAuthProperties oauth = new OAuthProperties();
    private LdapProperties ldap = new LdapProperties();
    // Ant-style path patterns (e.g. "/webhooks/**") an application built on ntrloc wants open,
    // bypassing SecurityConfig's own authenticated-by-default rule -- entirely optional, left null
    // (not an empty list) when unset rather than forced to a default, since "not configured" and
    // "configured empty" are the same thing here and SecurityConfig already has to null-check this
    // regardless. Never a substitute for "/public"/"/login" -- see SecurityConfig's own comment on
    // why those are appended unconditionally, not something an application's list can affect.
    private List<String> openPaths;

    // getters/setters for all three


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

    public LdapProperties getLdap() {
        return ldap;
    }

    public void setLdap(LdapProperties ldap) {
        this.ldap = ldap;
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

    public static class OAuthProperties {
        private boolean enabled = false;
        private String issuerUri;
        private String clientId;
        private String clientSecret;
        private String provider;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }
    }

    public static class LdapProperties {
        private boolean enabled = false;
        private String url;
        private String base;
        private String username;
        private String password;
        private String userDnPattern = "uid={0},ou=people";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getBase() {
            return base;
        }

        public void setBase(String base) {
            this.base = base;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getUserDnPattern() {
            return userDnPattern;
        }

        public void setUserDnPattern(String userDnPattern) {
            this.userDnPattern = userDnPattern;
        }
    }
}
