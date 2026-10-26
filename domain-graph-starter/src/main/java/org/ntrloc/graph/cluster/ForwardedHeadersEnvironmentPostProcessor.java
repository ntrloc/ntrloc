package org.ntrloc.graph.cluster;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

// ntrloc normally runs behind a load balancer that terminates TLS (an AWS ALB, ingress-nginx, ...),
// and builds absolute URLs from each request: OIDC redirect URIs, SAML entity IDs and endpoints,
// whether the session cookie is Secure. Those must describe the public address the browser used,
// which only the balancer's X-Forwarded-* headers carry. "native" is Tomcat's RemoteIpValve, which
// honors them only from private and loopback addresses -- where a balancer connects from -- so a
// client reaching a node directly can't spoof them.
//
// addLast: a default, so a deployment can still choose its own strategy.
public class ForwardedHeadersEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addLast(
                new MapPropertySource("ntrlocForwardedHeadersDefault", Map.of("server.forward-headers-strategy", "native")));
    }
}
