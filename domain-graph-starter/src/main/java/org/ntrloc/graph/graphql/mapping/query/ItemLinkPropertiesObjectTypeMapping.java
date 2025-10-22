package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemLinkPropertiesObjectTypeMapping implements PropertyFieldValueDefinitionMapper, ObjectTypeProducer {

    final static String IS_LINK_PROPERTIES_TYPE = "isLinkPropertiesType";

    private final String graphQlTypeName;
    private final LinkDefinition linkDefinition;
    private final Map<String, PropertyDefinition> linkPropertyTypeDefinitions;

    /** Maps the graphQL name of properties to the field definition of those properties. */
    private final Map<String, FieldDefinition> propertyFieldMappings;

    ItemLinkPropertiesObjectTypeMapping(LinkDefinition linkDefinition) {
        String typeName = "%s %s %s Link Properties".formatted(linkDefinition.getSourceItemType(), linkDefinition.getSourceLabel(), linkDefinition.getTargetItemType());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.linkDefinition = linkDefinition;
        this.linkPropertyTypeDefinitions = linkDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getName, p -> p));

        propertyFieldMappings = linkDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).collect(Collectors.toMap(FieldDefinition::getName, p -> p));
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<FieldDefinition> fieldDefinitions = new ArrayList<>();
        fieldDefinitions.addAll(propertyFieldMappings.values());
        ObjectTypeDefinition typeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .additionalData(IS_LINK_PROPERTIES_TYPE, Boolean.toString(true))
                .build();
        return List.of(typeDefinition);
    }

    public List<String> parseLinkProperties(Field field) {
        // here we need to translate the graphQL property name, like "firstName", back to the original property name, like "First Name".
        List<Selection> propertySelections = field.getSelectionSet().getSelections();
        List<Field> propertyFields = propertySelections.stream().map(s -> (Field) s).collect(Collectors.toList());

        return propertyFields.stream().map(f -> {
            String originalFieldName = propertyFieldMappings.get(f.getName()).getAdditionalData().get(ORIGINAL_PROPERTY_NAME_FIELD);
            PropertyDefinition pd = linkPropertyTypeDefinitions.get(originalFieldName);
            return pd.getUid();
        }).collect(Collectors.toList());
    }

}
