package org.ntrloc.graph.graphql.mapping.input;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.EntityDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

/* Maps an entity to a GraphQL input object that represents an update instruction. */
public class EntityUpdateInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private EntityDefinition entityDefinition;
    private EntityPropertiesInputObjectTypeMapping propertiesMapping;
    private EntityUpdateLinksInputObjectTypeMapping updateLinksInputTypeMapping;
    private SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public EntityUpdateInputObjectTypeMapping(EntityDefinition entityDefinition, EntityPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s Update Input", entityDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.entityDefinition = entityDefinition;
        this.propertiesMapping = propertiesMapping;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public EntityDefinition getEntityDefinition() {
        return entityDefinition;
    }

    public void setLinkUpdateInputType(EntityUpdateLinksInputObjectTypeMapping updateLinksInputType) {
        updateLinksInputTypeMapping = updateLinksInputType;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> entityUpdateInputValues = new ArrayList<>();
        InputValueDefinition referenceValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name("where")
                .type(new NonNullType(new TypeName(matcherChoiceMapping.getGraphQlTypeName())))
                .build();

        InputValueDefinition entityPropertiesInputValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name("properties")
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        entityUpdateInputValues.addAll(List.of(referenceValueDefinition, entityPropertiesInputValueDefinition));

        if (updateLinksInputTypeMapping != null) {
            entityUpdateInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name("links")
                    .type(new ListType(new NonNullType(new TypeName(updateLinksInputTypeMapping.getGraphQlTypeName()))))
                    .build());
        }

        var entityUpdateType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(entityUpdateInputValues)
                .build();

        var retList = new ArrayList<InputObjectTypeDefinition>();
        retList.add(entityUpdateType);
        retList.addAll(propertiesMapping.getInputObjectTypeDefinitions());
        if (updateLinksInputTypeMapping != null) {
            retList.addAll(updateLinksInputTypeMapping.getInputObjectTypeDefinitions());
        }
        return retList;
    }
}
