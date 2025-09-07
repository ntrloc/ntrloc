package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.RelationshipDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.List;

/** Maps an entity's incoming relationship to an instruction to create a new instance of that relationship. */
public class IncomingRelationshipDeleteInputObjectTypeMapping implements IncomingRelationshipInputTypeMapping, InputObjectTypeProducer {

    private String graphQlTypeName;
    private RelationshipDefinition sourceRelationshipDefinition;
    private SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public IncomingRelationshipDeleteInputObjectTypeMapping(RelationshipDefinition sourceRelationshipDefinition, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s %s %s Link Delete Input", sourceRelationshipDefinition.getTargetEntity(), sourceRelationshipDefinition.getTargetLabel(), sourceRelationshipDefinition.getSourceEntity());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.sourceRelationshipDefinition = sourceRelationshipDefinition;
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
        InputValueDefinition matcherValue = InputValueDefinition.newInputValueDefinition()
                .name("source")
                .type(new TypeName(matcherChoiceMapping.getGraphQlTypeName()))
                .build();
        InputObjectTypeDefinition definition = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(List.of(matcherValue))
                .build();
        return List.of(definition);
    }
}
