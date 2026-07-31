package org.ntrloc.graph;

import org.ntrloc.graph.db.partition.binary.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.security.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Test-only entry point. Nothing under main/ depends on this — it exists purely so
 * integration tests in this module have a Spring Boot context to bootstrap, since the
 * module currently ships no production Application class of its own.
 */
@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, BinaryStorageAdapterConfiguration.class})
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
