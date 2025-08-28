package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.InputTypeProducer;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputTypeMapping;

import java.util.List;

/** Maps an entity's outbound relationship to an instruction to create a new instance of that relationship. */
public class OutgoingRelationshipCreateInputTypeMapping implements OutgoingRelationshipInputTypeMapping, InputTypeProducer {

    private String graphQlTypeName;
    private RelationshipDefinition targetRelationshipDefinition;
    private RelationshipPropertiesInputTypeMapping propertiesMapping;
    private MatcherChoiceInputTypeMapping matcherChoiceMapping;

    public OutgoingRelationshipCreateInputTypeMapping(RelationshipDefinition targetRelationshipDefinition, RelationshipPropertiesInputTypeMapping propertiesMapping, MatcherChoiceInputTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s %s %s Link Create Input", targetRelationshipDefinition.getSourceEntity(), targetRelationshipDefinition.getSourceLabel(), targetRelationshipDefinition.getTargetEntity());
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
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                        .name(graphQlTypeName)
                        .inputValueDefinitions(List.of(propertiesValue, matcherValue))
                        .build());
    }
}
