package org.ntrloc.graph.graphql.mapping;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.impl.SchemaMapperImpl;

import java.util.Set;

class SchemaMapperTest {

    @Test
    void testMapInputTypes() {
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

        ItemDefinition photographerEntity = new ItemDefinition();
        photographerEntity.setName("Photographer");
        photographerEntity.setDescription("A photographer");
        PropertyDefinition photographerName = new PropertyDefinition("name", PropertyType.STRING, "photographer name");
        photographerEntity.setProperties(Set.of(photographerName));

        LinkDefinition photoRelationship = new LinkDefinition();
        photoRelationship.setSourceEntity("Photographer");
        photoRelationship.setTargetEntity("Photo");
        photoRelationship.setSourceCardinality(new Cardinality(0, 1) );
        photoRelationship.setTargetCardinality(new Cardinality(0, 1) );
        photoRelationship.setSourceVersionAction(LinkDefinition.VersionAction.NONE);
        photoRelationship.setTargetVersionAction(LinkDefinition.VersionAction.NONE);
        photoRelationship.setName("CREATED");
        photoRelationship.setSourceLabel("created");
        photoRelationship.setTargetLabel("created by");

        PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
        photoRelationship.setProperties(Set.of(createdCountProperty));

        Set<ItemDefinition> itemDefinitions = Set.of(photoEntity, photographerEntity);
        Set<LinkDefinition> linkDefinitions = Set.of(photoRelationship);

        SchemaMapper mapper = new SchemaMapperImpl();

        mapper.mapSchema(itemDefinitions, linkDefinitions);

    }

}
