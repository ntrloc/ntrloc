package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Selection;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueryItemPropertyObjectTypeMapping implements ObjectTypeProducer, PropertyFieldValueDefinitionMapper {

    static String IS_PROPERTY_TYPE_FLAG = "isPropertyType";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;

    /** Maps the graphQL name of properties to the field definition of those properties. */
    private Map<String, FieldDefinition> propertyFieldMappings;

    /** Maps the graphQL name of property groups to the mapping for those groups. */
    private Map<String, QueryItemPropertyGroupObjectTypeMapping> groupMappings;

    public QueryItemPropertyObjectTypeMapping(ItemDefinition itemDefinition) {
        this.itemDefinition = itemDefinition;
        String typeName = "%s Properties".formatted(itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        propertyFieldMappings = itemDefinition.getProperties().stream().map(this::getPropertyFieldDefinition).collect(Collectors.toMap(FieldDefinition::getName, p -> p));

        groupMappings = itemDefinition.getPropertyGroups() == null ?
                Map.of() :
                itemDefinition.getPropertyGroups().stream().collect(Collectors.toMap(pg -> getGroupFieldName(pg.getName()), pg -> new QueryItemPropertyGroupObjectTypeMapping(itemDefinition, pg)));
    }

    private String getGroupFieldName(String propertyGroupName) {
        return CaseUtils.toCamelCase(propertyGroupName, false, '_', '-');
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        var retTypes = new ArrayList<ObjectTypeDefinition>();

        var propertyFieldDefinitions = new ArrayList<FieldDefinition>();
        propertyFieldDefinitions.addAll(this.propertyFieldMappings.values());

        for (Map.Entry<String, QueryItemPropertyGroupObjectTypeMapping> entry : groupMappings.entrySet()) {
            String fieldName = entry.getKey();
            var groupMapping = entry.getValue();
            List<ObjectTypeDefinition> groupTypeDefinitions = groupMapping.getObjectTypeDefinitions();
            retTypes.addAll(groupTypeDefinitions);

            FieldDefinition groupField = FieldDefinition.newFieldDefinition()
                    .name(fieldName)
                    .type(new TypeName(groupMapping.getGraphQlTypeName()))
                    .build();
            propertyFieldDefinitions.add(groupField);
        }

        var typeDef = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(propertyFieldDefinitions)
                .additionalData(IS_PROPERTY_TYPE_FLAG, Boolean.toString(true))
                .build();

        retTypes.add(typeDef);
        return retTypes;
    }

    List<String> parseQueryProperties(Field field) {
        // here we need to translate the graphQL property name, like "firstName", back to the original property name, like "First Name".
        List<Selection> propertySelections = field.getSelectionSet().getSelections();
        List<Field> propertyFields = propertySelections.stream().map(s -> (Field) s).collect(Collectors.toList());
        return propertyFields.stream().map(f -> propertyFieldMappings.get(f.getName()).getAdditionalData().get(ORIGINAL_PROPERTY_NAME_FIELD)).collect(Collectors.toList());
    }

}
