package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.Field;
import graphql.language.FieldDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.graphql.mapping.ObjectTypeProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class QueryObjectTypeMapping implements ObjectTypeProducer {

    private static final Logger LOG = LoggerFactory.getLogger(QueryObjectTypeMapping.class);

    protected String graphQlTypeName;

    private final Map<String, ItemObjectTypeMapping> itemTypeMappings = new HashMap<>();

    public QueryObjectTypeMapping(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions) {
        this.graphQlTypeName = "Query";

        for (ItemDefinition itemDefinition : itemDefinitions) {
            Set<LinkDefinition> itemLinks = linkDefinitions.stream().filter(l -> l.getSourceEntityUid().equals(itemDefinition.getName()) || l.getTargetEntityUid().equals(itemDefinition.getName())).collect(java.util.stream.Collectors.toSet());
            itemTypeMappings.put(itemDefinition.getName(), new ItemObjectTypeMapping(itemDefinition, itemLinks));
        }

        // now that all the item types have been mapped, register them with each other
        for (ItemObjectTypeMapping itemTypeMapping : itemTypeMappings.values()) {
            itemTypeMapping.registerItemTypeMappingsForLinks(itemTypeMappings);
        }
    }

    @Override
    public List<ObjectTypeDefinition> getObjectTypeDefinitions() {
        List<ObjectTypeDefinition> retList = new ArrayList<>();

        List<FieldDefinition> fieldDefinitions = new ArrayList<>();
        for (ItemObjectTypeMapping itemTypeMapping : itemTypeMappings.values()) {
            retList.addAll(itemTypeMapping.getObjectTypeDefinitions());

            FieldDefinition definition = FieldDefinition.newFieldDefinition()
                    .name(itemTypeMapping.getItemDefinition().getName())
                    .type(new NonNullType(new ListType(new NonNullType(new TypeName(itemTypeMapping.getGraphQlTypeName())))))
                    .build();
            fieldDefinitions.add(definition);
        }

        ObjectTypeDefinition queryDefinition = ObjectTypeDefinition.newObjectTypeDefinition()
                .name(graphQlTypeName)
                .fieldDefinitions(fieldDefinitions)
                .build();

        retList.add(queryDefinition);

        return retList;
    }

    public SelectableItemProjectionSpec parseQueryField(Field field) {
        LOG.info("parsing query field {}", field);
        String itemName = field.getName();
        ItemObjectTypeMapping itemTypeMapping = itemTypeMappings.get(itemName);
        return itemTypeMapping.parseSelectableQueryField(field);
    }

}
