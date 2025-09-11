package org.ntrloc.graph.graphql.mapping.selector;

import graphql.language.Directive;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.language.selectors.Selector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SelectorChoiceInputObjectTypeMapping implements SelectorInputObjectTypeMapping {

    private String graphQlTypeName;
    private Map<String, SelectorInputObjectTypeMapping> matchers = new HashMap<>();

    public SelectorChoiceInputObjectTypeMapping() {
        this.graphQlTypeName = CaseUtils.toCamelCase("Selector Input", true, '_', '-');

        matchers.put("all", new AllSelectorInputObjectTypeMapping());
        matchers.put("and", new AndSelectorInputObjectTypeMapping());
        matchers.put("not", new NotSelectorInputObjectTypeMapping());
        matchers.put("or", new OrSelectorInputObjectTypeMapping());
        matchers.put("property", new PropertySelectorInputObjectTypeMapping());
        matchers.put("propertyValue", new PropertyValueSelectorInputObjectTypeMapping());
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> matcherValueDefinitions = new ArrayList<>();

        var idMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("id").build();
        var refMatcherValue = InputValueDefinition.newInputValueDefinition().type(new TypeName("String")).name("ref").build();

        matcherValueDefinitions.add(idMatcherValue);
        matcherValueDefinitions.add(refMatcherValue);

        for (Map.Entry<String, SelectorInputObjectTypeMapping> entry : matchers.entrySet()) {
            String name = entry.getKey();
            SelectorInputObjectTypeMapping mapping = entry.getValue();
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

    public Selector parseSelector(ObjectValue objectValue) {
        ObjectField field = objectValue.getObjectFields().get(0);
        return switch (field.getName()) {
            case "id" -> new IdSelector(((StringValue)field.getValue()).getValue(), IdSelector.Type.GLOBAL);
            case "ref" -> new IdSelector(((StringValue)field.getValue()).getValue(), IdSelector.Type.LOCAL);
            default -> throw new IllegalArgumentException("Unknown selector choice: " + field.getName());
        };
    }

}
