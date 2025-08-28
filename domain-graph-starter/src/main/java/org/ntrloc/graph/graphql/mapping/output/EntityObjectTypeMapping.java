package org.ntrloc.graph.graphql.mapping.output;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;

import java.util.ArrayList;
import java.util.List;

public class EntityObjectTypeMapping implements ObjectTypeProducer {

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;

    private EntityPropertiesObjectTypeMapping propertiesMapping;

    public EntityObjectTypeMapping(EntityDefinition entityDefinition) {
        this.entityDefinition = entityDefinition;
        String typeName = String.format("%s", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        propertiesMapping = new EntityPropertiesObjectTypeMapping(entityDefinition);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        /* TODO: properties definitions, property group definitions,
        inbound relationships, outbound relationships */

        ArrayList<ObjectTypeDefinition> allDefinitions = new ArrayList<>(propertiesMapping.getObjectTypeDefinitions());

        FieldDefinition propertiesDefinition = FieldDefinition.newFieldDefinition()
                .name("properties")
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        ObjectTypeDefinition typeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(List.of(propertiesDefinition))
                .additionalData(OutputTypeConstants.IS_TOP_LEVEL_ENTITY_OUTPUT_TYPE, Boolean.toString(true))
                .build();

        allDefinitions.add(typeDefinition);

        return allDefinitions;
    }

}
