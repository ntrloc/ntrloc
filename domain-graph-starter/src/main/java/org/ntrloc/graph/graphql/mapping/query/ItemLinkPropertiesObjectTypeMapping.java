package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.List;

public class ItemLinkPropertiesObjectTypeMapping implements PropertyFieldValueDefinitionMapper, ObjectTypeProducer {

    final static String IS_LINK_PROPERTIES_TYPE = "isLinkPropertiesType";

    private final String graphQlTypeName;
    private final LinkDefinition linkDefinition;

    ItemLinkPropertiesObjectTypeMapping(LinkDefinition linkDefinition) {
        String typeName = "%s %s %s Link Properties".formatted(linkDefinition.getSourceEntity(), linkDefinition.getSourceLabel(), linkDefinition.getTargetEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.linkDefinition = linkDefinition;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<FieldDefinition> fieldDefinitions = linkDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).toList();
        ObjectTypeDefinition typeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .additionalData(IS_LINK_PROPERTIES_TYPE, Boolean.toString(true))
                .build();
        return List.of(typeDefinition);
    }

}
