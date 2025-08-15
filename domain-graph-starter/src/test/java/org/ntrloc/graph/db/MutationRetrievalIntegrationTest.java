package org.ntrloc.graph.db;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.GraphQLAutoConfiguration;
import org.ntrloc.graph.JanusAutoConfiguration;
import org.ntrloc.graph.cluster.config.StandaloneClusterConfigurationFactory;
import org.ntrloc.graph.cluster.impl.ClusterServiceImpl;
import org.ntrloc.graph.db.impl.EntityManagerImpl;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.db.storage.impl.BlockDeviceBinaryStorageAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.cassandra.CassandraAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

import java.util.Map;
import java.util.Set;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {CassandraAutoConfiguration.class})
@EnableConfigurationProperties(BinaryStorageAdapterConfiguration.class)
@ContextConfiguration(classes = {EntityManagerImpl.class, SchemaManagerImpl.class, JanusAutoConfiguration.class,
        BlockDeviceBinaryStorageAdapter.class, StandaloneClusterConfigurationFactory.class, ClusterServiceImpl.class,
        GraphQLAutoConfiguration.class
})
class MutationRetrievalIntegrationTest {

    @Autowired
    private SchemaManager schemaManager;


    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void yamlProperties(DynamicPropertyRegistry registry) {
        var yaml = """
                spring.main.web-application-type: reactive
                storage.backend: inmemory
                cache.tx-cache-size: 0
                
                binary:
                  storage:
                    strategy: block
                    block:
                      location: target/storage-temp
                      autocreate: true
                logging:
                  level:
                    root: debug
                
                """;
        Yaml parser = new Yaml();
        Map<String, Object> map = parser.load(yaml);
        flattenYamlProperties("", map, registry);
    }

    private static void flattenYamlProperties(String prefix, Map<String, Object> properties,
                                              DynamicPropertyRegistry registry) {
        properties.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                flattenYamlProperties(fullKey, (Map<String, Object>) value, registry);
            } else {
                registry.add(fullKey, () -> value.toString());
            }
        });
    }

    @Test
    public void testCreateEntity() throws InterruptedException {

        EntityDefinition definition = new EntityDefinition();
        definition.setName("Photo");
        definition.setProperties(Set.of(
                new PropertyDefinition("name", PropertyType.STRING, "photo name")
        ));
        schemaManager.createEntityDefinition(definition);

        RestClient client = RestClient.create();
        Thread.sleep(2000);

    }

}
