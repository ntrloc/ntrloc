package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Matcher;
import org.ntrloc.graph.db.language.OrMatcher;

import java.util.List;

public class OrMatcherInputTypeMapping implements MatcherInputTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new OrMatcher();

    public OrMatcherInputTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Or Matcher Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition clauseValue = InputValueDefinition.newInputValueDefinition()
                .name("clauses")
                .type(new NonNullType(new ListType(new NonNullType(new TypeName("MatcherInput")))))
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
