package org.ntrloc.graph.graphql.impl;

import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.GraphQLSchemaMapper;
import org.ntrloc.graph.graphql.mapping.legacy.EntityMapping;
import org.ntrloc.graph.graphql.mapping.legacy.InboundRelationshipMapping;
import org.ntrloc.graph.graphql.mapping.legacy.OutboundRelationshipMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class GraphQLSchemaMapperImpl implements GraphQLSchemaMapper {

    private static final Logger LOG = LoggerFactory.getLogger(GraphQLSchemaMapperImpl.class);

    private Map<String, EntityDefinition> entityDefinitionMap = new HashMap<>();
    private Map<String, RelationshipDefinition> relationshipDefinitionMap = new HashMap<>();

    Map<String, EntityMapping> entityMappingMap = new HashMap<>();

    public void mapSchemaElements(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        parse(entityDefinitions, relationshipDefinitions);

        parseSchema(entityDefinitions, relationshipDefinitions);

    }

    private void parse(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        this.entityDefinitionMap.putAll(entityDefinitions.stream().collect(Collectors.toMap(EntityDefinition::getName, entityDefinition -> entityDefinition)));
        this.relationshipDefinitionMap.putAll(relationshipDefinitions.stream().collect(Collectors.toMap(RelationshipDefinition::getName, relationshipDefinition -> relationshipDefinition)));

        for (EntityDefinition entityDefinition : entityDefinitions) {

            EntityMapping entityMapping = entityMappingMap.get(entityDefinition.getName());
            if (entityMapping == null) {
                entityMapping = new EntityMapping(entityDefinition, this);
                entityMappingMap.put(entityDefinition.getName(), entityMapping);
            }

            Set<RelationshipDefinition> inboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getTargetEntity().equals(entityDefinition.getName())).collect(Collectors.toSet());
            for (RelationshipDefinition inboundRelationship : inboundRelationships) {
                EntityMapping sourceMapping = getEntityMapping(inboundRelationship.getSourceEntity()).orElseGet(() -> {
                    Optional<EntityDefinition> sourceOpt = getEntityDefinition(inboundRelationship.getSourceEntity());
                    if (sourceOpt.isEmpty()) {
                        throw new RuntimeException("Source entity " + inboundRelationship.getSourceEntity() + " not found in mapping context");
                    } else {
                        EntityDefinition sourceEntity = sourceOpt.get();
                        EntityMapping newMapping = new EntityMapping(sourceEntity, this);
                        entityMappingMap.put(sourceEntity.getName(), newMapping);
                        return newMapping;
                    }
                });

                var newMapping = new InboundRelationshipMapping(inboundRelationship, sourceMapping, this);
                entityMapping.addInboundRelationship(newMapping);
            }

            Set<RelationshipDefinition> outboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getSourceEntity().equals(entityDefinition.getName())).collect(Collectors.toSet());
            for (RelationshipDefinition outboundRelationship : outboundRelationships) {
                EntityMapping targetMapping = getEntityMapping(outboundRelationship.getTargetEntity()).orElseGet(() -> {
                    Optional<EntityDefinition> targetOpt = getEntityDefinition(outboundRelationship.getTargetEntity());
                    if (targetOpt.isEmpty()) {
                        throw new RuntimeException("Target entity " + outboundRelationship.getTargetEntity() + " not found in mapping context");
                    } else {
                        EntityDefinition targetEntity = targetOpt.get();
                        EntityMapping newMapping = new EntityMapping(targetEntity, this);
                        entityMappingMap.put(targetEntity.getName(), newMapping);
                        return newMapping;
                    }
                });

                var newMapping = new OutboundRelationshipMapping(outboundRelationship, targetMapping, this);
                entityMapping.addOutboundRelationship(newMapping);
            }

        }
    }

    private Optional<EntityDefinition> getEntityDefinition(String entityName) {
        return Optional.ofNullable(entityDefinitionMap.get(entityName));
    }

    public Optional<EntityMapping> getEntityMapping(String entityName) {
        return Optional.ofNullable(entityMappingMap.get(entityName));
    }


    private void parseSchema(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        // this is the new implementation?

    }



}
