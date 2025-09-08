package org.ntrloc.graph.graphql.mapping.query;

import graphql.language.ObjectTypeDefinition;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ItemObjectTypesMapper {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(ItemObjectTypesMapper.class);

    private Map<String, ItemObjectTypeMapping> entityOutputMap = new HashMap<>();

    public Map<String, ObjectTypeDefinition> mapObjectTypes(Set<ItemDefinition> itemDefinitions, Set<LinkDefinition> linkDefinitions) {

        entityOutputMap = new HashMap<>();

        for (ItemDefinition definition : itemDefinitions) {
            ItemObjectTypeMapping mapping = new ItemObjectTypeMapping(definition);
            LOG.info("Parsed input type {} for entity {}", mapping.getGraphQlTypeName(), definition.getName());
            entityOutputMap.put(definition.getName(), mapping);
        }

        return entityOutputMap.values().stream()
                .flatMap(mapping -> mapping.getObjectTypeDefinitions().stream())
                .collect(Collectors.toMap(ObjectTypeDefinition::getName, inputObjectTypeDefinition -> inputObjectTypeDefinition, (existingValue, newValue) -> existingValue));

    }

    public Map<String, ItemObjectTypeMapping> getEntityOutputTypes() {
        return entityOutputMap;
    }

}
