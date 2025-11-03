package org.ntrloc.graph.db.schema;

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
    Optional<LinkDefinition> retrieveLinkDefinition(String linkDefinitionId);
    void deleteLinkDefinition(LinkDefinition definition);

    PropertyDefinition updatePropertyDefinition(String propertyUid, String name, String description);

    /* ------- Various other methods -------- */
    void addSchemaChangeReaction(SchemaChangeReaction reaction);

}
