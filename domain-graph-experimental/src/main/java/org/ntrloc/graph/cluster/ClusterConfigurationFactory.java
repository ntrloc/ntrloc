package org.ntrloc.graph.cluster;

import com.hazelcast.config.Config;
import org.springframework.beans.factory.FactoryBean;

public interface ClusterConfigurationFactory extends FactoryBean<Config> {

    default Class<?> getObjectType() {
        return Config.class;
    }

    default boolean isSingleton() {
        return true;
    }

}
