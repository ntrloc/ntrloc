package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class EntityInputObjectTypesMapper {

    private static final Logger LOG = LoggerFactory.getLogger(EntityInputObjectTypesMapper.class);

    /* Matches an entity name to the input object types to which it corresponds. */
    private Map<String, EntityInputObjectTypeMapping> entityInputMap = new HashMap<>();

    public Map<String, InputObjectTypeDefinition> mapInputObjectTypes(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        MatcherChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping = new MatcherChoiceInputObjectTypeMapping();

        for (EntityDefinition definition : entityDefinitions) {
            EntityInputObjectTypeMapping mapping = new EntityInputObjectTypeMapping(definition, matcherChoiceInputTypeMapping);
            LOG.info("Parsed input type {} for entity {}", mapping.getGraphQlTypeName(), definition.getName());
            entityInputMap.put(definition.getName(), mapping);
        }

        for (EntityDefinition entityDefinition: entityDefinitions) {
            String entityName = entityDefinition.getName();
            EntityInputObjectTypeMapping sourceMapping = entityInputMap.get(entityName);

            Set<RelationshipDefinition> outboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getSourceEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityInputObjectTypeMapping>> outgoingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: outboundRelationships) {
                EntityInputObjectTypeMapping targetMapping = entityInputMap.get(definition.getTargetEntity());
                outgoingTuples.add(Tuple.of(definition, targetMapping));
            }

            Set<RelationshipDefinition> inboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getTargetEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityInputObjectTypeMapping>> incomingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: inboundRelationships) {
                EntityInputObjectTypeMapping targetMapping = entityInputMap.get(definition.getSourceEntity());
                incomingTuples.add(Tuple.of(definition, targetMapping));
            }

            sourceMapping.mapRelationships(incomingTuples, outgoingTuples, matcherChoiceInputTypeMapping);
        }

        LOG.info("Mapping complete");
        return entityInputMap.values().stream()
                .flatMap(mapping -> mapping.getInputObjectTypeDefinitions().stream())
                .collect(Collectors.toMap(InputObjectTypeDefinition::getName, inputObjectTypeDefinition -> inputObjectTypeDefinition, (existingValue, newValue) -> existingValue));
    }

    public Map<String, EntityInputObjectTypeMapping> getEntityMapping() {
        return entityInputMap;
    }

}
