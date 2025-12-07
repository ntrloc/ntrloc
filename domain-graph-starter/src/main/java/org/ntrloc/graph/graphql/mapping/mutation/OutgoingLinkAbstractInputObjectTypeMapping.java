package org.ntrloc.graph.graphql.mapping.mutation;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

public abstract class OutgoingLinkAbstractInputObjectTypeMapping implements OutgoingLinkInputTypeMapping, InputObjectTypeProducer {

    protected LinkDefinition targetLinkDefinition;
    protected String graphQlTypeName;
    protected LinkPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    OutgoingLinkAbstractInputObjectTypeMapping(String typePattern, LinkDefinition targetLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format(typePattern, targetLinkDefinition.getSourceItemType(), targetLinkDefinition.getSourceLabel(), targetLinkDefinition.getTargetItemType());
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

}
