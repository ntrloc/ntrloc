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

public class MatcherChoiceInputObjectTypeMapping implements MatcherInputObjectTypeMapping {

    private String graphQlTypeName;
    private Map<String, MatcherInputObjectTypeMapping> matchers = new HashMap<>();

    public MatcherChoiceInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Matcher Input", true, '_', '-');

        matchers.put("all", new AllMatcherInputObjectTypeMapping());
        matchers.put("and", new AndMatcherInputObjectTypeMapping());
        matchers.put("not", new NotMatcherInputObjectTypeMapping());
        matchers.put("or", new OrMatcherInputObjectTypeMapping());
        matchers.put("property", new PropertyMatcherInputObjectTypeMapping());
        matchers.put("propertyValue", new PropertyValueMatcherInputObjectTypeMapping());
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> matcherValueDefinitions = new ArrayList<>();

        var idMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("id").build();
        var refMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("ref").build();

        matcherValueDefinitions.add(idMatcherValue);
        matcherValueDefinitions.add(refMatcherValue);

        for (Map.Entry<String, MatcherInputObjectTypeMapping> entry : matchers.entrySet()) {
            String name = entry.getKey();
            MatcherInputObjectTypeMapping mapping = entry.getValue();
            var matcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName(mapping.getGraphQlTypeName())).name(name).build();
            matcherValueDefinitions.add(matcherValue);
        }

        List<InputObjectTypeDefinition> matcherInputTypes = matchers.values().stream().flatMap(m -> m.getInputObjectTypeDefinitions().stream()).toList();

        var choiiceMapperInputType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .directive(Directive.newDirective().name("oneOf").build())
                .inputValueDefinitions(matcherValueDefinitions)
                .build();

        List<InputObjectTypeDefinition> retDefinitions = new ArrayList<>(matcherInputTypes);
        retDefinitions.add(choiiceMapperInputType);
        return retDefinitions;

    }

    @Override
    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
