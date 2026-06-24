package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.IdentifiedItemDefinition;
import org.ntrloc.graph.schema.definition.ItemDefinition;

import java.util.Set;
import java.util.UUID;

public interface ItemDefinitionRepository {

    Set<IdentifiedItemDefinition> getItemDefinitions();

    IdentifiedItemDefinition createItemDefinition(ItemDefinition itemDefinition);

    void deleteItemDefinition(UUID id);

}
