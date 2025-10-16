package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

public abstract class OutgoingLinkAbstractInputObjectTypeMapping implements OutgoingLinkInputTypeMapping, InputObjectTypeProducer {

    protected String propertiesFieldName = "properties";
    protected String targetFieldName = "target";

    protected LinkDefinition targetLinkDefinition;
    protected String graphQlTypeName;
    protected LinkPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    OutgoingLinkAbstractInputObjectTypeMapping(String typePattern, LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format(typePattern, targetLinkDefinition.getSourceEntityUid(), targetLinkDefinition.getSourceLabel(), targetLinkDefinition.getTargetEntityUid());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.targetLinkDefinition = targetLinkDefinition;
        this.propertiesMapping = propertiesMapping;
        this.selectorChoiceInputObjectTypeMapping = selectorChoiceInputObjectTypeMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public LinkDefinition getTargetRelationshipDefinition() {
        return targetLinkDefinition;
    }

    @Override
    public String getRelationshipSourceLabel() {
        return targetLinkDefinition.getSourceLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name(propertiesFieldName)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name(targetFieldName)
                .type(new TypeName(selectorChoiceInputObjectTypeMapping.getGraphQlTypeName()))
                .build();
        var thisDef = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(propertiesValue, matcherValue))
                .build();

        List<InputObjectTypeDefinition> retTypes = new ArrayList<>(selectorChoiceInputObjectTypeMapping.getInputObjectTypeDefinitions());
        retTypes.addAll(propertiesMapping.getInputObjectTypeDefinitions());
        retTypes.add(thisDef);
        return retTypes;
    }

}
