package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.IdentifiedPropertyDefinition;
import org.ntrloc.graph.schema.definition.PropertyDefinition;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PropertyDefinitionRepository {

    IdentifiedPropertyDefinition create(PropertyDefinition propertyDefinition);
    Optional<IdentifiedPropertyDefinition> findById(UUID id);
    Optional<IdentifiedPropertyDefinition> findByName(String name);
    List<IdentifiedPropertyDefinition> findAll();
    IdentifiedPropertyDefinition updateName(UUID id, String newLabel);
    void delete(UUID id);

}
