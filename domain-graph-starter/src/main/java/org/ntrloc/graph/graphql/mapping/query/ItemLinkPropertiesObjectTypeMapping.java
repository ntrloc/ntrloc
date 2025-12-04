package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.projection.LinkProjection;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ItemLinkPropertiesObjectTypeMapping implements PropertyFieldValueDefinitionMapper, ObjectTypeProducer {

    final static String IS_LINK_PROPERTIES_TYPE = "isLinkPropertiesType";

    private final String graphQlTypeName;
    private final LinkDefinition linkDefinition;
    private final Map<String, PropertyDefinition> linkPropertyTypeDefinitions;

    /** Maps the graphQL name of properties to the field definition of those properties. */
    private final Map<String, FieldDefinition> propertyFieldMappings = new HashMap<>();

    /** Maps the original schema property names to the field definitions used by GraphQL. */
    private final Map<String, FieldDefinition> propertyFieldsBySchemaPropertyName = new HashMap<>();

    ItemLinkPropertiesObjectTypeMapping(LinkDefinition linkDefinition) {
        String typeName = "%s %s %s Link Properties".formatted(linkDefinition.getSourceItemType(), linkDefinition.getSourceLabel(), linkDefinition.getTargetItemType());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.linkDefinition = linkDefinition;
        this.linkPropertyTypeDefinitions = linkDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getName, p -> p));

        for (PropertyDefinition propertyDefinition : linkDefinition.getProperties()) {
            FieldDefinition fieldDefinition = getPropertyFieldDefinition(propertyDefinition);
            propertyFieldMappings.put(fieldDefinition.getName(), fieldDefinition);
            propertyFieldsBySchemaPropertyName.put(propertyDefinition.getName(), fieldDefinition);
        }
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

        return propertyFields.stream().map(f -> propertyFieldMappings.get(f.getName()).getAdditionalData().get(ORIGINAL_PROPERTY_NAME_FIELD)).toList();
    }

    public void translateLinkProjectionProperties(LinkProjection linkProjection) {
        Map<String, Object> properties = linkProjection.getProperties();
        Map<String, Object> translatedProperties = properties.entrySet().stream().collect(Collectors.toMap(entry -> {
            String propertyName = entry.getKey();
            Object propertyValue = entry.getValue();
            FieldDefinition fieldDefinition = propertyFieldsBySchemaPropertyName.get(propertyName);
            return fieldDefinition.getName();
        }, Map.Entry::getValue, (existing, replacement) -> existing));
        linkProjection.setProperties(translatedProperties);
    }

}
