package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;
import java.util.Map;

public class AndSelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;

    public AndSelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("And Selector Input", true, '_', '-');
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition clauseValue = InputValueDefinition.newInputValueDefinition().name("clauses").type(new ListType(new NonNullType(new TypeName("SelectorInput")))).build();
        var andMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(clauseValue)
                .build();
        return List.of(andMatcherInput);
    }

    @Override
    public Selector parseSelector(Map<String, Object> objectValue) {
        return null;
    }
}
