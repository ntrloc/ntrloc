package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.ListConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.ScheduledExecutorConfig;
import com.hazelcast.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "standalone", matchIfMissing = true)
public class StandaloneClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(StandaloneClusterConfigurationFactory.class);

    public StandaloneClusterConfigurationFactory(@Value("${cluster.name}") String clusterName,
                                                 List<MapConfig> mapConfigs,
                                                 List<ListConfig> listConfigs,
                                                 List<TopicConfig> topicConfigs,
                                                 List<QueueConfig> queueConfigs,
                                                 List<ScheduledExecutorConfig> scheduledExecutorConfigs) {
        super(clusterName, mapConfigs, listConfigs, topicConfigs, queueConfigs, scheduledExecutorConfigs);
    }

    @Override
    public Config getObject() {
        LOG.info("Enabling standalone clustering");
        Config cfg = getBaseConfiguration();
        return cfg;
    }

}
