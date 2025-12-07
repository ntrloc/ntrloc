package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingLinkUpdateInputObjectTypeMapping extends IncomingLinkAbstractInputObjectTypeMapping {

    private static final String WHERE_FIELD_NAME = "where";

    public IncomingLinkUpdateInputObjectTypeMapping(LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        super("%s %s %s Link Update Input", sourceLinkDefinition, propertiesMapping, matcherChoiceMapping);
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name(propertyFieldName)
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

}
