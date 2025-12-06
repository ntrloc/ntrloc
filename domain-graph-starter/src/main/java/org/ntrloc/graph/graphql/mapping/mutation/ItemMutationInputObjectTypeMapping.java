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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Maps an entity to an input object that can contain a create, update, or delete operation for that specific entity.
 */
public class ItemMutationInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemMutationInputObjectTypeMapping.class);

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;

    private ItemCreateInputObjectTypeMapping createInputTypeMapping;
    private ItemUpdateInputObjectTypeMapping updateInputTypeMapping;
    private ItemDeleteInputObjectTypeMapping deleteInputTypeMapping;

    private InputObjectTypeDefinition choiceDefinition;

    public ItemMutationInputObjectTypeMapping(ItemDefinition itemDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        String typeName = String.format("%s Mutation Choice Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;

        var inputPropertiesMapping = new ItemPropertiesInputObjectTypeMapping(itemDefinition);

        this.createInputTypeMapping = new ItemCreateInputObjectTypeMapping(itemDefinition, inputPropertiesMapping);
        this.updateInputTypeMapping = new ItemUpdateInputObjectTypeMapping(itemDefinition, inputPropertiesMapping, matcherChoiceInputTypeMapping);
        this.deleteInputTypeMapping = new ItemDeleteInputObjectTypeMapping(itemDefinition, matcherChoiceInputTypeMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public ItemDefinition getEntityDefinition() {
        return itemDefinition;
    }

    public void mapRelationships(List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> incomingRelationshipTuples,
                                 List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> outgoingRelationshipTuples,
                                 SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        var incomingMappingTuples = mapIncomingRelationships(incomingRelationshipTuples, matcherChoiceInputTypeMapping);
        var outgoingMappingTuples = mapOutgoingRelationships(outgoingRelationshipTuples, matcherChoiceInputTypeMapping);

        // then, the entity-level create links input type (PhotographerCreateLinksInput)
        LOG.info("Create entity-wide links create type");
        var entityCreateLinksInputTypeMapping = new ItemCreateLinksInputObjectTypeMapping(itemDefinition, incomingMappingTuples.first(), outgoingMappingTuples.first());
        createInputTypeMapping.setLinkCreateInputType(entityCreateLinksInputTypeMapping);

        // and the entity-level (PhotographerCreateLinksUpdate, which really should be PhotographerUpdateLinksInput)
        LOG.info("Create entity-wide links update type");
        var entityUpdateLinksInputTypeMapping = new ItemUpdateLinksInputObjectTypeMapping(itemDefinition, incomingMappingTuples.second(), outgoingMappingTuples.second());
        updateInputTypeMapping.setLinkUpdateInputType(entityUpdateLinksInputTypeMapping);

    }

    private Tuple<Map<String, OutgoingLinkCreateInputObjectTypeMapping>, Map<String, OutgoingLinkChoiceInputObjectTypeMapping>> mapOutgoingRelationships(List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> relationshipTuples, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {

        Map<String, OutgoingLinkCreateInputObjectTypeMapping> outgoingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, OutgoingLinkChoiceInputObjectTypeMapping> outgoingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping outgoing relationship {} from {} to {}", relationshipDefinition.getUid(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            LinkPropertiesInputObjectTypeMapping relationshipPropertiesInputTypeMapping = new LinkPropertiesInputObjectTypeMapping(relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getUid());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            OutgoingLinkCreateInputObjectTypeMapping relationshipCreateType = new OutgoingLinkCreateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            OutgoingLinkUpdateInputObjectTypeMapping relationshipUpdateType = new OutgoingLinkUpdateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            OutgoingLinkDeleteInputObjectTypeMapping relationshipDeleteType = new OutgoingLinkDeleteInputObjectTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            OutgoingLinkChoiceInputObjectTypeMapping relationshipChoiceType = new OutgoingLinkChoiceInputObjectTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            outgoingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getRelationshipSourceLabel(), relationshipCreateType);
            outgoingRelationshipChoiceInputTypeMappings.put(relationshipChoiceType.getRelationshipSourceLabel(), relationshipChoiceType);

            LOG.info("rel types created");
        }

        return Tuple.of(outgoingRelationshipCreateInputTypeMappings, outgoingRelationshipChoiceInputTypeMappings);

    }

    private Tuple<Map<String, IncomingLinkCreateInputObjectTypeMapping>, Map<String, IncomingLinkChoiceInputObjectTypeMapping>> mapIncomingRelationships(List<Tuple<LinkDefinition, ItemMutationInputObjectTypeMapping>> relationshipTuples, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        Map<String, IncomingLinkCreateInputObjectTypeMapping> incomingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, IncomingLinkChoiceInputObjectTypeMapping> incomingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping incomgin relationship {} from {} to {}", relationshipDefinition.getUid(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            LinkPropertiesInputObjectTypeMapping relationshipPropertiesInputTypeMapping = new LinkPropertiesInputObjectTypeMapping(relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getUid());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            IncomingLinkCreateInputObjectTypeMapping relationshipCreateType = new IncomingLinkCreateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            IncomingLinkUpdateInputObjectTypeMapping relationshipUpdateType = new IncomingLinkUpdateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            IncomingLinkDeleteInputObjectTypeMapping relationshipDeleteType = new IncomingLinkDeleteInputObjectTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            IncomingLinkChoiceInputObjectTypeMapping relationshipChoiceType = new IncomingLinkChoiceInputObjectTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            incomingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getRelationshipTargetLabel(), relationshipCreateType);
            incomingRelationshipChoiceInputTypeMappings.put(relationshipChoiceType.getRelationshipTargetLabel(), relationshipChoiceType);

            LOG.info("incoming rel types created");
        }

        return Tuple.of(incomingRelationshipCreateInputTypeMappings, incomingRelationshipChoiceInputTypeMappings);
    }

    InputObjectTypeDefinition getChoiceInputTypeDefinition() {
        if (choiceDefinition == null) {
            InputValueDefinition createValue = InputValueDefinition.newInputValueDefinition()
                    .name(CREATE_MAPPING_KEY)
                    .type(new TypeName(createInputTypeMapping.getGraphQlTypeName()))
                    .build();
            InputValueDefinition updateValue = InputValueDefinition.newInputValueDefinition()
                    .name(UPDATE_MAPPING_KEY)
                    .type(new TypeName(updateInputTypeMapping.getGraphQlTypeName()))
                    .build();
            InputValueDefinition deleteValue = InputValueDefinition.newInputValueDefinition()
                    .name(DELETE_MAPPING_KEY)
                    .type(new TypeName(deleteInputTypeMapping.getGraphQlTypeName()))
                    .build();
            choiceDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                    .name(graphQlTypeName)
                    .directive(Directive.newDirective().name("oneOf").build())
                    .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                    .additionalData(InputTypeConstants.IS_TOP_LEVEL_ENTITY_INPUT_TYPE, Boolean.toString(true))
                    .build();
        }
        return choiceDefinition;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputObjectTypeDefinition> inputObjectTypeDefinitions = Stream.of(createInputTypeMapping, updateInputTypeMapping, deleteInputTypeMapping).flatMap(producer -> producer.getInputObjectTypeDefinitions().stream()).toList();
        List<InputObjectTypeDefinition> retList = new ArrayList<>();
        retList.add(getChoiceInputTypeDefinition());
        retList.addAll(inputObjectTypeDefinitions);
        return retList;
    }

    public List<ItemMutation> parseItemMutations(List<Map<String, Map<String, Object>>> objectValues) {
        List<ItemMutation> mutations = new ArrayList<>();

        for (Map<String, Map<String, Object>> objectValue: objectValues) {
            ItemMutation mutation;
            if (objectValue.containsKey(CREATE_MAPPING_KEY)) {
                var create = createInputTypeMapping.parseCreateMutation(objectValue.get(CREATE_MAPPING_KEY));
                create.setItemType(itemDefinition.getName());
                mutation = create;
            } else if (objectValue.containsKey(UPDATE_MAPPING_KEY)) {
                var update = updateInputTypeMapping.parseUpdateMutation(objectValue.get(UPDATE_MAPPING_KEY));
                update.setItemType(itemDefinition.getName());
                mutation = update;
            } else if (objectValue.containsKey(DELETE_MAPPING_KEY)) {
                var update = deleteInputTypeMapping.parseDeleteMutation(objectValue.get(DELETE_MAPPING_KEY));
                mutation = update;
            } else {
                throw new IllegalArgumentException("Cannot parse item mutation");
            }
            mutations.add(mutation);
        }

        return mutations;
    }

}
