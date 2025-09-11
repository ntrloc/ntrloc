package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.NotItemSelector;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.List;

public class NotSelectorInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;
    private NotItemSelector selector = new NotItemSelector();

    public NotSelectorInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Not Selector Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition matchValue = InputValueDefinition.newInputValueDefinition()
                .name("select")
                .type(new NonNullType(new ListType(new NonNullType(new TypeName("SelectorInput")))))
                .build();
        var notMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(matchValue)
                .build();
        return List.of(notMatcherInput);
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public Selector parseSelector(ObjectValue objectValue) {
        return null;
    }
}
