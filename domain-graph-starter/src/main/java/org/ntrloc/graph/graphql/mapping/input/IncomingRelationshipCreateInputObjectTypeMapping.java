package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

/** Maps an entity's inbound relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipCreateInputObjectTypeMapping extends RelationshipCreateInputObjectTypeMapping implements IncomingRelationshipInputTypeMapping, InputObjectTypeProducer {

    private String graphQlTypeName;
    private RelationshipDefinition sourceRelationshipDefinition;
    private RelationshipPropertiesInputObjectTypeMapping propertiesMapping;
    private MatcherChoiceInputObjectTypeMapping matcherChoiceMapping;

    public IncomingRelationshipCreateInputObjectTypeMapping(RelationshipDefinition sourceRelationshipDefinition, RelationshipPropertiesInputObjectTypeMapping propertiesMapping, MatcherChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s %s %s Link Create Input", sourceRelationshipDefinition.getTargetEntity(), sourceRelationshipDefinition.getTargetLabel(), sourceRelationshipDefinition.getSourceEntity());
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
    public String getTargetLabel() {
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
