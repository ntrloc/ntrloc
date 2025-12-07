package org.ntrloc.graph.graphql;

import com.netflix.graphql.dgs.client.GraphQLClient;
import com.netflix.graphql.dgs.client.GraphQLError;
import com.netflix.graphql.dgs.client.GraphQLResponse;
import com.netflix.graphql.dgs.client.RestClientGraphQLClient;
import net.javacrumbs.jsonunit.core.Option;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.janusgraph.core.JanusGraph;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.GraphQLAutoConfiguration;
import org.ntrloc.graph.JanusAutoConfiguration;
import org.ntrloc.graph.cluster.config.StandaloneClusterConfigurationFactory;
import org.ntrloc.graph.cluster.impl.ClusterServiceImpl;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.impl.ItemManagerImpl;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableAutoConfiguration(exclude = {CassandraAutoConfiguration.class})
@EnableConfigurationProperties(BinaryStorageAdapterConfiguration.class)
@ContextConfiguration(classes = {ItemManagerImpl.class, SchemaManagerImpl.class, JanusAutoConfiguration.class,
        BlockDeviceBinaryStorageAdapter.class, StandaloneClusterConfigurationFactory.class, ClusterServiceImpl.class,
        GraphQLAutoConfiguration.class
})
class GraphQLMutationRetrievalIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLMutationRetrievalIntegrationTest.class);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private ItemManager itemManager;

    @Autowired
    private GraphTraversalSource traversalSource;

    @Autowired
    private JanusGraph janusGraph;

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

    GraphQLMutationRetrievalIntegrationTest(@LocalServerPort Integer port) {
        this.port = port;
        LOG.info("Running on port {}", port);
        RestClient restClient = RestClient.create("http://localhost:" + port.toString() + "/graphql");
        graphQlClient = new RestClientGraphQLClient(restClient);
    }

    private void initSchema() {

        try {
            traversalSource.V().drop().iterate();
        } catch (Exception e) {

        }

        try {
            traversalSource.E().drop().iterate();
        } catch (Exception e) {

        }

        traversalSource.tx().commit();

        AtomicBoolean schemaUpdated = new AtomicBoolean(false);
        schemaManager.addSchemaChangeReaction(() -> {
            schemaUpdated.set(true);
        });


        ItemDefinition photoEntity = new ItemDefinition();
        photoEntity.setName("Photo");
        photoEntity.setDescription("A photo");

        PropertyDefinition photoName = new PropertyDefinition("name", PropertyType.STRING, "photo name");
        PropertyDefinition photoNumber = new PropertyDefinition("number", PropertyType.INT, "photo number");
        PropertyDefinition photoBoolean = new PropertyDefinition("boolean", PropertyType.BOOLEAN, "photo boolean");
        PropertyDefinition photoDate = new PropertyDefinition("date", PropertyType.DATE, "photo date");
        PropertyDefinition photoDateTime = new PropertyDefinition("dateTime", PropertyType.DATETIME, "photo dateTime");
        PropertyDefinition photoDouble = new PropertyDefinition("double", PropertyType.DOUBLE, "photo double");
        PropertyDefinition photoBinary = new PropertyDefinition("binary", PropertyType.BINARY, "photo binary");
        PropertyDefinition photoStringList = new PropertyDefinition("stringList", PropertyType.STRING_LIST, "photo string list");
        PropertyDefinition photoIntList = new PropertyDefinition("intList", PropertyType.INT_LIST, "photo int list");
        PropertyDefinition photoBooleanList = new PropertyDefinition("booleanList", PropertyType.BOOLEAN_LIST, "photo boolean list");
        PropertyDefinition photoDateList = new PropertyDefinition("dateList", PropertyType.DATE_LIST, "photo date list");
        PropertyDefinition photoDateTimeList = new PropertyDefinition("dateTimeList", PropertyType.DATETIME_LIST, "photo dateTime list");
        PropertyDefinition photoDoubleList = new PropertyDefinition("doubleList", PropertyType.DOUBLE_LIST, "photo double list");

        photoEntity.setProperties(Set.of(photoName, photoNumber, photoBoolean, photoDate, photoDateTime, photoDouble, photoBinary, photoStringList, photoIntList, photoBooleanList, photoDateList, photoDateTimeList, photoDoubleList));

        PropertyDefinition title1 = new PropertyDefinition("title1", PropertyType.STRING, "title 1");
        PropertyDefinition title2 = new PropertyDefinition("title2", PropertyType.STRING, "title 2");

        PropertyGroupDefinition titleGroup = new PropertyGroupDefinition("Titles", "photo titles", Set.of(title1, title2));
        photoEntity.setPropertyGroups(Set.of(titleGroup));

        schemaManager.createItemDefinition(photoEntity);

        ItemDefinition photographerEntity = new ItemDefinition();
        photographerEntity.setName("Photographer");
        photographerEntity.setDescription("A photographer");
        PropertyDefinition photographerName = new PropertyDefinition("name", PropertyType.STRING, "photographer name");
        photographerEntity.setProperties(Set.of(photographerName));

        schemaManager.createItemDefinition(photographerEntity);

        LinkDefinition photoRelationship = new LinkDefinition();
        photoRelationship.setSourceItemType("Photographer");
        photoRelationship.setTargetItemType("Photo");
        photoRelationship.setSourceCardinality(new Cardinality(0, 1) );
        photoRelationship.setTargetCardinality(new Cardinality(0, 1) );
        photoRelationship.setSourceVersionAction(LinkDefinition.VersionAction.NONE);
        photoRelationship.setTargetVersionAction(LinkDefinition.VersionAction.NONE);
        photoRelationship.setSourceLabel("created");
        photoRelationship.setTargetLabel("createdBy");

        PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
        photoRelationship.setProperties(Set.of(createdCountProperty));

        schemaManager.createLinkDefinition(photoRelationship);

        await().atMost(5, TimeUnit.SECONDS).until(() -> schemaUpdated.get());

        var schema = RestClient.create().get().uri("http://localhost:" + port + "/graphql/schema").retrieve().body(String.class);
        LOG.info("Schema: {}", schema);
    }

    @Test
    void testCreateItemWithAllDataTypes() throws IOException {
        initSchema();

        var writer = itemManager.openWriter();
        writer.write(new byte[] { 1, 2, 3, 4, 5});
        String dataNodeId = itemManager.commitBinary(writer);

        var startvertices = traversalSource.V()
                .project("label", "values")
                .by(__.label())
                .by(__.valueMap())
                .toList();
        LOG.info("Vertices before mutation: {}", startvertices);

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { properties: {
                            name: "photo1"
                            number: 23,
                            boolean: true,
                            date: "2021-01-01",
                            dateTime: "2022-01-01T07:22:40Z",
                            double: 123.456,
                            binary: "{DATA_NODE_ID}",
                            stringList: ["a", "b", "c"],
                            intList: [1, 2, 3],
                            booleanList: [true, false],
                            dateList: ["2021-01-01", "2021-01-02"],
                            dateTimeList: ["2022-01-01T07:22:40Z", "2022-01-02T08:22:40Z"],
                            doubleList: [123.456, 789.123]
                         } } } }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """.replace("{DATA_NODE_ID}", dataNodeId);

        long start = System.currentTimeMillis();
        GraphQLResponse response = graphQlClient.executeQuery(mutation);
        List<GraphQLError> errors = response.getErrors();
        assertTrue(errors.isEmpty(), "Found errors: " + errors);
        long end = System.currentTimeMillis();
        LOG.info("Mutation took {} ms", end - start);

        var query = """
                {
                    Photo {
                        properties {
                            name
                            number
                            boolean
                            date
                            dateTime
                            double
                            binary {
                                md5
                                sha256
                                mimeType
                                length
                                url
                            }
                            stringList
                            intList
                            booleanList
                            dateList
                            dateTimeList
                            doubleList
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        System.out.println("hi");

    }

    @Test
    void testCreateAndLinkItems() {
        initSchema();

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { ref: "photo1" properties: { name: "photo1" number: 23 } } } },
                         { Photo: { create: { ref: "photo2" properties: { name: "photo2" number: 34 } } } },
                         {
                            Photographer: {
                                create: {
                                    properties: { name: "Bill" }
                                    links: {
                                        created: [
                                            { properties: { count: 2 } target: { ref: "photo1" } },
                                            { properties: { count: 5} target: { ref: "photo2" } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        created {
                            itemType
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

        var expectedMutationResponse = """
                {
                    "data": {
                        "execute": {
                            "created": [
                                { "itemType": "Photo", "id": "${json-unit.any-string}" },
                                { "itemType": "Photographer", "id": "${json-unit.any-string}" },
                                { "itemType": "Photo", "id": "${json-unit.any-string}" }
                            ]
                        }
                    }
                }
                """;
        try {
            assertThatJson(response.getJson())
                    .when(Option.IGNORING_ARRAY_ORDER)
                    .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                    .isEqualTo(expectedMutationResponse);

        } catch (AssertionError e) {
            LOG.error("Mutation response: {}", response.getJson());
            throw e;
        }

        List<?> ids = response.extractValueAsObject("execute.created[*].id", List.class);
        assertFalse(ids.isEmpty());

        LOG.info("Running query");
        var query = """
                {
                    Photo {
                        properties {
                            name
                            number
                        }
                        links {
                            createdby {
                                properties { count }
                                source {
                                    properties {
                                        name
                                    }
                                }
                            }
                        }
                    }
                    Photographer {
                        properties { name }
                        links {
                            created {
                                properties { count }
                                target {
                                    properties { name }
                                }
                            }
                        }
                    }
                }
                """;
        start = System.currentTimeMillis();
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());
        end = System.currentTimeMillis();

        var expectedQueryResult = """
                {
                    "data": {
                        "Photo": [
                            { 
                                "properties": { "name": "photo1", "number": 23 },
                                "links": {
                                    "createdby": [
                                        { "properties": { "count": 2 }, "source": { "properties": { "name": "Bill" } } }
                                    ]
                                }
                            },
                            {
                                "properties": { "name": "photo2", "number": 34 },
                                "links": {
                                    "createdby": [
                                        { "properties": { "count": 5 }, "source": { "properties": { "name": "Bill" } } }
                                    ]
                                }
                            }
                        ],
                        "Photographer": [
                            {
                                "properties": { "name": "Bill" },
                                "links": {
                                    "created": [
                                        { "properties": { "count": 5 }, "target": { "properties": { "name": "photo2" } } },
                                        { "properties": { "count": 2 }, "target": { "properties": { "name": "photo1" } } }
                                    ]
                                }
                            }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                .isEqualTo(expectedQueryResult);

        LOG.info("Query took {} ms", end - start);
    }

    @Test
    void testUpdateItem() {
        initSchema();

        var create = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { properties: { name: "photo1" number: 23 } } } }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        LOG.info("Running mutation");
        GraphQLResponse response = graphQlClient.executeQuery(create);
        List<String> ids = response.extractValueAsObject("execute.created[*].id", List.class);
        String photoId = ids.get(0);

        var update = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photo: {
                                update: {
                                    where: { id: "%s" }
                                    properties: { name: "photo2" number: null }
                                }
                            }
                         }
                     ]) {
                        updated {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photoId);
        LOG.info("Running mutation");
        response = graphQlClient.executeQuery(update);
        assertFalse(response.getData().isEmpty());
        var query = """
                {
                    Photo {
                        properties {
                            name
                            number
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photo": [
                            { "properties": { "name": "photo2", "number": null } }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                .isEqualTo(expectedQueryResult);
    }

    @Test
    void testDeleteItem() {
        initSchema();

        var create = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { properties: { name: "photo1" number: 23 } } } }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        LOG.info("Running mutation");
        GraphQLResponse response = graphQlClient.executeQuery(create);
        List<String> ids = response.extractValueAsObject("execute.created[*].id", List.class);
        String photoId = ids.get(0);

        var delete = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photo: {
                                delete: { where: { id: "%s" } }
                            }
                         }
                     ]) {
                        deleted {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photoId);
        LOG.info("Running mutation");
        response = graphQlClient.executeQuery(delete);
        assertFalse(response.getData().isEmpty());
        var query = """
                {
                    Photo {
                        properties {
                            name
                            number
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photo": [ ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                .isEqualTo(expectedQueryResult);
    }

    @Test
    void testLinkExistingItems() {
        initSchema();

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { ref: "photo1" properties: { name: "photo1" number: 23 } } } },
                         { Photographer: { create: { properties: { name: "Bill" } } } }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        GraphQLResponse response = graphQlClient.executeQuery(mutation);

        List<Map<String, Object>> responses = response.extractValueAsObject("execute.created", List.class);

        Optional<Map<String, Object>> photographerMap = responses.stream().filter(r -> r.get("itemType").equals("Photographer")).findFirst();
        Optional<Map<String, Object>> photoMap = responses.stream().filter(r -> r.get("itemType").equals("Photo")).findFirst();

        assertTrue(photographerMap.isPresent());
        assertTrue(photoMap.isPresent());

        String photographerId = photographerMap.get().get("id").toString();
        String photoId = photoMap.get().get("id").toString();

        var update = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photographer: {
                                update: {
                                    where: { id: "%s" }
                                    links: {
                                        created: [
                                            { create: { target: { id: "%s" } } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        updated {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photographerId, photoId);
        LOG.info("Running mutation");
        response = graphQlClient.executeQuery(update);
        assertTrue(response.getErrors().isEmpty());

        var query = """
                {
                    Photographer {
                        properties {
                            name
                        } links {
                            created {
                                target { properties { name } }
                            }
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photographer": [
                            {
                                "properties": { "name": "Bill" },
                                "links": {
                                    "created": [ { "target": { "properties": { "name": "photo1" } } } ]
                                }
                            }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                .isEqualTo(expectedQueryResult);

    }

    @Test
    void testLinkNewAndExistingItems() {
        initSchema();

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { properties: { name: "photo1" number: 23 } } } },
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        GraphQLResponse response = graphQlClient.executeQuery(mutation);

        List<Map<String, Object>> responses = response.extractValueAsObject("execute.created", List.class);
        Optional<Map<String, Object>> photoMap = responses.stream().filter(r -> r.get("itemType").equals("Photo")).findFirst();

        assertTrue(photoMap.isPresent());

        String photoId = photoMap.get().get("id").toString();

        var create = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photographer: {
                                create: {
                                    properties: { name: "Bill" }
                                    links: {
                                        created: [
                                            { properties: { count: 2 } target: { id: "%s" } },
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        updated {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photoId);
        LOG.info("Running mutation");
        response = graphQlClient.executeQuery(create);
        assertTrue(response.getErrors().isEmpty());

        var query = """
                {
                    Photographer {
                        properties {
                            name
                        } links {
                            created {
                                target { properties { name } }
                            }
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photographer": [
                            {
                                "properties": { "name": "Bill" },
                                "links": {
                                    "created": [ { "target": { "properties": { "name": "photo1" } } } ]
                                }
                            }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .when(Option.IGNORING_EXTRA_ARRAY_ITEMS)
                .isEqualTo(expectedQueryResult);
    }

    @Test
    void testUpdateLink() {
        initSchema();

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { ref: "photo1" properties: { name: "photo1" number: 23 } } } }
                         {
                            Photographer: {
                                create: {
                                    properties: { name: "Bill" }
                                    links: {
                                        created: [
                                            { properties: { count: 2 } target: { ref: "photo1" } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        GraphQLResponse response = graphQlClient.executeQuery(mutation);
        assertTrue(response.getErrors().isEmpty());

        var query = """
                {
                    Photographer {
                        id
                        properties {
                            name
                        } links {
                            created {
                                id
                                properties { count }
                                target { properties { name } }
                            }
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        String photographerId = response.extractValueAsObject("data.Photographer[0].id", String.class);
        String linkId = response.extractValueAsObject("data.Photographer[0].links.created[0].id", String.class);

        var linkUpdate = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photographer: {
                                update: {
                                    where: { id: "%s" }
                                    links: {
                                        created: [
                                            { update: { where: { id: "%s" } properties: { count: 3 } } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        updated {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photographerId, linkId);
        response = graphQlClient.executeQuery(linkUpdate);
        assertTrue(response.getErrors().isEmpty());

        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photographer": [
                            {
                                "id": "${json-unit.any-string}",
                                "properties": { "name": "Bill" },
                                "links": {
                                    "created": [
                                        {
                                            "id": "${json-unit.any-string}",
                                            "properties": { "count": 3 },
                                            "target": { "properties": { "name": "photo1" } }
                                         }
                                     ]
                                }
                            }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo(expectedQueryResult);
    }

    @Test
    void testDeleteLink() {
        initSchema();

        var mutation = """
                mutation Mutation {
                     execute(inputs: [
                         { Photo: { create: { ref: "photo1" properties: { name: "photo1" number: 23 } } } }
                         {
                            Photographer: {
                                create: {
                                    properties: { name: "Bill" }
                                    links: {
                                        created: [
                                            { properties: { count: 2 } target: { ref: "photo1" } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        created {
                            itemType
                            id
                        }
                     }
                }
                """;
        GraphQLResponse response = graphQlClient.executeQuery(mutation);
        assertTrue(response.getErrors().isEmpty());

        var query = """
                {
                    Photographer {
                        id
                        properties {
                            name
                        } links {
                            created {
                                id
                                properties { count }
                                target { properties { name } }
                            }
                        }
                    }
                }
                """;
        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        String photographerId = response.extractValueAsObject("data.Photographer[0].id", String.class);
        String linkId = response.extractValueAsObject("data.Photographer[0].links.created[0].id", String.class);

        var linkDelete = """
                mutation Mutation {
                     execute(inputs: [
                         {
                            Photographer: {
                                update: {
                                    where: { id: "%s" }
                                    links: {
                                        created: [
                                            { delete: { where: { id: "%s" } } }
                                        ]
                                    }
                                }
                            }
                         }
                     ]) {
                        updated {
                            itemType
                            id
                        }
                     }
                }
                """.formatted(photographerId, linkId);
        response = graphQlClient.executeQuery(linkDelete);
        assertTrue(response.getErrors().isEmpty());

        response = graphQlClient.executeQuery(query);
        assertFalse(response.getData().isEmpty());

        var expectedQueryResult = """
                {
                    "data": {
                        "Photographer": [
                            {
                                "id": "${json-unit.any-string}",
                                "properties": { "name": "Bill" },
                                "links": {
                                    "created": null
                                }
                            }
                        ]
                    }
                }
                """;
        assertThatJson(response.getJson())
                .when(Option.IGNORING_ARRAY_ORDER)
                .isEqualTo(expectedQueryResult);
    }

}
