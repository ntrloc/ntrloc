package org.ntrloc.graph;

import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(BinaryStorageAdapterConfiguration.class)
@ComponentScan("org.ntrloc.graph.db.storage")
public class StorageAutoConfiguration {
}
