package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "standalone", matchIfMissing = true)
public class StandaloneClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(StandaloneClusterConfigurationFactory.class);

    @Override
    public Config getObject() {
        LOG.info("Enabling standalone clustering");
        Config cfg = getBaseConfiguration();
        return cfg;
    }

}
