package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.BooleanValue;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.AllNodesMatcher;
import org.ntrloc.graph.db.language.Matcher;

import java.util.List;

public class AllMatcherInputTypeMapping implements MatcherInputTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new AllNodesMatcher();

    public AllMatcherInputTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("All Matcher Input", true, '_', '-');
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinition(InputValueDefinition.newInputValueDefinition()
                        .name("matchAll")
                        .type(new TypeName("Boolean"))
                        .defaultValue(BooleanValue.of(true))
                        .build())
                .build());
    }

}
