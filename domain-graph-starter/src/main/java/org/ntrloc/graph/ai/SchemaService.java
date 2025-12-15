package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.LinkDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
public class SchemaService {

    private SchemaManager schemaManager;

    public SchemaService(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @McpTool(description = "Retrieves all available item types")
    Set<ItemDefinition> getItemDefinitions() {
        return schemaManager.retrieveItemDefinitions();
    }

    @McpTool(description = "Retrieves all available link types")
    Set<LinkDefinition> getLinkDefinitions() {
        return schemaManager.retrieveLinkDefinitions();
    }

}
