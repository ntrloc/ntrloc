package org.ntrloc.graph.graphql.mapping;

import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

import java.util.HashMap;
import java.util.Map;

public abstract class RelationshipMapping {

    protected String graphName;
    protected String description;

    protected Map<String, PropertyMapping> graphRelationshipProperties = new HashMap<>();
    protected Map<String, PropertyMapping> graphQlRelationshipProperties = new HashMap<>();

    protected Map<String, PropertyGroupMapping> graphRelationshipPropertyGroups = new HashMap<>();
    protected Map<String, PropertyGroupMapping> graphQlRelationshipPropertyGroups = new HashMap<>();

    protected RelationshipMapping(RelationshipDefinition def, GraphQLSchemaMapper mappingContext) {
        graphName = def.getName();
        description = def.getDescription();

        if (def.getPropertyGroups() != null) {
            def.getPropertyGroups().forEach(propertyGroupDefinition -> {
                var propertyGroupMapping = new PropertyGroupMapping(propertyGroupDefinition, mappingContext);
                graphRelationshipPropertyGroups.put(propertyGroupDefinition.getName(), propertyGroupMapping);
                graphQlRelationshipPropertyGroups.put(propertyGroupMapping.getGraphQlPropertyGroupName(), propertyGroupMapping);
            });
        }

        if (def.getProperties() != null) {
            def.getProperties().forEach(propertyDefinition -> {
                var propertyMapping = new PropertyMapping(propertyDefinition, mappingContext);
                graphRelationshipProperties.put(propertyDefinition.getName(), propertyMapping);
                graphQlRelationshipProperties.put(propertyMapping.getGraphQlPropertyName(), propertyMapping);
            });
        }
    }

    public String getGraphName() {
        return graphName;
    }

    public String getDescription() {
        return description;
    }

}
