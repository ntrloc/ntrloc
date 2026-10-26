package org.ntrloc.graph.cluster;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.spring.session.config.annotation.SpringSessionHazelcastInstance;
import com.hazelcast.spring.session.config.annotation.web.http.EnableHazelcastHttpSession;
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
}
