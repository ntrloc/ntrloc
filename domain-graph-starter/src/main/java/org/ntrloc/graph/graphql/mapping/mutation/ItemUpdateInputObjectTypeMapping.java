package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.ItemUpdateMutation;
import org.ntrloc.graph.db.language.mutation.LinkMutation;
import org.ntrloc.graph.db.language.selectors.Selector;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.ntrloc.graph.graphql.mapping.selector.SelectorChoiceInputObjectTypeMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/* Maps an entity to a GraphQL input object that represents an update instruction. */
public class ItemUpdateInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemUpdateInputObjectTypeMapping.class);
    private static final String WHERE_FIELD_NAME = "where";
    private static final String PROPERTIES_FIELD_NAME = "properties";
    private static final String LINKS_FIELD_NAME = "links";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private ItemPropertiesInputObjectTypeMapping propertiesMapping;
    private ItemUpdateLinksInputObjectTypeMapping updateLinksInputTypeMapping;
    private SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping;

    public ItemUpdateInputObjectTypeMapping(ItemDefinition itemDefinition, ItemPropertiesInputObjectTypeMapping propertiesMapping, SelectorChoiceInputObjectTypeMapping selectorChoiceInputObjectTypeMapping) {
        String typeName = String.format("%s Update Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;
        this.propertiesMapping = propertiesMapping;
        this.selectorChoiceInputObjectTypeMapping = selectorChoiceInputObjectTypeMapping;
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
                .name(WHERE_FIELD_NAME)
                .type(new NonNullType(new TypeName(selectorChoiceInputObjectTypeMapping.getGraphQlTypeName())))
                .build();

        InputValueDefinition entityPropertiesInputValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name(PROPERTIES_FIELD_NAME)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        entityUpdateInputValues.addAll(List.of(referenceValueDefinition, entityPropertiesInputValueDefinition));

        if (updateLinksInputTypeMapping != null) {
            entityUpdateInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(LINKS_FIELD_NAME)
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

    public ItemUpdateMutation parseUpdateMutation(Map<String, Object> inputMap) {
        ItemUpdateMutation mutation = new ItemUpdateMutation();

        if (inputMap.containsKey(WHERE_FIELD_NAME)) {
            Selector selector = selectorChoiceInputObjectTypeMapping.parseSelector((Map<String, Object>) inputMap.get(WHERE_FIELD_NAME));
            mutation.setSelector(selector);
        } else {
            throw new IllegalArgumentException("Update mutation must contain a field named " + WHERE_FIELD_NAME);
        }

        if (inputMap.containsKey(PROPERTIES_FIELD_NAME)) {
            List<? extends Property> properties = propertiesMapping.mapProperties((Map<String, Object>) inputMap.get(PROPERTIES_FIELD_NAME));
            mutation.setProperties(properties);
        }

        if (inputMap.containsKey(LINKS_FIELD_NAME)) {
            List<Map<String, List<Map<String, Map<String, Object>>>>> linksField = (List)inputMap.get(LINKS_FIELD_NAME);
            List<LinkMutation> updateMutations = updateLinksInputTypeMapping.parseLinkMutations(linksField);
            mutation.setLinks(updateMutations);
        }
        return mutation;
    }
}
