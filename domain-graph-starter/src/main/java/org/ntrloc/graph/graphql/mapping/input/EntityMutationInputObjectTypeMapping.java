package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Maps an entity to an input object that can contain a create, update, or delete operation.
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

    public EntityMutationInputObjectTypeMapping(EntityDefinition entityDefinition, MatcherChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {
        String typeName = String.format("%s Input", entityDefinition.getName());
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

    public void mapOutgoingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> relationshipTuples, MatcherChoiceInputObjectTypeMapping matcherChoiceInputTypeMapping) {

        Map<String, OutgoingRelationshipCreateInputObjectTypeMapping> outgoingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, OutgoingRelationshipChoiceInputObjectTypeMapping> outgoingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping outgoing relationship {} from {} to {}", relationshipDefinition.getName(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            RelationshipPropertiesInputObjectTypeMapping relationshipPropertiesInputTypeMapping = new RelationshipPropertiesInputObjectTypeMapping(entityDefinition, relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getName());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            OutgoingRelationshipCreateInputObjectTypeMapping relationshipCreateType = new OutgoingRelationshipCreateInputObjectTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            OutgoingRelationshipUpdateInputTypeMapping relationshipUpdateType = new OutgoingRelationshipUpdateInputTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            OutgoingRelationshipDeleteInputTypeMapping relationshipDeleteType = new OutgoingRelationshipDeleteInputTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            OutgoingRelationshipChoiceInputObjectTypeMapping relationshipChoiceType = new OutgoingRelationshipChoiceInputObjectTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            outgoingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getSourceLabel(), relationshipCreateType);
            outgoingRelationshipChoiceInputTypeMappings.put(relationshipCreateType.getSourceLabel(), relationshipChoiceType);

            LOG.info("rel types created");
        }

        // then, the entity-level create links input type (PhotographerCreateLinksInput)
        LOG.info("Create entity-wide links create type");
        var entityCreateLinksInputTypeMapping = new EntityCreateLinksInputObjectTypeMapping(entityDefinition, outgoingRelationshipCreateInputTypeMappings);
        createInputTypeMapping.setLinkCreateInputType(entityCreateLinksInputTypeMapping);

        // and the entity-level (PhotographerCreateLinksUpdate, which really should be PhotographerUpdateLinksInput)
        LOG.info("Create entity-wide links update type");
        var entityUpdateLinksInputTypeMapping = new EntityUpdateLinksInputObjectTypeMapping(entityDefinition, outgoingRelationshipChoiceInputTypeMappings);
        updateInputTypeMapping.setLinkUpdateInputType(entityUpdateLinksInputTypeMapping);

    }

    public void mapIncomingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputObjectTypeMapping>> relationshipTuples) {
        //LOG.info("Mapping incoming relationship {} from {} to {}", relationshipDefinition.getName(), graphQlTypeName, sourceMapping.getGraphQlTypeName());
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {

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
        InputObjectTypeDefinition mutationDefinition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(List.of(createValue, updateValue, deleteValue))
                .additionalData(InputTypeConstants.IS_TOP_LEVEL_ENTITY_INPUT_TYPE, Boolean.toString(true))
                .build();

        List<InputObjectTypeDefinition> inputObjectTypeDefinitions = Stream.of(createInputTypeMapping, updateInputTypeMapping, deleteInputTypeMapping).flatMap(producer -> producer.getInputObjectTypeDefinitions().stream()).toList();

        List<InputObjectTypeDefinition> retList = new ArrayList<>();
        retList.add(mutationDefinition);
        retList.addAll(inputObjectTypeDefinitions);

        return retList;
    }

}
