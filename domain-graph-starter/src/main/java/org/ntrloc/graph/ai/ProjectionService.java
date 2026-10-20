package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.ProjectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectionService {

    private static final Logger LOG = LoggerFactory.getLogger(ProjectionService.class);

    private final EntityManager entityManager;
    private final McpPrincipalResolver principalResolver;

    public ProjectionService(EntityManager entityManager, McpPrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    @McpTool(description = """
            Retrieves items of a given type using a projection, with optional sorting/filtering.

            This is a read-only operation.

            Consult the schema (getSchema) first: itemTypeName must exactly match an item type
            name from the schema, and any sort field or filtered property must be one of that
            item type's own properties or one of the system fields (itemId, itemType, createdDate,
            createdBy, updatedDate, updatedBy, visibilityState).

            A property nested inside a property group is addressed by dot-separated path
            (e.g. "dimensions.widthCm"), one segment per level of nesting -- this applies to a
            predicate's propertyName, sortField, and facet field alike. A trait's properties are
            addressed under the trait's name (e.g. "File.mimeType"). The path must end on a
            leaf (scalar) property; naming a group itself, without a leaf beneath it,
            is not a valid filter/sort/facet target.

            Results are paginated: at most 50 items are returned per call unless a larger limit is
            requested (capped at 500 regardless), and totalCount in the response is the count of
            all matching items, not just the ones returned -- use offset to page through the rest.
            """)
    public ProjectionResult projectItems(@ToolParam(description = """
            The collection projection specification: item type name, optional sort field/
            direction, optional filter/facets, optional offset/limit (default limit 50, max 500).
            """) CollectionProjectionSpec spec, McpSyncRequestContext requestContext) {
        try {
            NtrlocPrincipal principal = principalResolver.requirePrincipal(requestContext);
            LOG.info("Projecting items with spec {} as {}", spec, principal.externalId());
            ProjectionResult result = entityManager.project(spec, "", principal);
            LOG.info("Projected {} item(s)", result.items().size());
            return result;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            LOG.error("Error while projecting items", e);
            throw new RuntimeException("Error while projecting items", e);
        }
    }
}
