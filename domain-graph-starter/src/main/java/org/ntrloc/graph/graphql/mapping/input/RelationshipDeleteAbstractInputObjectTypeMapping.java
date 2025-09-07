package org.ntrloc.graph.graphql.mapping.input;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

public class RelationshipDeleteAbstractInputObjectTypeMapping {

    protected String graphQlTypeName;
    protected RelationshipDefinition relationshipDefinition;
    protected SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public RelationshipDeleteAbstractInputObjectTypeMapping(String typeName, RelationshipDefinition relationshipDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.relationshipDefinition = relationshipDefinition;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

}
