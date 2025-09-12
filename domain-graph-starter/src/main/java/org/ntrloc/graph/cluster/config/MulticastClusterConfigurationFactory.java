package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.ListConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.ScheduledExecutorConfig;
import com.hazelcast.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "multicast")
public class MulticastClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(MulticastClusterConfigurationFactory.class);

    @Value("${cluster.multicast.interfaces}")
    private String[] multicastInterfaces;

    @Value("${cluster.multicast.init-timeout:5}")
    private int multicastJoinTimeout;

    public MulticastClusterConfigurationFactory(@Value("${cluster.name:ntrloc}") String clusterName,
                                                List<MapConfig> mapConfigs,
                                                List<ListConfig> listConfigs,
                                                List<TopicConfig> topicConfigs,
                                                List<QueueConfig> queueConfigs,
                                                List<ScheduledExecutorConfig> scheduledExecutorConfigs) {
        super(clusterName, mapConfigs, listConfigs, topicConfigs, queueConfigs, scheduledExecutorConfigs);
    }

    @Override
    public Config getObject() {
        LOG.info("Enabling multicast clustering");
        Config cfg = getBaseConfiguration();

        NetworkConfig network = cfg.getNetworkConfig();
        network.getInterfaces().setEnabled(true);
        network.getInterfaces().setInterfaces(Arrays.asList(multicastInterfaces));
        JoinConfig joinConfig = network.getJoin();
        joinConfig.getMulticastConfig().setMulticastTimeoutSeconds(multicastJoinTimeout);
        joinConfig.getMulticastConfig().setEnabled(true);

        return cfg;
    }

}
