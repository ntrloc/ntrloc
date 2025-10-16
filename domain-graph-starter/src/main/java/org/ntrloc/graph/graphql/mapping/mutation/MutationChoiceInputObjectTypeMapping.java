package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.language.mutation.ItemMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;
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
    private Map<String, ItemMutationInputObjectTypeMapping> entityInputTypes = new java.util.HashMap<>();

    public MutationChoiceInputObjectTypeMapping(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions) {
        String typeName = String.format("Mutation Choice Input");
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');

        SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping = new SelectorChoiceInputObjectTypeMapping();

        for (ItemDefinition definition : itemDefinitions) {
            ItemMutationInputObjectTypeMapping mapping = new ItemMutationInputObjectTypeMapping(definition, matcherChoiceInputTypeMapping);
            LOG.info("Parsed input type {} for entity {}", mapping.getGraphQlTypeName(), definition.getName());
            entityInputTypes.put(definition.getName(), mapping);
        }

        for (ItemDefinition itemDefinition : itemDefinitions) {
            String entityName = itemDefinition.getName();
            ItemMutationInputObjectTypeMapping sourceMapping = entityInputTypes.get(entityName);

            Set<LinkDefinition> outboundRelationships = linkDefinitions.stream().filter(rel -> rel.getSourceItemType().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> outgoingTuples = new ArrayList<>();
            for (LinkDefinition definition: outboundRelationships) {
                ItemMutationInputObjectTypeMapping targetMapping = entityInputTypes.get(definition.getTargetItemType());
                outgoingTuples.add(Tuple.of(definition, targetMapping));
            }

            Set<LinkDefinition> inboundRelationships = linkDefinitions.stream().filter(rel -> rel.getTargetItemType().equals(entityName)).collect(Collectors.toSet());
            List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> incomingTuples = new ArrayList<>();
            for (LinkDefinition definition: inboundRelationships) {
                ItemMutationInputObjectTypeMapping targetMapping = entityInputTypes.get(definition.getSourceItemType());
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

    public Map<String, ItemMutationInputObjectTypeMapping> getEntityMapping() {
        return entityInputTypes;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        if (entityInputTypes.isEmpty()) {
            return List.of();
        } else {
            ArrayList<InputObjectTypeDefinition> allDefinitions = new ArrayList<>();
            List<InputValueDefinition> inputValues = new ArrayList<>();

            for (ItemMutationInputObjectTypeMapping mapping : entityInputTypes.values()) {
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

    public Map<String, List<ItemMutation>> parseEntityMutations(List<Map<String, Object>> mutationValues) {
        Map<String, List<Map<String, Object>>> mutationsByEntity = new java.util.HashMap<>();
        mutationValues.forEach(mutationValue -> {
            Map.Entry<String, Object> entry = mutationValue.entrySet().iterator().next();
            String itemType = entry.getKey();
            Map<String, Object> mutationValueObject = (Map)entry.getValue();
            List<Map<String, Object>> typeMutations = mutationsByEntity.getOrDefault(itemType, new ArrayList<>());
            typeMutations.add(mutationValueObject);
            mutationsByEntity.put(entry.getKey(), typeMutations);
        });

        Map<String, List<ItemMutation>> ret = mutationsByEntity.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            ItemMutationInputObjectTypeMapping mapping = entityInputTypes.get(entry.getKey());
            List<Map<String, Map<String, Object>>> mutationMaps = (List) entry.getValue();
            return mapping.parseEntityMutations(mutationMaps);
        }));

        return ret;
    }
}
