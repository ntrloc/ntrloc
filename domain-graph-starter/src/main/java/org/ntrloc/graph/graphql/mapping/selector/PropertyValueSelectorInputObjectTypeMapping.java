package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.HasPropertyValueSelector;
import org.ntrloc.graph.db.language.selectors.ItemSelector;

import java.util.List;

public class PropertyValueSelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;
    private ItemSelector selector = new HasPropertyValueSelector();

    public PropertyValueSelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Property Value Selector Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(
                        InputValueDefinition.newInputValueDefinition().name("name").type(new TypeName("String")).build(),
                        InputValueDefinition.newInputValueDefinition().name("value").type(new TypeName("String")).build()
                ))
                .build());
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
