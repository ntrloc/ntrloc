package org.ntrloc.graph.graphql.mapping.mutation;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.TypeName;
import org.apache.commons.text.CaseUtils;
import org.ntrloc.graph.db.language.Property;
import org.ntrloc.graph.db.language.mutation.ItemCreateMutation;
import org.ntrloc.graph.db.language.mutation.LinkCreateMutation;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.graphql.mapping.InputObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/* Maps an entity to a GraphQL input object that represents a create instruction. */
public class ItemCreateInputObjectTypeMapping implements InputObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(ItemCreateInputObjectTypeMapping.class);

    private static final String LINKS_FIELD_NAME = "links";
    private static final String REF_FIELD_NAME = "ref";
    private static final String PROPERTIES_FIELD_NAME = "properties";

    private String graphQlTypeName;
    private ItemDefinition itemDefinition;
    private ItemPropertiesInputObjectTypeMapping propertiesMapping;
    private ItemCreateLinksInputObjectTypeMapping linkCreateInputType;

    public ItemCreateInputObjectTypeMapping(ItemDefinition itemDefinition, ItemPropertiesInputObjectTypeMapping propertiesMapping) {
        String typeName = String.format("%s Create Input", itemDefinition.getName());
        this.graphQlTypeName = CaseUtils.toCamelCase(typeName, true, '_', '-');
        this.itemDefinition = itemDefinition;
        this.propertiesMapping = propertiesMapping;
    }

    public String getGraphQlTypeName() {
        return graphQlTypeName;
    }

    public ItemDefinition getEntityDefinition() {
        return itemDefinition;
    }

    public void setLinkCreateInputType(ItemCreateLinksInputObjectTypeMapping linkCreateInputType) {
        this.linkCreateInputType = linkCreateInputType;
    }

    @Override
    public List<InputObjectTypeDefinition> getInputObjectTypeDefinitions() {
        List<InputValueDefinition> entityCreateInputValues = new ArrayList<>();
        InputValueDefinition referenceValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name(REF_FIELD_NAME)
                .type(new TypeName("String"))
                .build();

        InputValueDefinition entityPropertiesInputValueDefinition = InputValueDefinition.newInputValueDefinition()
                .name(PROPERTIES_FIELD_NAME)
                .type(new TypeName(propertiesMapping.getGraphQlTypeName()))
                .build();

        entityCreateInputValues.addAll(List.of(referenceValueDefinition, entityPropertiesInputValueDefinition));

        if (linkCreateInputType != null) {
            entityCreateInputValues.add(InputValueDefinition.newInputValueDefinition()
                    .name(LINKS_FIELD_NAME)
                    .type(new TypeName(linkCreateInputType.getGraphQlTypeName()))
                    .build());
        }

        var entityCreateType = InputObjectTypeDefinition.newInputObjectDefinition()
                .name(graphQlTypeName)
                .inputValueDefinitions(entityCreateInputValues)
                .build();

        var retList = new ArrayList<InputObjectTypeDefinition>();
        retList.add(entityCreateType);
        retList.addAll(propertiesMapping.getInputObjectTypeDefinitions());
        if (linkCreateInputType != null) {
            retList.addAll(linkCreateInputType.getInputObjectTypeDefinitions());
        }
        return retList;
    }

    public ItemCreateMutation parseCreateMutation(Map<String, Object> inputMap) {
        ItemCreateMutation mutation = new ItemCreateMutation();

        if (inputMap.containsKey(REF_FIELD_NAME)) {
            mutation.setRefId((String)inputMap.get(REF_FIELD_NAME));
        }
        if (inputMap.containsKey(PROPERTIES_FIELD_NAME)) {
            List<? extends Property> properties = propertiesMapping.mapProperties((Map<String, Object>) inputMap.get(PROPERTIES_FIELD_NAME));
            mutation.setProperties(properties);
        }
        if (inputMap.containsKey(LINKS_FIELD_NAME)) {
            LOG.info("Mapping links");
            Map<String, List<Map<String, Object>>> linksField = (Map)inputMap.get(LINKS_FIELD_NAME);
            List<LinkCreateMutation> createMutations = linkCreateInputType.parseLinkCreateMutations(linksField);
            mutation.setLinks(createMutations);
        }
        return mutation;
    }

}
