package org.ntrloc.graph.db.partition.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "graph.security")
public class SecurityProperties {

    private boolean seedTestData = false;
    private AutoProvision autoProvision = new AutoProvision();

    public boolean isSeedTestData() {
        return seedTestData;
    }

    public void setSeedTestData(boolean seedTestData) {
        this.seedTestData = seedTestData;
    }

    public AutoProvision getAutoProvision() {
        return autoProvision;
    }

    public void setAutoProvision(AutoProvision autoProvision) {
        this.autoProvision = autoProvision;
    }

    // Whether someone authenticated by an external identity provider (LDAP, OAuth), but with no
    // ntrloc account yet, gets one created at login (enabled) or has the login rejected.
    public static class AutoProvision {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
