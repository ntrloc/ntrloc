package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.FieldDefinition;
import graphql.language.ObjectTypeDefinition;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class QueryItemPropertyGroupObjectTypeMapping implements ObjectTypeProducer, PropertyFieldValueDefinitionMapper {

    static String IS_PROPERTY_GROUP_TYPE = "isPropertyGroup";

    private String graphQlTypeName;
    private PropertyGroupDefinition propertyGroupDefinition;

    private Map<String, FieldDefinition> propertyFieldDefinitions;

    public QueryItemPropertyGroupObjectTypeMapping(ItemDefinition itemDefinition, PropertyGroupDefinition propertyGroupDefinition) {
        this.propertyGroupDefinition = propertyGroupDefinition;
        String typeName = "%s %s Property Group".formatted(itemDefinition.getName(), propertyGroupDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        this.propertyFieldDefinitions = propertyGroupDefinition.getProperties().stream().collect(Collectors.toMap(PropertyDefinition::getName, this::getPropertyFieldDefinition));
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public PropertyGroupDefinition getPropertyGroupDefinition() {
        return propertyGroupDefinition;
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {

        var fieldDefinitions = new ArrayList<FieldDefinition>();
        for (Map.Entry<String, FieldDefinition> entry : propertyFieldDefinitions.entrySet()) {
            fieldDefinitions.add(entry.getValue());
        }

        var groupTypeDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .additionalData(IS_PROPERTY_GROUP_TYPE, Boolean.valueOf(true).toString())
                .build();

        return List.of(groupTypeDefinition);
    }

}
