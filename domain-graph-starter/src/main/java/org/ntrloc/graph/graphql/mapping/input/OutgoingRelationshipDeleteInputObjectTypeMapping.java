package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.List;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingRelationshipDeleteInputObjectTypeMapping extends RelationshipDeleteAbstractInputObjectTypeMapping implements OutgoingRelationshipInputTypeMapping, InputObjectTypeProducer {

    public OutgoingRelationshipDeleteInputObjectTypeMapping(LinkDefinition targetLinkDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super(String.format("%s %s %s Link Delete Input", targetLinkDefinition.getSourceEntity(), targetLinkDefinition.getSourceLabel(), targetLinkDefinition.getTargetEntity()), targetLinkDefinition, matcherChoiceMapping);
    }

    public LinkDefinition getTargetRelationshipDefinition() {
        return linkDefinition;
    }

    @Override
    public String getRelationshipSourceLabel() {
        return linkDefinition.getSourceLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name("target")
                .type(new TypeName(matcherChoiceMapping.getGraphQlTypeName()))
                .build();
        InputObjectTypeDefinition definition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(matcherValue))
                .build();
        return List.of(definition);
    }

}
