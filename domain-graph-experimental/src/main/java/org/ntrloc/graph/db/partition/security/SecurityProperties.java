package org.ntrloc.graph.db.partition.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ntrloc.security")
public class SecurityProperties {

    private String headerName = "X-Ntrloc-User";
    private String paramName = "asUser";
    private boolean seedTestData = false;

    public String getHeaderName() {
        return headerName;
    }

    public void setHeaderName(String headerName) {
        this.headerName = headerName;
    }

    public String getParamName() {
        return paramName;
    }

    public void setParamName(String paramName) {
        this.paramName = paramName;
    }

    public boolean isSeedTestData() {
        return seedTestData;
    }

    public void setSeedTestData(boolean seedTestData) {
        this.seedTestData = seedTestData;
    }
}
