package org.ntrloc.graph.cluster.config;

import com.hazelcast.config.AwsConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.NetworkConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.cluster.ClusterConfigurationFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(value = "cluster.strategy", havingValue = "aws")
public class AwsClusterConfigurationFactory extends AbstractClusterConfiguration implements ClusterConfigurationFactory {

    private static final Logger LOG = LogManager.getLogger(AwsClusterConfigurationFactory.class);

    @Value("${cluster.aws.iamRole}")
    private String iamRole;

    @Value("${cluster.aws.tagName}")
    private String tagName;

    @Value("${cluster.aws.tagValue}")
    private String tagValue;

    public AwsClusterConfigurationFactory(@Value("${cluster.name:ntrloc}") String clusterName) {
        super(clusterName);
    }

    @Override
    public Config getObject() {
        LOG.info("Enabling AWS clustering");
        Config cfg = getBaseConfiguration();

        NetworkConfig network = cfg.getNetworkConfig();
        JoinConfig joinConfig = network.getJoin();
        AwsConfig awsConfig = joinConfig.getAwsConfig();
        awsConfig.setEnabled(true);
        awsConfig.setProperty("iam-role", iamRole);
        awsConfig.setProperty("tag-key", tagName);
        awsConfig.setProperty("tag-value", tagValue);

        return cfg;
    }

}
