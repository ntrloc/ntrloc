package org.ntrloc.graph.graphql.mapping.legacy;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyGroupDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

import java.util.HashMap;
import java.util.Map;

public class PropertyGroupMapping {

    private String graphPropertyGroupName;
    private String graphQlPropertyGroupName;
    private String description;

    private Map<String, PropertyMapping> graphPropertyMappings = new HashMap<>();
    private Map<String, PropertyMapping> graphQlPropertyMappings = new HashMap<>();

    public PropertyGroupMapping(PropertyGroupDefinition propertyGroupDefinition, GraphQLSchemaMapper mappingContext) {
        graphPropertyGroupName = propertyGroupDefinition.getName();
        graphQlPropertyGroupName = CaseUtils.toCamelCase(graphPropertyGroupName, false, '_', '-');
        description = propertyGroupDefinition.getDescription();

        if (propertyGroupDefinition.getProperties() != null) {
            propertyGroupDefinition.getProperties().forEach(propertyDefinition -> {
                var propertyMapping = new PropertyMapping(propertyDefinition, mappingContext);
                graphPropertyMappings.put(propertyDefinition.getName(), propertyMapping);
                graphQlPropertyMappings.put(propertyMapping.getGraphQlPropertyName(), propertyMapping);
            });
        }
    }

    public String getGraphPropertyGroupName() {
        return graphPropertyGroupName;
    }

    public String getGraphQlPropertyGroupName() {
        return graphQlPropertyGroupName;
    }

    public String getDescription() {
        return description;
    }

    public PropertyMapping getGraphPropertyMapping(String propertyName) {
        return graphPropertyMappings.get(propertyName);
    }

    public PropertyMapping getGraphQlPropertyMapping(String propertyName) {
        return graphQlPropertyMappings.get(propertyName);
    }
}
