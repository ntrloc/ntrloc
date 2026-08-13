package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.ProjectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class ProjectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionService.class);

    // Read-only first pass: an MCP tool call doesn't carry a real per-caller identity through
    // Spring's reactive Authentication/ServerHttpRequest the way an ordinary REST request does
    // (PrincipalResolver needs both, resolved as controller-method parameters), so this runs as
    // a fixed superuser principal for now. Real per-caller MCP authorization -- distinguishing
    // which external caller is invoking the tool and enforcing their actual permissions -- is a
    // deferred follow-up, not yet designed.
    private static final NtrlocPrincipal MCP_PRINCIPAL =
            new ResolvedPrincipal(UUID.randomUUID(), "mcp-tool-caller", "MCP Tool Caller", null, Set.of(), true);

    private final EntityManager entityManager;

    public ProjectionService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @McpTool(description = """
            Retrieves items of a given type using a projection, with optional sorting/filtering.

            This is a read-only operation.

            Consult the schema (getSchema) first: itemTypeName must exactly match an item type
            name from the schema, and any sort field or filtered property must be one of that
            item type's own properties or one of the system fields (itemId, itemType, createdAt,
            updatedAt, visibilityState).

            A property nested inside an OBJECT-typed property is addressed by dot-separated path
            (e.g. "dimensions.widthCm"), one segment per level of nesting -- this applies to a
            predicate's propertyName, sortField, and facet field alike. The path must end on a
            leaf (scalar) property; naming an OBJECT property itself, without a leaf beneath it,
            is not a valid filter/sort/facet target.

            Results are paginated: at most 50 items are returned per call unless a larger limit is
            requested (capped at 500 regardless), and totalCount in the response is the count of
            all matching items, not just the ones returned -- use offset to page through the rest.
            """)
    public ProjectionResult projectItems(@ToolParam(description = """
            The collection projection specification: item type name, optional sort field/
            direction, optional filter/facets, optional offset/limit (default limit 50, max 500).
            """) CollectionProjectionSpec spec) {
        try {
            LOG.info("Projecting items with spec {}", spec);
            ProjectionResult result = entityManager.project(spec, "", MCP_PRINCIPAL);
            LOG.info("Projected {} item(s)", result.items().size());
            return result;
        } catch (Exception e) {
            LOG.error("Error while projecting items", e);
            throw new RuntimeException("Error while projecting items", e);
        }
    }
}
