package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.OrItemSelector;

import java.util.List;

public class OrSelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;
    private OrItemSelector selector = new OrItemSelector();

    public OrSelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Or Selector Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition clauseValue = InputValueDefinition.newInputValueDefinition()
                .name("clauses")
                .type(new NonNullType(new ListType(new NonNullType(new TypeName("SelectorInput")))))
                .build();
        var orMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(clauseValue)
                .build();
        return List.of(orMatcherInput);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
