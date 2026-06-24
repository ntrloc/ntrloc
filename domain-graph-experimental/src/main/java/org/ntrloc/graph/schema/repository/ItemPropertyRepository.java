package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ItemPropertyRepository {

    void associate(UUID itemDefinitionId, UUID propertyDefinitionId);
    void dissociate(UUID itemDefinitionId, UUID propertyDefinitionId);
    List<IdentifiedPropertyDefinition> findByItemType(UUID itemDefinitionId);
    Map<UUID, List<IdentifiedPropertyDefinition>> mapAllByItemType();
    boolean exists(UUID itemDefinitionId, UUID propertyDefinitionId);

}
