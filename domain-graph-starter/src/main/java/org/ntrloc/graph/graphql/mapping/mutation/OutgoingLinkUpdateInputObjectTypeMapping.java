package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.LinkUpdateMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingLinkUpdateInputObjectTypeMapping extends OutgoingLinkAbstractInputObjectTypeMapping {

    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String WHERE_FIELD_NAME = "where";

    public OutgoingLinkUpdateInputObjectTypeMapping(LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Update Input", targetLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name(PROPERTIES_FIELD_NAME)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name(WHERE_FIELD_NAME)
                .type(new TypeName(selectorChoiceInputObjectTypeMapping.getGraphQlTypeName()))
                .build();
        var thisDef = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(propertiesValue, matcherValue))
                .build();

        List<InputObjectTypeDefinition> retTypes = new ArrayList<>(selectorChoiceInputObjectTypeMapping.getInputObjectTypeDefinitions());
        retTypes.addAll(propertiesMapping.getInputObjectTypeDefinitions());
        retTypes.add(thisDef);
        return retTypes;
    }

    List<LinkUpdateMutation> parseLinkUpdateMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkUpdateMutation> retList = new ArrayList<>();

        for (var mutationMap : mutationObjects) {
            var whereObject = mutationMap.get(WHERE_FIELD_NAME);
            if (whereObject == null) {
                throw new IllegalArgumentException("Outgoing link create must contain a field named " + WHERE_FIELD_NAME);
            } else {
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector(whereObject);
                LinkUpdateMutation mutation = new LinkUpdateMutation();
                mutation.setLinkType(targetLinkDefinition.getSourceLabel());
                mutation.setSelector(selector);

                if (mutationMap.containsKey(PROPERTIES_FIELD_NAME)) {
                    List<Property> properties = propertiesMapping.mapProperties((Map<String, Object>) mutationMap.get(PROPERTIES_FIELD_NAME));
                    mutation.setProperties(properties);
                }

                retList.add(mutation);
            }
        }

        return retList;
    }

}
