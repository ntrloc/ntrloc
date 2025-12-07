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

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkDeleteInputObjectTypeMapping extends LinkDeleteAbstractInputObjectTypeMapping implements IncomingLinkInputTypeMapping, InputObjectTypeProducer {

    public IncomingLinkDeleteInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super(String.format("%s %s %s Link Delete Input", sourceLinkDefinition.getTargetItemType(), sourceLinkDefinition.getTargetLabel(), sourceLinkDefinition.getSourceItemType()), sourceLinkDefinition, matcherChoiceMapping);
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
                .name(WHERE_FIELD_NAME)
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
            var whereObject = mutationMap.get(WHERE_FIELD_NAME);
            if (whereObject == null) {
                throw new IllegalArgumentException("Outgoing link create must contain a field named " + WHERE_FIELD_NAME);
            } else {
                Selector selector = matcherChoiceMapping.parseSelector(whereObject);
                LinkDeleteMutation mutation = new LinkDeleteMutation().selector(selector);
                mutation.setLinkType(linkDefinition.getSourceLabel());

                retList.add(mutation);
            }
        }

        return retList;
    }

}
