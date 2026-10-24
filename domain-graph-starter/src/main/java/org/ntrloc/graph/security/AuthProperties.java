package org.ntrloc.graph.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "graph.auth")
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

    public static class LdapProperties {
        private boolean enabled = false;
        private String url;
        private String base;
        // Bind DN and password for searching the directory; leave both unset to search anonymously.
        private String username;
        private String password;
        // Where users are searched for (relative to base), and how: {0} is what the person typed
        // into the login form -- a uid by default, or an email with e.g. "(mail={0})".
        private String userSearchBase = "ou=people";
        private String userSearchFilter = "(uid={0})";
        // The entry attribute holding the user's email, which is what an LDAP login resolves to.
        private String emailAttribute = "mail";
        // Display name for an auto-provisioned account (see graph.security.auto-provision); falls
        // back to cn, then to the email.
        private String displayNameAttribute = "displayName";

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

        public String getUserSearchBase() {
            return userSearchBase;
        }

        public void setUserSearchBase(String userSearchBase) {
            this.userSearchBase = userSearchBase;
        }

        public String getUserSearchFilter() {
            return userSearchFilter;
        }

        public void setUserSearchFilter(String userSearchFilter) {
            this.userSearchFilter = userSearchFilter;
        }

        public String getEmailAttribute() {
            return emailAttribute;
        }

        public void setEmailAttribute(String emailAttribute) {
            this.emailAttribute = emailAttribute;
        }

        public String getDisplayNameAttribute() {
            return displayNameAttribute;
        }

        public void setDisplayNameAttribute(String displayNameAttribute) {
            this.displayNameAttribute = displayNameAttribute;
        }
    }
}
