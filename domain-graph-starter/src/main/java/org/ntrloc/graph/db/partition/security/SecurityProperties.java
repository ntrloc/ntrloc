package org.ntrloc.graph.db.partition.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "graph.security")
public class SecurityProperties {

    private boolean seedTestData = false;

    public boolean isSeedTestData() {
        return seedTestData;
    }

    public void setSeedTestData(boolean seedTestData) {
        this.seedTestData = seedTestData;
    }
}
