package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.Tuple;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.InputTypeProducer;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;
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
public class EntityMutationInputTypeMapping implements InputTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(EntityMutationInputTypeMapping.class);

    private static final String CREATE_MAPPING_KEY = "create";
    private static final String UPDATE_MAPPING_KEY = "update";
    private static final String DELETE_MAPPING_KEY = "delete";

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;

    private EntityCreateInputTypeMapping createInputTypeMapping;
    private EntityUpdateInputTypeMapping updateInputTypeMapping;
    private EntityDeleteInputTypeMapping deleteInputTypeMapping;

    public EntityMutationInputTypeMapping(EntityDefinition entityDefinition, MatcherChoiceInputTypeMapping matcherChoiceInputTypeMapping) {
        String typeName = String.format("%s Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.entityDefinition = entityDefinition;

        var inputPropertiesMapping = new EntityPropertiesInputTypeMapping(entityDefinition);

        this.createInputTypeMapping = new EntityCreateInputTypeMapping(entityDefinition, inputPropertiesMapping);
        this.updateInputTypeMapping = new EntityUpdateInputTypeMapping(entityDefinition, inputPropertiesMapping, matcherChoiceInputTypeMapping);
        this.deleteInputTypeMapping = new EntityDeleteInputTypeMapping(entityDefinition, matcherChoiceInputTypeMapping);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public void mapOutgoingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputTypeMapping>> relationshipTuples, MatcherChoiceInputTypeMapping matcherChoiceInputTypeMapping) {

        Map<String, OutgoingRelationshipCreateInputTypeMapping> outgoingRelationshipCreateInputTypeMappings = new HashMap<>();
        Map<String, OutgoingRelationshipChoiceInputTypeMapping> outgoingRelationshipChoiceInputTypeMappings = new HashMap<>();

        for (var tuple: relationshipTuples) {
            var relationshipDefinition = tuple.first();
            var targetMapping = tuple.second();

            // link properties type (PhotographerCreatedPhotoLinkProperties)

            LOG.info("Mapping outgoing relationship {} from {} to {}", relationshipDefinition.getName(), graphQlTypeName, targetMapping.getGraphQlTypeName());
            RelationshipPropertiesInputTypeMapping relationshipPropertiesInputTypeMapping = new RelationshipPropertiesInputTypeMapping(entityDefinition, relationshipDefinition);

            LOG.info("Got properties mapping {} for relationship {}", relationshipPropertiesInputTypeMapping.getGraphQlTypeName(), relationshipDefinition.getName());

            // link create type (PhotographerCreatedPhotoLinkCreateInput)
            OutgoingRelationshipCreateInputTypeMapping relationshipCreateType = new OutgoingRelationshipCreateInputTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link update type (PhotographerCreatedPhotoLinkUpdateInput)
            OutgoingRelationshipUpdateInputTypeMapping relationshipUpdateType = new OutgoingRelationshipUpdateInputTypeMapping(relationshipDefinition, relationshipPropertiesInputTypeMapping, matcherChoiceInputTypeMapping);

            // link delete type (PhotographerCreatedPhotoLinkDeleteInput)
            OutgoingRelationshipDeleteInputTypeMapping relationshipDeleteType = new OutgoingRelationshipDeleteInputTypeMapping(relationshipDefinition, matcherChoiceInputTypeMapping);

            // link modification type (PhotographerCreatedPhotoLinkModificationInput)
            OutgoingRelationshipChoiceInputTypeMapping relationshipChoiceType = new OutgoingRelationshipChoiceInputTypeMapping(relationshipDefinition, relationshipCreateType, relationshipUpdateType, relationshipDeleteType);

            outgoingRelationshipCreateInputTypeMappings.put(relationshipCreateType.getSourceLabel(), relationshipCreateType);
            outgoingRelationshipChoiceInputTypeMappings.put(relationshipCreateType.getSourceLabel(), relationshipChoiceType);

            LOG.info("rel types created");
        }

        // then, the entity-level create links input type (PhotographerCreateLinksInput)
        LOG.info("Create entity-wide links create type");
        var entityCreateLinksInputTypeMapping = new EntityCreateLinksInputTypeMapping(entityDefinition, outgoingRelationshipCreateInputTypeMappings);
        createInputTypeMapping.setLinkCreateInputType(entityCreateLinksInputTypeMapping);

        // and the entity-level (PhotographerCreateLinksUpdate, which really should be PhotographerUpdateLinksInput)
        LOG.info("Create entity-wide links update type");
        var entityUpdateLinksInputTypeMapping = new EntityUpdateLinksInputTypeMapping(entityDefinition, outgoingRelationshipChoiceInputTypeMappings);
        updateInputTypeMapping.setLinkUpdateInputType(entityUpdateLinksInputTypeMapping);

    }

    public void mapIncomingRelationships(List<Tuple<RelationshipDefinition, EntityMutationInputTypeMapping>> relationshipTuples) {
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
