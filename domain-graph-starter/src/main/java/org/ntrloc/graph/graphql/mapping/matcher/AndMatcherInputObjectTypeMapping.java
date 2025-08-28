package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.AndMatcher;
import org.ntrloc.graph.db.language.Matcher;

import java.util.List;

public class AndMatcherInputObjectTypeMapping implements MatcherInputObjectTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new AndMatcher();

    public AndMatcherInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("And Matcher Input", true, '_', '-');
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition clauseValue = InputValueDefinition.newInputValueDefinition().name("clauses").type(new ListType(new NonNullType(new TypeName("MatcherInput")))).build();
        var andMatcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(clauseValue)
                .build();
        return List.of(andMatcherInput);
    }
}
