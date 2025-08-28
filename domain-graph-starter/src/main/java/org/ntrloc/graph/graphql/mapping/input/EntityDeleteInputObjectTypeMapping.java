package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.graphql.mapping.matcher.MatcherChoiceInputObjectTypeMapping;

import java.util.List;

/* Maps an entity to a GraphQL input object that represents a delete instruction. */
public class EntityDeleteInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;
    private MatcherChoiceInputObjectTypeMapping matcherChoiceMapping;

    public EntityDeleteInputObjectTypeMapping(EntityDefinition entityDefinition, MatcherChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s Delete Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.entityDefinition = entityDefinition;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        InputValueDefinition whereValue = InputValueDefinition.newInputValueDefinition()
                .name("where")
                .type(new NonNullType(new TypeName(matcherChoiceMapping.getGraphQlTypeName())))
                .build();
        return List.of(InputObjectTypeDefinition.newInputObjectDefinition()
                .name(String.format("%sDeleteInput", entityDefinition.getName()))
                .inputValueDefinition(whereValue)
                .build());
    }
}
