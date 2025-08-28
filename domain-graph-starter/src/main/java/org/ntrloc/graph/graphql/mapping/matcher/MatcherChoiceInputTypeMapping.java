package org.ntrloc.graph.graphql.mapping.matcher;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MatcherChoiceInputTypeMapping implements MatcherInputTypeMapping {

    private String graphQlTypeName;
    private Map<String, MatcherInputTypeMapping> matchers = new HashMap<>();

    public MatcherChoiceInputTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Matcher Input", true, '_', '-');

        matchers.put("all", new AllMatcherInputTypeMapping());
        matchers.put("and", new AndMatcherInputTypeMapping());
        matchers.put("not", new NotMatcherInputTypeMapping());
        matchers.put("or", new OrMatcherInputTypeMapping());
        matchers.put("property", new PropertyMatcherInputTypeMapping());
        matchers.put("propertyValue", new PropertyValueMatcherInputTypeMapping());
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> matcherValueDefinitions = new ArrayList<>();

        var idMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("id").build();
        var refMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("ref").build();

        matcherValueDefinitions.add(idMatcherValue);
        matcherValueDefinitions.add(refMatcherValue);

        for (Map.Entry<String, MatcherInputTypeMapping> entry : matchers.entrySet()) {
            String name = entry.getKey();
            MatcherInputTypeMapping mapping = entry.getValue();
            var matcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName(mapping.getGraphQlTypeName())).name(name).build();
            matcherValueDefinitions.add(matcherValue);
        }

        var matcherInput = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(matcherValueDefinitions)
                .build();

        return List.of(matcherInput);
    }

    @Override
    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
