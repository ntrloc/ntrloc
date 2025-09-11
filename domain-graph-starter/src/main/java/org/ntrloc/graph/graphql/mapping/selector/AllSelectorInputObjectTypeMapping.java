package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.BooleanValue;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;

public class AllSelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;

    public AllSelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("All Selector Input", true, '_', '-');
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                        .name("selectAll")
                        .type(new TypeName("Boolean"))
                        .defaultValue(BooleanValue.of(true))
                        .build())
                .build());
    }

    @Override
    public Selector parseSelector(ObjectValue objectValue) {
        return null;
    }
}
