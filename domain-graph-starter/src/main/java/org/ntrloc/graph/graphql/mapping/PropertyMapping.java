package org.ntrloc.graph.graphql.mapping;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.PropertyDefinition;
import org.ntrloc.graph.db.schema.PropertyType;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

public class PropertyMapping {

    private String graphPropertyName;
    private String graphQlPropertyName;
    private String description;
    private PropertyType propertyType;

    public PropertyMapping(PropertyDefinition propertyDefinition, GraphQLSchemaMapper mappingContext) {
        graphPropertyName = propertyDefinition.getName();
        graphQlPropertyName = CaseUtils.toCamelCase(graphPropertyName, false, '_', '-');
        description = propertyDefinition.getDescription();
        propertyType = propertyDefinition.getType();
    }

    public String getGraphPropertyName() {
        return graphPropertyName;
    }

    public void setGraphPropertyName(String graphPropertyName) {
        this.graphPropertyName = graphPropertyName;
    }

    public String getGraphQlPropertyName() {
        return graphQlPropertyName;
    }

    public void setGraphQlPropertyName(String graphQlPropertyName) {
        this.graphQlPropertyName = graphQlPropertyName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public PropertyType getPropertyType() {
        return propertyType;
    }

    public void setPropertyType(PropertyType propertyType) {
        this.propertyType = propertyType;
    }
}
