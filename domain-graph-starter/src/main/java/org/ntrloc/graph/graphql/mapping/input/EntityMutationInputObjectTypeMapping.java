package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.language.mutation.EntityMutation;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
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
public class EntityMutationInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EntityMutationInputObjectTypeMapping.class);

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;

    private EntityCreateInputObjectTypeMapping createInputTypeMapping;
    private EntityUpdateInputObjectTypeMapping updateInputTypeMapping;
    private EntityDeleteInputObjectTypeMapping deleteInputTypeMapping;

    private InputObjectTypeDefinition choiceDefinition;

    public EntityMutationInputObjectTypeMapping(EntityDefinition entityDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        String typeName = String.format("%s Mutation Choice Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.entityDefinition = entityDefinition;

        var inputPropertiesMapping = new EntityPropertiesInputObjectTypeMapping(entityDefinition);

        this.createInputTypeMapping = new EntityCreateInputObjectTypeMapping(entityDefinition, inputPropertiesMapping);
        this.updateInputTypeMapping = new EntityUpdateInputObjectTypeMapping(entityDefinition, inputPropertiesMapping, matcherChoiceInputTypeMapping);
        this.deleteInputTypeMapping = new EntityDeleteInputObjectTypeMapping(entityDefinition, matcherChoiceInputTypeMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public void mapRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> incomingRelationshipTuples,
                                 List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> outgoingRelationshipTuples,
                                 SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        var incomingMappingTuples = mapIncomingRelationships(incomingRelationshipTuples, matcherChoiceInputTypeMapping);
        var outgoingMappingTuples = mapOutgoingRelationships(outgoingRelationshipTuples, matcherChoiceInputTypeMapping);

        // then, the entity-level create links input type (PhotographerCreateLinksInput)
        LOG.info("Create entity-wide links create type");
        var entityCreateLinksInputTypeMapping = new EntityCreateLinksInputObjectTypeMapping(entityDefinition, incomingMappingTuples.first(), outgoingMappingTuples.first());
        createInputTypeMapping.setLinkCreateInputType(entityCreateLinksInputTypeMapping);

        // and the entity-level (PhotographerCreateLinksUpdate, which really should be PhotographerUpdateLinksInput)
        LOG.info("Create entity-wide links update type");
        var entityUpdateLinksInputTypeMapping = new EntityUpdateLinksInputObjectTypeMapping(entityDefinition, incomingMappingTuples.second(), outgoingMappingTuples.second());
        updateInputTypeMapping.setLinkUpdateInputType(entityUpdateLinksInputTypeMapping);

    }

    private Tuple<Map<String, OutgoingRelationshipCreateInputObjectTypeMapping>, Map<String, OutgoingRelationshipChoiceInputObjectTypeMapping>> mapOutgoingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> relationshipTuples, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {

        Map<String, OutgoingRelationshipCreateInputObjectTypeMapping> outgoingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, OutgoingRelationshipChoiceInputObjectTypeMapping> outgoingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping outgoing relationship {} from {} to {}", relationshipDefinition.getName(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            RelationshipPropertiesInputObjectTypeMapping relationshipPropertiesInputTypeMapping = new RelationshipPropertiesInputObjectTypeMapping(relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getName());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            OutgoingRelationshipCreateInputObjectTypeMapping relationshipCreateType = new OutgoingRelationshipCreateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            OutgoingRelationshipUpdateInputObjectTypeMapping relationshipUpdateType = new OutgoingRelationshipUpdateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            OutgoingRelationshipDeleteInputObjectTypeMapping relationshipDeleteType = new OutgoingRelationshipDeleteInputObjectTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            OutgoingRelationshipChoiceInputObjectTypeMapping relationshipChoiceType = new OutgoingRelationshipChoiceInputObjectTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            outgoingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getSourceLabel(), relationshipCreateType);
            outgoingRelationshipChoiceInputTypeMappings.put(relationshipChoiceType.getSourceLabel(), relationshipChoiceType);

            LOG.info("rel types created");
        }

        return Tuple.of(outgoingRelationshipCreateInputTypeMappings, outgoingRelationshipChoiceInputTypeMappings);

    }

    private Tuple<Map<String, IncomingRelationshipCreateInputObjectTypeMapping>, Map<String, IncomingRelationshipChoiceInputObjectTypeMapping>> mapIncomingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> relationshipTuples, SelectorChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        Map<String, IncomingRelationshipCreateInputObjectTypeMapping> incomingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, IncomingRelationshipChoiceInputObjectTypeMapping> incomingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping incomgin relationship {} from {} to {}", relationshipDefinition.getName(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            RelationshipPropertiesInputObjectTypeMapping relationshipPropertiesInputTypeMapping = new RelationshipPropertiesInputObjectTypeMapping(relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getName());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            IncomingRelationshipCreateInputObjectTypeMapping relationshipCreateType = new IncomingRelationshipCreateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            IncomingRelationshipUpdateInputObjectTypeMapping relationshipUpdateType = new IncomingRelationshipUpdateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            IncomingRelationshipDeleteInputObjectTypeMapping relationshipDeleteType = new IncomingRelationshipDeleteInputObjectTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            IncomingRelationshipChoiceInputObjectTypeMapping relationshipChoiceType = new IncomingRelationshipChoiceInputObjectTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            incomingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getTargetLabel(), relationshipCreateType);
            incomingRelationshipChoiceInputTypeMappings.put(relationshipChoiceType.getTargetLabel(), relationshipChoiceType);

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

    public List<EntityMutation> parseEntityMutations(List<ObjectValue> objectValues) {
        List<EntityMutation> mutations = new ArrayList<>();
        for (var objectValue: objectValues) {
            ObjectField field = objectValue.getObjectFields().get(0); // there can only be one field
            var mutation = switch (field.getName()) {
                case CREATE_MAPPING_KEY -> createInputTypeMapping.parseCreateMutation((ObjectValue) field.getValue());
                case UPDATE_MAPPING_KEY -> throw new IllegalArgumentException("Cannot update an entity-level mutation");
                case DELETE_MAPPING_KEY -> throw new IllegalArgumentException("Cannot delete an entity-level mutation");
                default -> throw new IllegalArgumentException("Unknown mutation type: " + field.getName());
            };
            mutation.setEntityType(entityDefinition.getName());
            mutations.add(mutation);
        }

        return mutations;
    }

}
