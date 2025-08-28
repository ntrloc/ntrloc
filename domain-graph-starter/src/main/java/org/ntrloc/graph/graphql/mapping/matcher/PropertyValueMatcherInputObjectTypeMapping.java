package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Matcher;
import org.ntrloc.graph.db.language.PropertyValueMatcher;

import java.util.List;

public class PropertyValueMatcherInputObjectTypeMapping implements MatcherInputObjectTypeMapping {

    private String graphQlTypeName;
    private Matcher matcher = new PropertyValueMatcher();

    public PropertyValueMatcherInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Property Value Matcher Input", true, '_', '-');
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(
                        InputValueDefinition.newInputValueDefinition().name("name").type(new TypeName("String")).build(),
                        InputValueDefinition.newInputValueDefinition().name("value").type(new TypeName("String")).build()
                ))
                .build());
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
