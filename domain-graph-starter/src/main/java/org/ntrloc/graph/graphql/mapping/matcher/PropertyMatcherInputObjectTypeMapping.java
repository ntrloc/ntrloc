package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Matcher;
import org.ntrloc.graph.db.language.OrMatcher;

import java.util.List;

public class PropertyMatcherInputObjectTypeMapping implements MatcherInputObjectTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new OrMatcher();

    public PropertyMatcherInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Property Matcher Input", true, '_', '-');
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
}
