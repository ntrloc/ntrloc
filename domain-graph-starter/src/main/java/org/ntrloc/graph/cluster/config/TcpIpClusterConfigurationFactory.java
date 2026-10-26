package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.ListConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.QueueConfig;
import com.hazelcast.config.ScheduledExecutorConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.config.TopicConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

// A fixed list of member addresses, for hosts where multicast is blocked and there's no
// Kubernetes or AWS discovery to lean on. Each node listens on exactly cluster.port -- no
// auto-increment -- so the member list everyone shares is the truth.
@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "tcp-ip")
public class TcpIpClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(TcpIpClusterConfigurationFactory.class);

    @Value("${cluster.port:5701}")
    private int port;

    // host or host:port, every node including this one
    @Value("${cluster.tcp-ip.members:}")
    private String[] members;

    // optional; restricts which local interface the member binds to (e.g. 127.0.0.1)
    @Value("${cluster.tcp-ip.interface:}")
    private String networkInterface;

    public TcpIpClusterConfigurationFactory(@Value("${cluster.name}") String clusterName,
                                            List<MapConfig> mapConfigs,
                                            List<ListConfig> listConfigs,
                                            List<TopicConfig> topicConfigs,
                                            List<QueueConfig> queueConfigs,
                                            List<ScheduledExecutorConfig> scheduledExecutorConfigs) {
        super(clusterName, mapConfigs, listConfigs, topicConfigs, queueConfigs, scheduledExecutorConfigs);
    }

    @Override
    public Config getObject() {
        List<String> memberList = Arrays.stream(members).map(String::trim).filter(m -> !m.isEmpty()).toList();
        if (memberList.isEmpty()) {
            throw new BeanCreationException("Cannot create TCP/IP cluster configuration without cluster.tcp-ip.members");
        }
        LOG.info("Enabling TCP/IP clustering on port {} with members {}", port, memberList);
        Config cfg = getBaseConfiguration();

        NetworkConfig network = cfg.getNetworkConfig();
        network.setPort(port);
        network.setPortAutoIncrement(false);
        if (!networkInterface.isBlank()) {
            network.getInterfaces().setEnabled(true);
            network.getInterfaces().setInterfaces(List.of(networkInterface));
        }

        TcpIpConfig tcpIpConfig = network.getJoin().getTcpIpConfig();
        tcpIpConfig.setMembers(memberList);
        tcpIpConfig.setEnabled(true);

        return cfg;
    }

}
