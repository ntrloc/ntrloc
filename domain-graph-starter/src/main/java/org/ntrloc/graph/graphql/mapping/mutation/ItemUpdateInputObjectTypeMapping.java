package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;

import java.util.ArrayList;
import java.util.List;

/* Maps an entity to a GraphQL input object that represents an update instruction. */
public class ItemUpdateInputObjectTypeMapping implements InputObjectTypeProducer {

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private ItemPropertiesInputObjectTypeMapping propertiesMapping;
    private ItemUpdateLinksInputObjectTypeMapping updateLinksInputTypeMapping;
    private SelectorChoiceInputObjectTypeMapping matcherChoiceMapping;

    public ItemUpdateInputObjectTypeMapping(ItemDefinition itemDefinition, ItemPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping matcherChoiceMapping) {
        String typeName = String.format("%s Update Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;
        this.propertiesMapping = propertiesMapping;
        this.matcherChoiceMapping = matcherChoiceMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public ItemDefinition getEntityDefinition() {
        return itemDefinition;
    }

    public void setLinkUpdateInputType(ItemUpdateLinksInputObjectTypeMapping updateLinksInputType) {
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
