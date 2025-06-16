package org.nterloc.graph.registry;

import org.nterloc.graph.ConditionalOnPropertyPrefix;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.StringJoiner;

@Component
@ConfigurationProperties(prefix = "nterloc.gateway")
@ConditionalOnPropertyPrefix(prefix = "nterloc.gateway")
public class GatewayConfiguration {

    private String scheme;
    private String hostname;
    private int port = 8761;
    private String registrationPath = "api/registration";
    private int renewalRateMilliseconds = 10000;

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getRegistrationPath() {
        return registrationPath;
    }

    public void setRegistrationPath(String registrationPath) {
        this.registrationPath = registrationPath;
    }

    public int getRenewalRateMilliseconds() {
        return renewalRateMilliseconds;
    }

    public void setRenewalRateMilliseconds(int renewalRateMilliseconds) {
        this.renewalRateMilliseconds = renewalRateMilliseconds;
    }

    @Override
    public String toString() {
        return new StringJoiner(", ", GatewayConfiguration.class.getSimpleName() + "[", "]")
                .add("scheme='" + scheme + "'")
                .add("hostname='" + hostname + "'")
                .add("port=" + port)
                .add("registrationPath='" + registrationPath + "'")
                .add("renewalRateMilliseconds=" + renewalRateMilliseconds)
                .toString();
    }
}
