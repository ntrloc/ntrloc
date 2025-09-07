package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

public abstract class OutgoingRelationshipAbstractInputObjectTypeMapping implements OutgoingRelationshipInputTypeMapping, InputObjectTypeProducer {

    protected RelationshipDefinition targetRelationshipDefinition;
    protected String graphQlTypeName;
    protected RelationshipPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    OutgoingRelationshipAbstractInputObjectTypeMapping(String typePattern, RelationshipDefinition targetRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format(typePattern, targetRelationshipDefinition.getSourceEntity(), targetRelationshipDefinition.getSourceLabel(), targetRelationshipDefinition.getTargetEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetRelationshipDefinition = targetRelationshipDefinition;
        this.propertiesMapping = propertiesMapping;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public RelationshipDefinition getTargetRelationshipDefinition() {
        return targetRelationshipDefinition;
    }

    @Override
    public String getRelationshipSourceLabel() {
        return targetRelationshipDefinition.getSourceLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name("target")
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
