package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.mutation.LinkDeleteMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingLinkDeleteInputObjectTypeMapping extends LinkDeleteAbstractInputObjectTypeMapping implements OutgoingLinkInputTypeMapping, InputObjectTypeProducer {

    public OutgoingLinkDeleteInputObjectTypeMapping(LinkDefinition targetLinkDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super(String.format("%s %s %s Link Delete Input", targetLinkDefinition.getSourceItemType(), targetLinkDefinition.getSourceLabel(), targetLinkDefinition.getTargetItemType()), targetLinkDefinition, matcherChoiceMapping);
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
                .name(targetFieldName)
                .type(new TypeName(matcherChoiceMapping.getGraphQlTypeName()))
                .build();
        InputObjectTypeDefinition definition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(matcherValue))
                .build();
        return List.of(definition);
    }

    List<LinkDeleteMutation> parseLinkDeleteMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkDeleteMutation> retList = new ArrayList<>();

        for (var mutationMap : mutationObjects) {
            var targetObject = mutationMap.get(targetFieldName);
            if (targetObject == null) {
                throw new IllegalArgumentException("Outgoing link create must contain a field named " + targetFieldName);
            } else {
                Selector selector = matcherChoiceMapping.parseSelector(targetObject);
                LinkDeleteMutation mutation = new LinkDeleteMutation().selector(selector);
                mutation.setLinkType(linkDefinition.getSourceLabel());

                retList.add(mutation);
            }
        }

        return retList;
    }

}
