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

public class IncomingLinkAbstractInputObjectTypeMapping implements IncomingLinkInputTypeMapping, InputObjectTypeProducer {

    String propertyFieldName = "properties";
    String sourceFieldName = "source";

    protected String graphQlTypeName;
    protected LinkDefinition sourceLinkDefinition;
    protected LinkPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    public IncomingLinkAbstractInputObjectTypeMapping(String namePattern, LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format(namePattern, sourceLinkDefinition.getTargetEntityUid(), sourceLinkDefinition.getTargetLabel(), sourceLinkDefinition.getSourceEntityUid());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.sourceLinkDefinition = sourceLinkDefinition;
        this.propertiesMapping = propertiesMapping;
        this.selectorChoiceInputObjectTypeMapping = selectorChoiceInputObjectTypeMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public LinkDefinition getSourceRelationshipDefinition() {
        return sourceLinkDefinition;
    }

    @Override
    public String getRelationshipTargetLabel() {
        return sourceLinkDefinition.getTargetLabel();
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition propertiesValue = InputValueDefinition.newInputValueDefinition()
                .name(propertyFieldName)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name(sourceFieldName)
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
