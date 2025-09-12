package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.HasPropertySelector;
import org.ntrloc.graph.db.language.selectors.ItemSelector;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;
import java.util.Map;

public class PropertySelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;
    private ItemSelector selector = new HasPropertySelector();

    public PropertySelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Property Selector Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition().name("name").type(new TypeName("String")).build())
                .build());
    }

    @Override
    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public Selector parseSelector(Map<String, Object> objectValue) {
        return null;
    }
}
