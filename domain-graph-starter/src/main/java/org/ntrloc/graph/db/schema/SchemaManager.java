package org.ntrloc.graph.db.schema;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public interface SchemaManager {

    /* ------- Items and item properties -------- */
    ItemDefinition createItemDefinition(ItemDefinition definition);
    Set<ItemDefinition> retrieveItemDefinitions();
    Optional<ItemDefinition> retrieveItemDefinition(String name);
    void updateItemDefinition(ItemDefinition definition);
    void deleteItemDefinition(ItemDefinition definition);
    PropertyDefinition createItemPropertyDefinition(String itemDefinitionId, PropertyDefinition definition);

    /* ------- Links and link properties -------- */
    Set<LinkDefinition> retrieveLinkDefinitions();
    void createLinkDefinition(LinkDefinition definition);
    void updateLinkDefinition(LinkDefinition definition);
    Optional<LinkDefinition> retrieveLinkDefinition(String name);
    void deleteLinkDefinition(LinkDefinition definition);

    PropertyDefinition updatePropertyDefinition(String propertyUid, String name, String description);

    /* ------- Various other methods -------- */
    void addSchemaChangeReaction(SchemaChangeReaction reaction);

    String getItemTypeName(String itemTypeId);
    String getItemTypeId(String itemTypeName);
    String getItemPropertyId(String itemTypeName, String propertyName);
    Map<String, String> getItemPropertyNameToIdMap(String itemTypeID);
    Map<String, PropertyDefinition> getItemPropertyDefinitionsById(String itemTypeID);

    String getLinkTypeName(String linkTypeId);
    String getLinkTypeId(String linkTypeName);
    String getLinkPropertyId(String linkTypeName, String propertyName);
    Map<String, String> getLinkPropertyNameToIdMap(String itemTypeID);
    Map<String, PropertyDefinition> getLinkPropertyDefinitionsById(String linkTypeID);

}
