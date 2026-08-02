package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.ListConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.ScheduledExecutorConfig;
import com.hazelcast.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public abstract class AbstractClusterConfiguration {

    private static final Logger LOG = LogManager.getLogger(AbstractClusterConfiguration.class);

    protected String clusterName;

    private List<MapConfig> mapConfigs;

    private List<ListConfig> listConfigs;

    private List<TopicConfig> topicConfigs;

    private List<QueueConfig> queueConfigs;

    private List<ScheduledExecutorConfig> scheduledExecutorConfigs;

    AbstractClusterConfiguration(@Value("${cluster.name}") String clusterName,
                                 List<MapConfig> mapConfigs,
                                 List<ListConfig> listConfigs,
                                 List<TopicConfig> topicConfigs,
                                 List<QueueConfig> queueConfigs,
                                 List<ScheduledExecutorConfig> scheduledExecutorConfigs) {
        this.clusterName = clusterName;
        this.mapConfigs = mapConfigs;
        this.listConfigs = listConfigs;
        this.topicConfigs = topicConfigs;
        this.queueConfigs = queueConfigs;
        this.scheduledExecutorConfigs = scheduledExecutorConfigs;
    }

    protected Config getBaseConfiguration() {
        LOG.info("Initializing cluster configuration");

        Config cfg = new Config();
        cfg.setInstanceName(clusterName + UUID.randomUUID().toString().replace("-", ""));
        cfg.setClusterName(clusterName);
        cfg.setProperty("hazelcast.logging.type", "log4j2");

        // we specficially do NOT want automatic network detection!
        JoinConfig joinConfig = cfg.getNetworkConfig().getJoin();
        joinConfig.getKubernetesConfig().setEnabled(false);
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(false);
        joinConfig.getAwsConfig().setEnabled(false);
        joinConfig.getAutoDetectionConfig().setEnabled(false);

        if (mapConfigs != null && !mapConfigs.isEmpty()) {
            LOG.debug("Applying cluster map configurations");
            mapConfigs.forEach(mapConfig -> {
                MapConfig clusterMapConfig = cfg.getMapConfig(mapConfig.getName());
                clusterMapConfig.setMaxIdleSeconds(mapConfig.getMaxIdleSeconds());
                LOG.debug("Applied configuration for map {}", mapConfig.getName());
            });
        } else {
            LOG.debug("No cluster map configurations found; skipping.");
        }

        if (listConfigs != null && !listConfigs.isEmpty()) {
            LOG.debug("Applying cluster list configurations");
            listConfigs.forEach(listConfig -> {
                ListConfig clusterListConfig = cfg.getListConfig(listConfig.getName());
                // no settings are applied yet
                LOG.debug("Applied configuration for list {}", clusterListConfig.getName());
            });
        } else {
            LOG.debug("No cluster list configurations found; skipping.");
        }

        if (topicConfigs != null && !topicConfigs.isEmpty()) {
            LOG.debug("Applying cluster topic configurations");
            topicConfigs.forEach(topicConfig -> {
                TopicConfig clusterTopicConfig = cfg.getTopicConfig(topicConfig.getName());
                clusterTopicConfig.setGlobalOrderingEnabled(topicConfig.isGlobalOrderingEnabled());
                clusterTopicConfig.setMultiThreadingEnabled(topicConfig.isMultiThreadingEnabled());
                clusterTopicConfig.setStatisticsEnabled(topicConfig.isStatisticsEnabled());
                // no settings are applied yet
                LOG.debug("Applied configuration for topic {}", topicConfig.getName());
            });
        } else {
            LOG.debug("No cluster topic configurations found; skipping.");
        }

        if (queueConfigs != null && !queueConfigs.isEmpty()) {
            LOG.debug("Applying cluster queue configurations");
            queueConfigs.forEach(queueConfig -> {
                QueueConfig clusterQueueConfig = cfg.getQueueConfig(queueConfig.getName());
                clusterQueueConfig.setMaxSize(queueConfig.getMaxSize());
                clusterQueueConfig.setPriorityComparatorClassName(queueConfig.getPriorityComparatorClassName());
                // no settings are applied yet
                LOG.debug("Applied configuration for queue {}", queueConfig.getName());
            });
        } else {
            LOG.debug("No cluster queue configurations found; skipping.");
        }

        if (scheduledExecutorConfigs != null && !scheduledExecutorConfigs.isEmpty()) {
            cfg.setScheduledExecutorConfigs(scheduledExecutorConfigs.stream().collect(Collectors.toMap(ScheduledExecutorConfig::getName, c -> {
                LOG.info("Applied configuration for scheduled executor {}", c.getName());
                return c;
            })));
        }

        return cfg;
    }

}
