package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import graphql.language.Value;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * Maps all entity types to an object that can be used to execute a mutation on one of them.
 *
 * For example, if there are 3 entities (Student, Class, and School), this will define a GraphQL input object
 * that allows the submission of a single Student mutation, Class mutation, or School mutation.
 */
public class MutationChoiceInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(MutationChoiceInputObjectTypeMapping.class);

    private String graphQlTypeName;
    private Map<String, EntityMutationInputObjectTypeMapping> entityInputTypes = new java.util.HashMap<>();

    public MutationChoiceInputObjectTypeMapping(Set<EntityDefinition> entityDefinitions, Set<RelationshipDefinition> relationshipDefinitions) {
        String typeName = String.format("Mutation Choice Input");
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        MatcherChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping = new MatcherChoiceInputObjectTypeMapping();

        for (EntityDefinition definition : entityDefinitions) {
            EntityMutationInputObjectTypeMapping mapping = new EntityMutationInputObjectTypeMapping(definition, matcherChoiceInputTypeMapping);
            LOG.info("Parsed input type {} for entity {}", mapping.getGraphQlTypeName(), definition.getName());
            entityInputTypes.put(definition.getName(), mapping);
        }

        for (EntityDefinition entityDefinition: entityDefinitions) {
            String entityName = entityDefinition.getName();
            EntityMutationInputObjectTypeMapping sourceMapping = entityInputTypes.get(entityName);

            Set<RelationshipDefinition> outboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getSourceEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> outgoingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: outboundRelationships) {
                EntityMutationInputObjectTypeMapping targetMapping = entityInputTypes.get(definition.getTargetEntity());
                outgoingTuples.add(Tuple.of(definition, targetMapping));
            }

            Set<RelationshipDefinition> inboundRelationships = relationshipDefinitions.stream().filter(rel -> rel.getTargetEntity().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> incomingTuples = new ArrayList<>();
            for (RelationshipDefinition definition: inboundRelationships) {
                EntityMutationInputObjectTypeMapping targetMapping = entityInputTypes.get(definition.getSourceEntity());
                incomingTuples.add(Tuple.of(definition, targetMapping));
            }

            sourceMapping.mapRelationships(incomingTuples, outgoingTuples, matcherChoiceInputTypeMapping);
        }
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public boolean isEmpty() {
        return entityInputTypes.isEmpty();
    }

    public Map<String, EntityMutationInputObjectTypeMapping> getEntityMapping() {
        return entityInputTypes;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        if (entityInputTypes.isEmpty()) {
            return List.of();
        } else {
            ArrayList<InputObjectTypeDefinition> allDefinitions = new ArrayList<>();
            List<InputValueDefinition> inputValues = new ArrayList<>();

            for (EntityMutationInputObjectTypeMapping mapping : entityInputTypes.values()) {
                allDefinitions.addAll(mapping.getInputObjectTypeDefinitions());

                InputValueDefinition inputValue = InputValueDefinition.newInputValueDefinition()
                        .name(mapping.getEntityDefinition().getName())
                        .type(new TypeName(mapping.getGraphQlTypeName()))
                        .build();
                inputValues.add(inputValue);
            }

            InputObjectTypeDefinition mutationDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                    .name(graphQlTypeName)
                    .directive(Directive.newDirective().name("oneOf").build())
                    .inputValueDefinitions(inputValues)
                    .build();
            allDefinitions.add(mutationDefinition);

            return allDefinitions;
        }
    }

    public Map<String, List<EntityMutation>> parseEntityMutations(List<Value> mutationValues) {
        List<ObjectValue> objectValues = mutationValues.stream().map(v -> (ObjectValue) v).collect(Collectors.toList());
        List<ObjectField> objectFields = objectValues.stream().map(o -> o.getObjectFields().get(0)).collect(Collectors.toList());
        Map<String, List<ObjectValue>> mutationsByEntity = objectFields.stream().collect(
                Collectors.groupingBy(
                        ObjectField::getName,
                        Collectors.mapping(f -> (ObjectValue) f.getValue(), Collectors.toList())
                )
        );

        Map<String, List<EntityMutation>> ret = mutationsByEntity.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            EntityMutationInputObjectTypeMapping mapping = entityInputTypes.get(entry.getKey());
            return mapping.parseEntityMutations(entry.getValue());
        }));

        LOG.info(">?");
        return ret;
    }
}
