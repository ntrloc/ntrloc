package org.ntrloc.graph.graphql.mapping.mutation;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

public abstract class IncomingLinkAbstractInputObjectTypeMapping implements IncomingLinkInputTypeMapping, InputObjectTypeProducer {

    String propertyFieldName = "properties";

    protected String graphQlTypeName;
    protected LinkDefinition sourceLinkDefinition;
    protected LinkPropertiesInputObjectTypeMapping propertiesMapping;
    protected SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    public IncomingLinkAbstractInputObjectTypeMapping(String namePattern, LinkDefinition sourceLinkDefinition, LinkPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format(namePattern, sourceLinkDefinition.getTargetItemType(), sourceLinkDefinition.getTargetLabel(), sourceLinkDefinition.getSourceItemType());
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

}
