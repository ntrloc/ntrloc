package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.mutation.ItemDeleteMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.List;
import java.util.Map;

/* Maps an entity to a GraphQL input object that represents a delete instruction. */
public class ItemDeleteInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final String WHERE_FIELD_NAME = "where";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    public ItemDeleteInputObjectTypeMapping(ItemDefinition itemDefinition, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format("%s Delete Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;
        this.selectorChoiceInputObjectTypeMapping = selectorChoiceInputObjectTypeMapping;
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
                .name(WHERE_FIELD_NAME)
                .type(new NonNullType(new TypeName(selectorChoiceInputObjectTypeMapping.getGraphQlTypeName())))
                .build();
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sDeleteInput", itemDefinition.getName()))
                .inputValueDefinition(whereValue)
                .build());
    }

    public ItemDeleteMutation parseDeleteMutation(Map<String, Object> inputMap) {
        ItemDeleteMutation mutation;

        if (inputMap.containsKey(WHERE_FIELD_NAME)) {
            Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector((Map<String, Object>) inputMap.get(WHERE_FIELD_NAME));
            mutation = new ItemDeleteMutation(selector);
        } else {
            throw new IllegalArgumentException("Update mutation must contain a field named " + WHERE_FIELD_NAME);
        }

        return mutation;
    }
}
