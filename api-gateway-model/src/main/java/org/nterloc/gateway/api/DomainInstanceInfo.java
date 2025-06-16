package org.nterloc.gateway.api;

import java.util.StringJoiner;

public class DomainInstanceInfo {

    private String domainName;

    private String instanceId;

    private String host;

    private int port;

    private String schemaUri = "graphql";

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getSchemaUri() {
        return schemaUri;
    }

    public void setSchemaUri(String schemaUri) {
        this.schemaUri = schemaUri;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", DomainInstanceInfo.class.getSimpleName() + "[", "]")
                .add("domainName='" + domainName + "'")
                .add("instanceId='" + instanceId + "'")
                .add("host='" + host + "'")
                .add("port=" + port)
                .add("schemaUri='" + schemaUri + "'")
                .toString();
    }
}
