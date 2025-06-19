package org.ntrloc.graph.db;

import com.google.common.collect.Streams;
import com.hazelcast.map.IMap;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.janusgraph.core.JanusGraph;
import org.janusgraph.core.JanusGraphFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.impl.EntityManagerImpl;
import org.ntrloc.graph.db.mutation.EntityCreateMutation;
import org.ntrloc.graph.db.mutation.EntityMutation;
import org.ntrloc.graph.db.mutation.EntityReference;
import org.ntrloc.graph.db.mutation.EntityUpdateMutation;
import org.ntrloc.graph.db.mutation.MutationRequest;
import org.ntrloc.graph.db.mutation.RelationshipCreateMutation;
import org.ntrloc.graph.db.mutation.RelationshipMutation;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.ntrloc.graph.db.schema.impl.SchemaManagerImpl;
import org.ntrloc.graph.db.storage.BinaryStorageAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.ntrloc.graph.db.PropertyConstants.UNIQUE_ID_PROPERTY;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@DisplayName("An entity manager")
public class EntityManagerMutationTest {

    private static final Logger LOG = LoggerFactory.getLogger(EntityManagerMutationTest.class);

    private GraphTraversalSource traversalSource;
    private JanusGraph janusGraph;

    private SchemaManager schemaManager;
    private EntityManager entityManager;

    @BeforeEach
    public void init() throws IOException {
        if (janusGraph != null && janusGraph.isOpen()) {
            janusGraph.close();
        }

        String indexPath;
        do {
            String tmpId = UUID.randomUUID().toString().substring(0, 8);
            indexPath = "target/db/lucene-test-" + tmpId;
        } while (new File(indexPath).exists());


        File indexDir = new File(indexPath);
        janusGraph = JanusGraphFactory.build()
                .set("storage.backend", "inmemory")
                .set("index.search.backend", "lucene")
                .set("index.search.directory", indexPath)
                .set("cache.tx-cache-size", 0)
                .open();
        traversalSource = janusGraph.traversal();
        try {
            traversalSource.V().drop().next();
        } catch (NoSuchElementException nee) { }

        janusGraph.tx().close();

        BinaryStorageAdapter adapter = mock(BinaryStorageAdapter.class);
        ClusterService clusterService = mock(ClusterService.class);
        doReturn(mock(IMap.class)).when(clusterService).getMap(anyString());
        schemaManager = new SchemaManagerImpl(janusGraph, traversalSource, clusterService);
        entityManager = new EntityManagerImpl(traversalSource, adapter, schemaManager);
        setUpSchema();
    }

    @Test
    @DisplayName("should return a transaction after executing a mutation")
    public void testReturnTransactionOnMutation() {
        EntityMutation entityMutation = new EntityCreateMutation()
                .entityType("Photo")
                .stringProperty("name", "photo1.jpg");
        MutationRequest request = new MutationRequest(Set.of(entityMutation), Set.of());
        Transaction transaction = entityManager.executeMutation(request);
        assertNotNull(transaction, "null transaction");
    }

    private void setUpSchema() {
        EntityDefinition photoDefinition = new EntityDefinition();
        photoDefinition.setName("Photo");

        PropertyDefinition nameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        PropertyDefinition numberDefinition = new PropertyDefinition("number", PropertyType.INT, "");
        PropertyDefinition booleanDefinition = new PropertyDefinition("boolean", PropertyType.BOOLEAN, "");
        PropertyDefinition dateDefinition = new PropertyDefinition("someDate", PropertyType.DATE, "");
        PropertyDefinition doublePropertyDefinition = new PropertyDefinition("double", PropertyType.DOUBLE, "");
        PropertyDefinition intListDefinition = new PropertyDefinition("intList", PropertyType.INT_LIST, "");
        photoDefinition.setProperties(Set.of(nameDefinition, numberDefinition, booleanDefinition, dateDefinition, doublePropertyDefinition, intListDefinition));

        schemaManager.createEntityDefinition(photoDefinition);

        EntityDefinition photographerDefinition = new EntityDefinition();
        photographerDefinition.setName("Photographer");
        PropertyDefinition photographerNameDefinition = new PropertyDefinition("name", PropertyType.STRING, "");
        photographerDefinition.setProperties(Set.of(photographerNameDefinition));
        schemaManager.createEntityDefinition(photographerDefinition);

        RelationshipDefinition relationshipDefinition = new RelationshipDefinition();
        relationshipDefinition.setName("created");
        relationshipDefinition.setSourceEntity(photographerDefinition.getName());
        relationshipDefinition.setTargetEntity(photoDefinition.getName());
        relationshipDefinition.setSourceCardinality(new Cardinality(1, 1));
        relationshipDefinition.setSourceVersionAction(RelationshipDefinition.VersionAction.COPY);
        relationshipDefinition.setTargetCardinality(new Cardinality(0, null));
        relationshipDefinition.setTargetVersionAction(RelationshipDefinition.VersionAction.MOVE);
        schemaManager.createRelationshipDefinition(relationshipDefinition);


    }

    @Test
    @DisplayName("should mark new entities as uncommitted")
    void testMarkNewEntitiesAsUncommitted() {
        EntityMutation entityMutation = new EntityCreateMutation()
                .entityType("Photo")
                .stringProperty("name", "photo1.jpg");
        MutationRequest request = new MutationRequest(Set.of(entityMutation), Set.of());
        Transaction t = entityManager.executeMutation(request);

        Map<Object, Object> valueMap = traversalSource.V().hasLabel("Photo").valueMap().next();
        List<String> status = (List<String>) valueMap.get("status");
        assertEquals(1, status.size());
        assertEquals("UNCOMMITTED", status.get(0));
    }

    @Test
    @DisplayName("should be able to abort a mutation transaction")
    void testRollback() {
        EntityMutation entityMutation = new EntityCreateMutation()
                .entityType("Photo")
                .stringProperty("name", "photo1.jpg");
        MutationRequest request = new MutationRequest(Set.of(entityMutation), Set.of());
        Transaction t = entityManager.executeMutation(request);
        entityManager.abort(t);

        assertFalse(traversalSource.V().has("transactionId", t.getId()).hasNext(), "found results with transaction id " + t.getId());
    }

    @Test
    @DisplayName("should store all allowed data types")
    public void testStoreAllDataTypes() {
        Date now = new Date();
        EntityMutation entityMutation = new EntityCreateMutation()
                .entityType("Photo")
                .stringProperty("name", "photo1.jpg")
                .intProperty("number", 5)
                .booleanProperty("boolean", true)
                .dateProperty("someDate", now)
                .binaryReferenceProperty("binary", 1L)
                .doubleProperty("double", 2.0)
                .intListProperty("intList", List.of(1, 2, 3))
                .booleanListProperty("booleanList", List.of(true, false, true))
                .doubleListProperty("doubleList", List.of(1.0, 2.0, 3.0))
                .dateListProperty("dateList", List.of(now))
                .stringListProperty("stringList", List.of("s1"));
        MutationRequest request = new MutationRequest(Set.of(entityMutation), Set.of());
        Transaction t = entityManager.executeMutation(request);

        entityManager.prepare(t);
        entityManager.commit(t);

        Map<Object, Object> valueMap = traversalSource.V().hasLabel("Photo").valueMap().next();
        assertEquals(List.of("NORMAL"), valueMap.get("status"));
        assertEquals(List.of(t.getId()), valueMap.get("transactionId"));
        assertEquals(List.of("photo1.jpg"), valueMap.get("Photo_name"));
        assertEquals(List.of(5), valueMap.get("Photo_number"));
        assertEquals(List.of(true), valueMap.get("Photo_boolean"));
        assertEquals(List.of(now), valueMap.get("Photo_someDate"));
        assertEquals(List.of(1L), valueMap.get("Photo_binary"));
        assertEquals(List.of(2.0), valueMap.get("Photo_double"));
        assertEquals(List.of( 1, 2, 3), valueMap.get("Photo_intList"));
        assertEquals(List.of(true, false, true), valueMap.get("Photo_booleanList"));
        assertEquals(List.of(1.0, 2.0, 3.0),valueMap.get("Photo_doubleList"));
        assertEquals(List.of(now), valueMap.get("Photo_dateList"));
        assertEquals(List.of("s1"), valueMap.get("Photo_stringList"));
    }

    private Traversal<Vertex, Map<String, Object>> propertiesAndRelationshipTraversal(GraphTraversal<Vertex, Vertex> start) {
        return start.project("id", "label", "props", "out", "in")
                .by(__.id())
                .by(__.label())
                .by(__.valueMap())
                .by(__.choose(
                                __.outE().count().is(0),

                                __.constant(new HashMap<>()),

                                __.outE()
                                        .unfold()
                                        .project("label", "info")
                                        .by(__.label())
                                        .by(__.project("properties", "target")
                                                .by(__.valueMap())
                                                .by(__.inV().project("id", "label", "props").by(__.id()).by(__.label()).by(__.valueMap()))
                                        )
                                        .group().by("label").by("info")
                        )
                )
                .by(__.choose(
                                __.inE().count().is(0),

                                __.constant(new HashMap<>()),

                                __.inE()
                                        .unfold()
                                        .project("label", "info")
                                        .by(__.label())
                                        .by(__.project("properties", "source")
                                                .by(__.valueMap())
                                                .by(__.inV().project("id", "label", "props").by(__.id()).by(__.label()).by(__.valueMap()))
                                        )
                                        .group().by("label").by("info")


                        )
                );
    }

    @Test
    @DisplayName("should be able to update an entity")
    public void testUpdateEntity() {

        LOG.info("Creating original entity");
        EntityCreateMutation createMutation = new EntityCreateMutation()
                .entityType("Photo")
                .stringProperty("name", "photo1.jpg")
                .intProperty("number", 5);
        MutationRequest createRequest = new MutationRequest(Set.of(createMutation), Set.of());
        Transaction t = entityManager.executeMutation(createRequest);
        entityManager.prepare(t);
        entityManager.commit(t);


        LOG.info("Updating entity");
        Vertex v = traversalSource.V().hasLabel("Photo").next();
        String uid = (String)v.values(UNIQUE_ID_PROPERTY).next();
        EntityUpdateMutation entityUpdateMutation = new EntityUpdateMutation()
                .id(uid)
                .stringProperty("name", "photo2.jpg").intProperty("number", null).booleanProperty("boolean", false);
        MutationRequest updateRequest = new MutationRequest(Set.of(entityUpdateMutation), Set.of());
        t = entityManager.executeMutation(updateRequest);
        entityManager.prepare(t);

        var checkNodes = Streams.stream(traversalSource.V().valueMap()).toList();

        LOG.info("Updated entity");

        Traversal<Vertex, Map<String, Object>> uncommittedTraversal = propertiesAndRelationshipTraversal(traversalSource.V().hasLabel("Photo").has(PropertyConstants.TRANSACTION_ID_PROPERTY, t.getId()));
        assertTrue(uncommittedTraversal.hasNext());
        var uncommittedEntity = uncommittedTraversal.next();

        HashMap<String, Object> props = (HashMap<String, Object>)uncommittedEntity.get("props");
        ArrayList<String> status = (ArrayList)props.get("status");
        assertEquals(List.of("UNCOMMITTED"), status);

        entityManager.commit(t);

        var allNodes = traversalSource.V().hasLabel("Photo").project("id", "label", "props").by(__.id()).by(__.label()).by(__.valueMap());
        while (allNodes.hasNext()) {
            var next = allNodes.next();
            LOG.info("Next: {}", next);
        }

        var version1Traversal = propertiesAndRelationshipTraversal(traversalSource.V().hasLabel("Photo").has("Photo_name", "photo1.jpg"));
        assertTrue(version1Traversal.hasNext(), "version 1 not found");
        var v1 = version1Traversal.next();

        var version2Traversal = propertiesAndRelationshipTraversal(traversalSource.V().hasLabel("Photo").has("Photo_name", "photo2.jpg"));
        assertTrue(version2Traversal.hasNext(), "version 2 not found");
        var v2 = version2Traversal.next();

        HashMap<String, Object> v1Incoming = (HashMap<String, Object>)v1.get("in");
        List<Map<String, Object>> v1HasPrevious = (List<Map<String, Object>>)v1Incoming.get(LabelConstants.HAS_PREVIOUS_VERSION_LABEL);
        Map<String, Object> firstInfo = v1HasPrevious.get(0);
        Map<String, Object> source = (Map<String, Object>) firstInfo.get("source");
        assertEquals(v1.get("id"), source.get("id"));

        var v2Props = (Map<String, Object>)v2.get("props");
        assertEquals(List.of(false), v2Props.get("Photo_boolean"));
        assertEquals(List.of("photo2.jpg"), v2Props.get("Photo_name"));
        assertNull(v2Props.get("Photo_number"));
    }

    @Test
    @DisplayName("should be able to create and link two new entities")
    void testAndLinkNewEntities() {

        EntityCreateMutation photoMutation = new EntityCreateMutation()
                .entityType("Photo")
                .refId("photo")
                .stringProperty("name", "photo1.jpg")
                .intProperty("number", 5);

        EntityCreateMutation photographerMutation = new EntityCreateMutation()
                .entityType("Photographer")
                .refId("photographer")
                .stringProperty("name", "Bill Smith");

        RelationshipMutation relationshipMutation = new RelationshipCreateMutation("created")
                .source(EntityReference.mutationReference("photographer"))
                .target(EntityReference.mutationReference("photo"))
                .dateProperty("creationDate", new Date());

        MutationRequest request = new MutationRequest(Set.of(photoMutation, photographerMutation), Set.of(relationshipMutation));
        Transaction t = entityManager.executeMutation(request);
        entityManager.prepare(t);
        entityManager.commit(t);

        // now check that the nodes have been created and linked
        Iterator<Map<String, Object>> testIter = traversalSource.V().hasLabel("Photographer")
                .project("node", "out")
                .by(__.elementMap())
                .by(__.outE().inV()
                        .unfold()
                        .project("label", "info")
                        .by(__.label())
                        .by(__.project("properties", "target")
                                .by(__.valueMap())
                                .by(__.out().project("id", "label", "props").by(__.id()).by(__.label()).by(__.valueMap()))
                        )
                        .group().by("label").by("info")

                );
        assertTrue(testIter.hasNext(), "did not find node with relationship");
        Map<String, Object> node = testIter.next();
        Map<String, Object> outgoingLinks = (Map<String, Object>) node.get("out");
        List<Map<String, Object>> createdLinks = (List<Map<String, Object>>)outgoingLinks.get("created");
        assertNotNull(createdLinks, "no created links found");
        assertEquals(1, createdLinks.size(), "incorrect link count");
        Map<String, Object> linkInfo = createdLinks.get(0);
        Map<String, Object> linkProperties = (Map<String, Object>) linkInfo.get("properties");
        assertTrue(linkProperties.containsKey("created_creationDate"), "relationship property not found");

        Map<String, Object> target = (Map<String, Object>) linkInfo.get("target");
        LOG.info("Got target {}", target);

        Map<String, Object> targetProps = (Map<String, Object>) target.get("props");
        assert(targetProps.containsKey("Photo_name"));
        assertEquals(List.of("photo1.jpg"), targetProps.get("Photo_name"));
    }

}
