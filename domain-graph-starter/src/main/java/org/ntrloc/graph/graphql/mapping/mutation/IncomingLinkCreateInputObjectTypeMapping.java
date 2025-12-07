package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkCreateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    private static final Logger LOG = LoggerFactory.getLogger(IncomingLinkCreateInputObjectTypeMapping.class);
    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String SOURCE_FIELD_NAME = "source";

    public IncomingLinkCreateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        super("%s %s %s Link Create Input", sourceLinkDefinition, propertiesMapping, selectorChoiceInputObjectTypeMapping);
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name(propertyFieldName)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name(SOURCE_FIELD_NAME)
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

    List<LinkCreateMutation> parseLinkCreateMutations(List<Map<String, Map<String, Object>>> mutationObjects) {
        List<LinkCreateMutation> retList = new ArrayList<>();

        for (var mutationMap : mutationObjects) {
            var targetObject = mutationMap.get(SOURCE_FIELD_NAME);
            if (targetObject == null) {
                throw new IllegalArgumentException("Incoming link create must contain a field named " + SOURCE_FIELD_NAME);
            } else {
                Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector(targetObject);
                LinkCreateMutation mutation = new LinkCreateMutation();
                mutation.setLinkType(sourceLinkDefinition.getTargetLabel());
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
