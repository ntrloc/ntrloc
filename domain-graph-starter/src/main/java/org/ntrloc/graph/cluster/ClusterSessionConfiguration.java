package org.ntrloc.graph.cluster;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spring.session.config.annotation.SpringSessionHazelcastInstance;
import com.hazelcast.spring.session.config.annotation.web.http.EnableHazelcastHttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.session.autoconfigure.DefaultCookieSerializerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

// Login sessions are kept in the cluster's Hazelcast rather than the servlet container, so a
// browser signed in on one node is signed in on every node, and signing out anywhere ends it
// everywhere. A standalone node is simply a cluster of one.
@Configuration
@EnableSpringHttpSession
@EnableHazelcastHttpSession
public class ClusterSessionConfiguration {

    // ClusterServiceImpl owns the member's lifecycle; no inferred shutdown() here.
    @Bean(destroyMethod = "")
    @SpringSessionHazelcastInstance
    HazelcastInstance sessionHazelcastInstance(ClusterService clusterService) {
        return clusterService.getHazelcastInstance();
    }

    // Spring Session marks its cookie SameSite=Lax, which browsers withhold from the cross-site
    // POST a SAML IdP sends back -- and without the session, Spring rejects the response for
    // lacking its saved authentication request. Unless a deployment sets
    // server.servlet.session.cookie.same-site, leave the attribute off, as the container did.
    @Bean
    DefaultCookieSerializerCustomizer sameSiteLeftToTheBrowser(
            @Value("${server.servlet.session.cookie.same-site:}") String configuredSameSite) {
        return serializer -> {
            if (configuredSameSite.isBlank()) {
                serializer.setSameSite(null);
            }
        };
    }
}
