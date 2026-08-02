package org.ntrloc;

import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.security.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, BinaryStorageAdapterConfiguration.class})
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
