package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.List;

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipDeleteInputObjectTypeMapping extends RelationshipDeleteAbstractInputObjectTypeMapping implements IncomingRelationshipInputTypeMapping, InputObjectTypeProducer {

    public IncomingRelationshipDeleteInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super(String.format("%s %s %s Link Delete Input", sourceLinkDefinition.getTargetEntity(), sourceLinkDefinition.getTargetLabel(), sourceLinkDefinition.getSourceEntity()), sourceLinkDefinition, matcherChoiceMapping);
    }

    public LinkDefinition getSourceRelationshipDefinition() {
        return linkDefinition;
    }

    @Override
    public String getRelationshipTargetLabel() {
        return linkDefinition.getTargetLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name("source")
                .type(new TypeName(matcherChoiceMapping.getGraphQlTypeName()))
                .build();
        InputObjectTypeDefinition definition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(matcherValue))
                .build();
        return List.of(definition);
    }

}
