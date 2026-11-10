package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminSchemaView;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

@Component
public class SchemaTools {

    private final SchemaService schemaService;
    private final McpPrincipalResolverAdapter principalResolver;

    public SchemaTools(SchemaService schemaService, McpPrincipalResolverAdapter principalResolver) {
        this.schemaService = schemaService;
        this.principalResolver = principalResolver;
    }

    @McpTool(description = """
            Retrieves the full graph schema: item types, traits, and link types, and every
            property each one defines (name, description, type, cardinality, usage).

            Each item type/trait lists the links it can participate in; each link entry
            identifies its target item type(s) and its own properties.

            Consult this before constructing a projection or mutation request -- item type
            names, property names, and link names must exactly match what's returned here.
            """)
    public AdminSchemaView getSchema(McpSyncRequestContext requestContext) {
        // Still rejects an unresolvable caller (Part D) even though the admin schema isn't
        // filtered by identity -- unlike SchemaService.getSchema(principal), which
        // scopes its result to a non-admin caller's grants, getAdminSchema() here
        // is deliberately still the full, unfiltered view every MCP caller gets (explicitly
        // confirmed in scope by the user, see docs/ntrloc-principal-enforcement-plan.md, Part D):
        // an MCP agent needs to see the whole schema to construct valid projection/mutation
        // requests against item types it may not itself be able to read or write. Requiring a
        // real principal here is a floor against anonymous MCP access, not content filtering.
        principalResolver.requirePrincipal(requestContext);
        return schemaService.getAdminSchema();
    }
}
