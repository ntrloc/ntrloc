package org.ntrloc.graph.db;

import com.netflix.graphql.dgs.client.GraphQLClient;
import com.netflix.graphql.dgs.client.GraphQLResponse;
import com.netflix.graphql.dgs.client.RestClientGraphQLClient;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.GraphQLAutoConfiguration;
import org.ntrloc.graph.JanusAutoConfiguration;
import org.ntrloc.graph.cluster.config.StandaloneClusterConfigurationFactory;
import org.ntrloc.graph.cluster.impl.ClusterServiceImpl;
import org.ntrloc.graph.db.impl.EntityManagerImpl;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapterConfiguration;
import org.ntrloc.graph.db.storage.impl.BlockDeviceBinaryStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {CassandraAutoConfiguration.class})
@EnableConfigurationProperties(BinaryStorageAdapterConfiguration.class)
@ContextConfiguration(classes = {EntityManagerImpl.class, SchemaManagerImpl.class, JanusAutoConfiguration.class,
        BlockDeviceBinaryStorageAdapter.class, StandaloneClusterConfigurationFactory.class, ClusterServiceImpl.class,
        GraphQLAutoConfiguration.class
})
class MutationRetrievalIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(MutationRetrievalIntegrationTest.class);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private GraphTraversalSource traversalSource;

    private Integer port;

    final GraphQLClient graphQlClient;

    @DynamicPropertySource
    static void yamlProperties(DynamicPropertyRegistry registry) {
        var yaml = """
                spring:
                  main.web-application-type: reactive
                  graphql.schema.printer.enabled: true
                
                cache.tx-cache-size: 0
                graph.index.lucene.directory: target/db/lucene
                
                binary:
                  storage:
                    strategy: block
                    block:
                      location: target/storage-temp
                      autocreate: true
                logging:
                  level:
                    root: DEBUG
                
                """;
        Yaml parser = new Yaml();
        Map<String, Object> map = parser.load(yaml);
        flattenYamlProperties("", map, registry);
    }

    private static void flattenYamlProperties(String prefix, Map<String, Object> properties, DynamicPropertyRegistry registry) {
        properties.forEach((key, value) -> {
            String fullKey = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map) {
                flattenYamlProperties(fullKey, (Map<String, Object>) value, registry);
            } else {
                registry.add(fullKey, () -> value.toString());
            }
        });
    }

    MutationRetrievalIntegrationTest(@LocalServerPort Integer port) {
        this.port = port;
        /*
        WebClient webClient = WebClient.create("http://localhost:" + port.toString() + "/graphql");
        graphQlClient = new WebClientGraphQLClient(webClient);

         */

        LOG.info("Running on port {}", port);

        RestClient restClient = RestClient.create("http://localhost:" + port.toString() + "/graphql");
        graphQlClient = new RestClientGraphQLClient(restClient);
    }

    private void initSchema() {
        EntityDefinition photoEntity = new EntityDefinition();
        photoEntity.setName("Photo");
        photoEntity.setDescription("A photo");

        PropertyDefinition photoName = new PropertyDefinition("name", PropertyType.STRING, "photo name");
        PropertyDefinition photoNumber = new PropertyDefinition("number", PropertyType.INT, "photo number");
        photoEntity.setProperties(Set.of(photoName, photoNumber));

        PropertyDefinition title1 = new PropertyDefinition("title1", PropertyType.STRING, "title 1");
        PropertyDefinition title2 = new PropertyDefinition("title2", PropertyType.STRING, "title 2");

        PropertyGroupDefinition titleGroup = new PropertyGroupDefinition("Titles", "photo titles", Set.of(title1, title2));
        photoEntity.setPropertyGroups(Set.of(titleGroup));

        schemaManager.createEntityDefinition(photoEntity);

        EntityDefinition photographerEntity = new EntityDefinition();
        photographerEntity.setName("Photographer");
        photographerEntity.setDescription("A photographer");
        PropertyDefinition photographerName = new PropertyDefinition("name", PropertyType.STRING, "photographer name");
        photographerEntity.setProperties(Set.of(photographerName));

        schemaManager.createEntityDefinition(photographerEntity);

        RelationshipDefinition photoRelationship = new RelationshipDefinition();
        photoRelationship.setSourceEntity("Photographer");
        photoRelationship.setTargetEntity("Photo");
        photoRelationship.setSourceCardinality(new Cardinality(0, 1) );
        photoRelationship.setTargetCardinality(new Cardinality(0, 1) );
        photoRelationship.setSourceVersionAction(RelationshipDefinition.VersionAction.NONE);
        photoRelationship.setTargetVersionAction(RelationshipDefinition.VersionAction.NONE);
        photoRelationship.setName("CREATED");
        photoRelationship.setSourceLabel("created");
        photoRelationship.setTargetLabel("createdBy");

        PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
        photoRelationship.setProperties(Set.of(createdCountProperty));

        schemaManager.createRelationshipDefinition(photoRelationship);
    }

    @Test
    void testCreateEntity() {
        AtomicBoolean schemaUpdated = new AtomicBoolean(false);
        schemaManager.addSchemaChangeReaction(() -> {
            schemaUpdated.set(true);
        });

        initSchema();

        await().atMost(5, TimeUnit.SECONDS).until(() -> schemaUpdated.get());

        var schema = RestClient.create().get().uri("http://localhost:" + port + "/graphql/schema").retrieve().body(String.class);
        LOG.info("Schema: {}", schema);

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { properties: { name: "photo1" } } } },
                         { Photo: { create: { properties: { name: "photo2" } } } },
                         { Photographer: { create: { properties: { name: "Bill" } } } }
                     ]) {
                        created {
                            entityType
                            id
                        }
                     }
                }
                """;
        LOG.info("Running mutation");

        long start = System.currentTimeMillis();
        GraphQLResponse response = graphQlClient.executeQuery(mutation);
        long end = System.currentTimeMillis();
        LOG.info("Mutation took {} ms", end - start);

        var vertices = traversalSource.V().hasLabel("Photo", "Photographer").elementMap().toList();

        /*
        LOG.info("Running query");
        var query = "{ Photo { properties { name } } } ";
        start = System.currentTimeMillis();
        response = graphQlClient.executeQuery(query);
        end = System.currentTimeMillis();
        LOG.info("Query took {} ms", end - start);

         */

        List<?> ids = response.extractValueAsObject("execute.created[*].id", List.class);
        assertFalse(ids.isEmpty());
    }

}
