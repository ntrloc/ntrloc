package org.ntrloc.graph.graphql.mapping;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;

import java.util.HashMap;
import java.util.Map;

public class EntityMapping {

    private String graphEntityName;
    private String graphQlEntityName;
    private String description;

    private Map<String, PropertyMapping> graphEntityProperties = new HashMap<>();
    private Map<String, PropertyMapping> graphQlEntityProperties = new HashMap<>();

    private Map<String, PropertyGroupMapping> graphEntityPropertyGroups = new HashMap<>();
    private Map<String, PropertyGroupMapping> graphQlEntityPropertyGroups = new HashMap<>();

    private Map<String, InboundRelationshipMapping> inboundGraphEntityRelationships = new HashMap<>();
    private Map<String, InboundRelationshipMapping> inbounddGraphQlEntityRelationships = new HashMap<>();

    private Map<String, OutboundRelationshipMapping> outboundGraphEntityRelationships = new HashMap<>();
    private Map<String, OutboundRelationshipMapping> outboundGraphQlEntityRelationships = new HashMap<>();

    public EntityMapping(EntityDefinition entityDefinition, GraphQLSchemaMapper mappingContext) {
        graphEntityName = entityDefinition.getName();
        graphQlEntityName = CaseUtils.toCamelCase(graphEntityName, false, '_', '-');
        description = entityDefinition.getDescription();

        if (entityDefinition.getPropertyGroups() != null) {
            entityDefinition.getPropertyGroups().forEach(propertyGroupDefinition -> {
                var propertyGroupMapping = new PropertyGroupMapping(propertyGroupDefinition, mappingContext);
                graphEntityPropertyGroups.put(propertyGroupDefinition.getName(), propertyGroupMapping);
                graphQlEntityPropertyGroups.put(propertyGroupMapping.getGraphQlPropertyGroupName(), propertyGroupMapping);
            });
        }

        if (entityDefinition.getProperties() != null) {
            entityDefinition.getProperties().forEach(propertyDefinition -> {
                var propertyMapping = new PropertyMapping(propertyDefinition, mappingContext);
                graphEntityProperties.put(propertyDefinition.getName(), propertyMapping);
                graphQlEntityProperties.put(propertyMapping.getGraphQlPropertyName(), propertyMapping);
            });
        }
    }

    public PropertyMapping getGraphEntityProperty(String propertyName) {
        return graphEntityProperties.get(propertyName);
    }

    public PropertyMapping getGraphQlEntityProperty(String propertyName) {
        return graphQlEntityProperties.get(propertyName);
    }

    public PropertyGroupMapping getGraphEntityPropertyGroup(String propertyGroupName) {
        return graphEntityPropertyGroups.get(propertyGroupName);
    }

    public PropertyGroupMapping getGraphQlEntityPropertyGroup(String propertyGroupName) {
        return graphQlEntityPropertyGroups.get(propertyGroupName);
    }

    public void addInboundRelationship(InboundRelationshipMapping mapping) {
        inboundGraphEntityRelationships.put(mapping.getTargetGraphQlName(), mapping);
        inbounddGraphQlEntityRelationships.put(mapping.getTargetGraphQlName(), mapping);
    }

    public void addOutboundRelationship(OutboundRelationshipMapping mapping) {
        outboundGraphEntityRelationships.put(mapping.getSourceGraphQlName(), mapping);
        outboundGraphQlEntityRelationships.put(mapping.getSourceGraphQlName(), mapping);
    }


}
