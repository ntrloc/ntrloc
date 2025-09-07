package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

public class IncomingRelationshipAbstractInputObjectTypeMapping implements IncomingRelationshipInputTypeMapping, InputObjectTypeProducer {

    protected String graphQlTypeName;
    protected RelationshipDefinition sourceRelationshipDefinition;
    protected RelationshipPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public IncomingRelationshipAbstractInputObjectTypeMapping(String namePattern, RelationshipDefinition sourceRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format(namePattern, sourceRelationshipDefinition.getTargetEntity(), sourceRelationshipDefinition.getTargetLabel(), sourceRelationshipDefinition.getSourceEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.sourceRelationshipDefinition = sourceRelationshipDefinition;
        this.propertiesMapping = propertiesMapping;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public RelationshipDefinition getSourceRelationshipDefinition() {
        return sourceRelationshipDefinition;
    }

    @Override
    public String getRelationshipTargetLabel() {
        return sourceRelationshipDefinition.getTargetLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name("source")
                .type(new TypeName(matcherChoiceMapping.getGraphQlTypeName()))
                .build();
        var thisDef = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(propertiesValue, matcherValue))
                .build();
        List<InputObjectTypeDefinition> retTypes = new ArrayList<>(matcherChoiceMapping.getInputObjectTypeDefinitions());
        retTypes.addAll(propertiesMapping.getInputObjectTypeDefinitions());
        retTypes.add(thisDef);
        return retTypes;
    }

}
