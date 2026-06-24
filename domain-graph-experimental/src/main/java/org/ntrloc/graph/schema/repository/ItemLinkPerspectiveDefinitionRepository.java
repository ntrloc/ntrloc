package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.IdentifiedItemLinkPerspectiveDefinition;
import org.ntrloc.graph.schema.definition.ItemLinkPerspectiveDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ItemLinkPerspectiveDefinitionRepository {

    IdentifiedItemLinkPerspectiveDefinition create(ItemLinkPerspectiveDefinition definition);
    Optional<IdentifiedItemLinkPerspectiveDefinition> findById(UUID id);
    List<IdentifiedItemLinkPerspectiveDefinition> findByLinkId(UUID linkId);
    IdentifiedItemLinkPerspectiveDefinition findInversePerspective(UUID linkId, UUID itemLinkPerspectiveId);
    IdentifiedItemLinkPerspectiveDefinition update(UUID id, ItemLinkPerspectiveDefinition definition);
    Map<UUID, List<IdentifiedItemLinkPerspectiveDefinition>> mapAllByItemType();
    void delete(UUID id);

}
