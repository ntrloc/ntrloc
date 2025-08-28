package org.ntrloc.graph.graphql.mapping;

import graphql.language.InputObjectTypeDefinition;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.input.EntityMutationInputTypeMapping;
import org.ntrloc.graph.graphql.mapping.input.InputTypeConstants;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class EntityInputTypesMapper {

    private static final Logger LOG = LoggerFactory.getLogger(EntityInputTypesMapper.class);

    public void parseEntityInputTypes(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions, MatcherChoiceInputTypeMapping matcherChoiceInputTypeMapping) {
        Map<String, EntityMutationInputTypeMapping> entityInputMap = new HashMap<>();

        for (EntityDefinition definition : entityDefinitions) {
            EntityMutationInputTypeMapping mapping = new EntityMutationInputTypeMapping(definition, matcherChoiceInputTypeMapping);
            LOG.info("Parsed input type {} for entity {}", mapping.getGraphQlTypeName(), definition.getName());
            entityInputMap.put(definition.getName(), mapping);
        }

        for (EntityDefinition entityDefinition: entityDefinitions) {
            String entityName = entityDefinition.getName();
            EntityMutationInputTypeMapping sourceMapping = entityInputMap.get(entityName);

            Set<RelationshipDefinition> outboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getSourceEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityMutationInputTypeMapping>> outgoingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: outboundRelationships) {
                EntityMutationInputTypeMapping targetMapping = entityInputMap.get(definition.getTargetEntity());
                outgoingTuples.add(Tuple.of(definition, targetMapping));
            }
            sourceMapping.mapOutgoingRelationships(outgoingTuples, matcherChoiceInputTypeMapping);

            Set<RelationshipDefinition> inboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getTargetEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityMutationInputTypeMapping>> incomingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: inboundRelationships) {
                EntityMutationInputTypeMapping targetMapping = entityInputMap.get(definition.getSourceEntity());
                incomingTuples.add(Tuple.of(definition, targetMapping));
            }
            sourceMapping.mapIncomingRelationships(incomingTuples);
        }

        LOG.info("Mapping complete");

        Map<String, InputObjectTypeDefinition> inputObjectTypeDefinitionMap = entityInputMap.values().stream()
                .flatMap(mapping -> mapping.getInputObjectTypeDefinitions().stream())
                .collect(Collectors.toMap(InputObjectTypeDefinition::getName, inputObjectTypeDefinition -> inputObjectTypeDefinition, (existingValue, newValue) -> existingValue));

        List<InputObjectTypeDefinition> entityMutationDefinitions =inputObjectTypeDefinitionMap.values().stream()
                .filter(def -> def.getAdditionalData().getOrDefault(InputTypeConstants.IS_TOP_LEVEL_ENTITY_INPUT_TYPE, Boolean.toString(false)).equals(Boolean.toString(true))).collect(Collectors.toList());

        LOG.info("Got input types {}", inputObjectTypeDefinitionMap);

    }

}
