package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.List;

/* Maps an entity to a GraphQL input object that represents a delete instruction. */
public class ItemDeleteInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public ItemDeleteInputObjectTypeMapping(ItemDefinition itemDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s Delete Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public ItemDefinition getEntityDefinition() {
        return itemDefinition;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition whereValue = InputValueDefinition.newInputValueDefinition()
                .name("where")
                .type(new NonNullType(new TypeName(matcherChoiceMapping.getGraphQlTypeName())))
                .build();
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sDeleteInput", itemDefinition.getName()))
                .inputValueDefinition(whereValue)
                .build());
    }
}
