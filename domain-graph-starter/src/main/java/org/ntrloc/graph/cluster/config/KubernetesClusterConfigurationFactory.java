package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.KubernetesConfig;
import com.hazelcast.config.ListConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.ScheduledExecutorConfig;
import com.hazelcast.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "kubernetes")
public class KubernetesClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(KubernetesClusterConfigurationFactory.class);

    @Value("${cluster.port:5701}")
    private int servicePort;

    @Value("${cluster.name:}")
    private Optional<String> serviceName;

    @Value("${cluster.label.name:}")
    private Optional<String> serviceLabelName;

    @Value("${cluster.label.value:}")
    private Optional<String> serviceLabelValue;

    public KubernetesClusterConfigurationFactory(@Value("${cluster.name:ntrloc}") String clusterName,
                                                 List<MapConfig> mapConfigs,
                                                 List<ListConfig> listConfigs,
                                                 List<TopicConfig> topicConfigs,
                                                 List<QueueConfig> queueConfigs,
                                                 List<ScheduledExecutorConfig> scheduledExecutorConfigs) {
        super(clusterName, mapConfigs, listConfigs, topicConfigs, queueConfigs, scheduledExecutorConfigs);
    }

    @Override
    public Config getObject() {
        LOG.info("Enabling Kubernetes clustering");
        Config cfg = getBaseConfiguration();

        NetworkConfig network = cfg.getNetworkConfig();
        JoinConfig joinConfig = network.getJoin();

        KubernetesConfig kubernetesConfig = joinConfig.getKubernetesConfig();
        kubernetesConfig.setEnabled(true);
        kubernetesConfig.setProperty("service-port", Integer.toString(servicePort));
        if (serviceLabelName.isPresent() && serviceLabelValue.isPresent()) {
            LOG.info("Enabling Kubernetes clustering with service label {}={}, port {}", serviceLabelName.get(), serviceLabelValue.get(), Integer.toString(servicePort));
            kubernetesConfig.setProperty("service-label-name", serviceLabelName.get());
            kubernetesConfig.setProperty("service-label-value", serviceLabelValue.get());
        } else if (serviceName.isPresent()) {
            kubernetesConfig.setProperty("service-name", serviceName.get());
            LOG.info("Enabling Kubernetes clustering with service name {}, port {}", serviceName, servicePort);
        } else {
            throw new BeanCreationException("Cannot create Kubernetes cluster configuration without a service name or service label");
        }
        return cfg;
    }

}
