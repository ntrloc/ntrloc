package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface LinkPropertyRepository {

    void associate(UUID linkDefinitionId, UUID propertyDefinitionId);
    void dissociate(UUID linkDefinitionId, UUID propertyDefinitionId);
    List<IdentifiedPropertyDefinition> findByLinkType(UUID linkDefinitionId);
    Map<UUID, List<IdentifiedPropertyDefinition>> mapAllByLinkType();
    boolean exists(UUID linkDefinitionId, UUID propertyDefinitionId);

}
