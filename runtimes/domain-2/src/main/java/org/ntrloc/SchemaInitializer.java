package org.ntrloc;

import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversalSource;
import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.StringProperty;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

@Component
public class SchemaInitializer {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaInitializer.class);

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private ItemManager itemManager;

    @Autowired
    private GraphTraversalSource traversalSource;

    @EventListener(ApplicationReadyEvent.class)
    void initSchema() {

        try {
            traversalSource.V().drop().iterate();
        } catch (NoSuchElementException nsee) {
            // do nothing
        }

        var entityDefs = schemaManager.retrieveItemDefinitions();
        if (entityDefs.isEmpty()) {
            LOG.info("Initializing schema");
            ItemDefinition photoEntity = new ItemDefinition();
            photoEntity.setName("Photo");
            photoEntity.setDescription("A photo");

            PropertyDefinition photoName = new PropertyDefinition("name", PropertyType.STRING, "photo name");
            PropertyDefinition photoNumber = new PropertyDefinition("number", PropertyType.INT, "photo number");
            photoEntity.setProperties(Set.of(photoName, photoNumber));

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
            photoRelationship.setTargetLabel("creator");

            PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
            photoRelationship.setProperties(Set.of(createdCountProperty));

            schemaManager.createLinkDefinition(photoRelationship);
            LOG.info("Created schema");
        } else {
            LOG.info("Schema already initialized");
            LOG.info("Found {} entities", entityDefs.size());
        }

        var photoCount = 50;
        var list = new ArrayList<ItemMutation>();
        for (int i = 0; i < photoCount; i++) {
            var photoCreate = new ItemCreateMutation()
                    .itemType("Photo")
                    .properties(List.of(new StringProperty("name", "photo-%d".formatted(i))));
            list.add(photoCreate);
        }


        var req = new MutationRequest(list);
        itemManager.executeMutation(req);
    }



}
