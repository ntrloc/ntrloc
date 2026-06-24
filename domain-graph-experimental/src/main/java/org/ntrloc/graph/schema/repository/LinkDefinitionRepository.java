package org.ntrloc.graph.schema.repository;

import org.ntrloc.graph.schema.definition.LinkDefinition;

import java.util.Set;
import java.util.UUID;

public interface LinkDefinitionRepository {

    Set<LinkDefinition> getLinkDefinitions();
    LinkDefinition createLinkDefinition();
    void deleteLinkDefinition(UUID id);

}
