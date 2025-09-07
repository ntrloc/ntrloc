package org.ntrloc.graph.graphql.mapping.input;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

public class RelationshipDeleteAbstractInputObjectTypeMapping {

    protected String graphQlTypeName;
    protected LinkDefinition linkDefinition;
    protected SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public RelationshipDeleteAbstractInputObjectTypeMapping(String typeName, LinkDefinition linkDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.linkDefinition = linkDefinition;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
