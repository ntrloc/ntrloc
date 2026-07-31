package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Service;

@Service
public class SchemaService {

    private final SchemaManager schemaManager;

    public SchemaService(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @McpTool(description = """
            Retrieves the full graph schema: item types, traits, and link types, and every
            property each one defines (name, description, type, cardinality, usage).

            Each item type/trait lists the links it can participate in; each link entry
            identifies its target item type(s) and its own properties.

            Consult this before constructing a projection or mutation request -- item type
            names, property names, and link names must exactly match what's returned here.
            """)
    public AdminSchemaView getSchema() {
        return schemaManager.getAdminSchema();
    }
}
