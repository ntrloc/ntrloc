package org.ntrloc.graph.graphql.mapping.input;

import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingRelationshipUpdateInputTypeMapping implements OutgoingRelationshipInputTypeMapping {

    private String graphQlTypeName;
    private RelationshipDefinition targetRelationshipDefinition;
    private RelationshipPropertiesInputTypeMapping propertiesMapping;
    private MatcherChoiceInputTypeMapping matcherChoiceMapping;

    public OutgoingRelationshipUpdateInputTypeMapping(RelationshipDefinition targetRelationshipDefinition, RelationshipPropertiesInputTypeMapping propertiesMapping, MatcherChoiceInputTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s %s %s Link Update Input", targetRelationshipDefinition.getSourceEntity(), targetRelationshipDefinition.getSourceLabel(), targetRelationshipDefinition.getTargetEntity());
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
    public String getSourceLabel() {
        return targetRelationshipDefinition.getSourceLabel();
    }
}
