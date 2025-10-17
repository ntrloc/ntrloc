package org.ntrloc.graph.db.schema;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SchemaManager {

    Set<ItemDefinition> retrieveItemDefinitions();
    String createItemDefinition(ItemDefinition definition);
    void updateItemDefinition(ItemDefinition definition);
    Optional<ItemDefinition> retrieveItemDefinition(String name);
    void deleteItemDefinition(ItemDefinition definition);

    Set<LinkDefinition> retrieveLinkDefinitions();
    void createLinkDefinition(LinkDefinition definition);
    void updateLinkDefinition(LinkDefinition definition);
    Optional<LinkDefinition> retrieveLinkDefinition(String name);
    void deleteLinkDefinition(LinkDefinition definition);

    void addSchemaChangeReaction(SchemaChangeReaction reaction);

    String getItemTypeId(String itemTypeName);
    String getItemPropertyId(String itemTypeName, String propertyName);
    String getLinkPropertyId(String linkTypeName, String propertyName);
    Map<String, String> getItemPropertyNameToIdMap(String itemTypeID);
    String getPropertyName(String itemPropertyId);

}
