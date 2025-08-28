package org.ntrloc.graph.graphql.mapping;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.schema.Cardinality;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;

import java.util.Set;

class EntityInputTypeMapperTest {

    @Test
    void testMapInputTypes() {
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

        EntityDefinition photographerEntity = new EntityDefinition();
        photographerEntity.setName("Photographer");
        photographerEntity.setDescription("A photographer");
        PropertyDefinition photographerName = new PropertyDefinition("name", PropertyType.STRING, "photographer name");
        photographerEntity.setProperties(Set.of(photographerName));

        RelationshipDefinition photoRelationship = new RelationshipDefinition();
        photoRelationship.setSourceEntity("Photographer");
        photoRelationship.setTargetEntity("Photo");
        photoRelationship.setSourceCardinality(new Cardinality(0, 1) );
        photoRelationship.setTargetCardinality(new Cardinality(0, 1) );
        photoRelationship.setSourceVersionAction(RelationshipDefinition.VersionAction.NONE);
        photoRelationship.setTargetVersionAction(RelationshipDefinition.VersionAction.NONE);
        photoRelationship.setName("CREATED");
        photoRelationship.setSourceLabel("created");
        photoRelationship.setTargetLabel("creator");

        PropertyDefinition createdCountProperty = new PropertyDefinition("count", PropertyType.INT, "count");
        photoRelationship.setProperties(Set.of(createdCountProperty));

        Set<EntityDefinition> entityDefinitions = Set.of(photoEntity, photographerEntity);
        Set<RelationshipDefinition> relationshipDefinitions = Set.of(photoRelationship);

        MatcherChoiceInputTypeMapping matcherChoiceInputTypeMapping = new MatcherChoiceInputTypeMapping();

        EntityInputTypesMapper mapper = new EntityInputTypesMapper();
        mapper.parseEntityInputTypes(entityDefinitions, relationshipDefinitions, matcherChoiceInputTypeMapping);

    }

}
