package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Matcher;
import org.ntrloc.graph.db.language.NotMatcher;

import java.util.List;

public class NotMatcherInputTypeMapping implements MatcherInputTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new NotMatcher();

    public NotMatcherInputTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Not Matcher Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition matchValue = InputValueDefinition.newInputValueDefinition()
                .name("match")
                .type(new NonNullType(new ListType(new NonNullType(new TypeName("MatcherInput")))))
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

}
