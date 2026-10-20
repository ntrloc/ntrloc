package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.EntityManager;
import org.ntrloc.graph.mutation.MutationRequest;
import org.ntrloc.graph.mutation.MutationResponse;
import org.ntrloc.graph.mutation.MutationValidationException;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Collectors;

@Service
public class MutationService {

    private static final Logger LOG = LoggerFactory.getLogger(MutationService.class);

    // Goes through EntityManager, not MutationRequestProcessor directly -- see
    // MutationController's identical comment; EntityManager is the one entry point for both
    // reads and writes now.
    private final EntityManager entityManager;
    private final McpPrincipalResolver principalResolver;

    public MutationService(EntityManager entityManager, McpPrincipalResolver principalResolver) {
        this.entityManager = entityManager;
        this.principalResolver = principalResolver;
    }

    @McpTool(description = """
            Creates, updates, or deletes items and links in one atomic request.

            Consult the schema (getSchema) first: itemTypeName, property names, and link
            perspective names must exactly match what's returned there.

            Item creates: itemTypeName plus a properties map (name -> value). Supply a refId to
            reference the not-yet-persisted item from a link in the same request (see below).
            Item updates: itemId plus a properties map, which is a diff -- an absent key leaves
            that property unchanged, a null value clears it. Item deletes: itemId only.

            Link creates: firstItem/secondItem, each a perspective name plus an item reference,
            and an optional properties map. An item reference is either {"type":"EXISTING",
            "itemId":...} for an already-persisted item, or {"type":"NEW","refId":...} to point at
            an item being created earlier in this same request. There is no link type field --
            which link type applies is derived entirely from the two perspectives named. Link
            updates: linkId plus a properties diff. Link deletes: linkId only.

            Example: to create an item and link it to an existing item in one call, give the new
            item's create mutation a refId (e.g. "a"), then reference {"type":"NEW","refId":"a"}
            as one endpoint of the link create mutation.

            The whole request is validated before anything is written: if any item or link
            mutation is invalid, nothing is persisted and every validation error found is reported
            at once, not just the first.
            """)
    public MutationResponse executeMutation(@ToolParam(description = """
            The mutation request: a list of item mutations and a list of link mutations, applied
            together as one atomic transaction.
            """) MutationRequest request, McpSyncRequestContext requestContext) {
        try {
            NtrlocPrincipal principal = principalResolver.requirePrincipal(requestContext);
            LOG.info("Executing mutation {} as {}", request, principal.externalId());
            MutationResponse response = entityManager.mutate(request, principal);
            LOG.info("Mutation applied: {} item(s), {} link(s)", response.items().size(), response.links().size());
            return response;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (MutationValidationException e) {
            String detail = e.errors().stream()
                    .map(error -> error.path() + ": " + error.message())
                    .collect(Collectors.joining("; "));
            LOG.warn("Mutation request failed validation: {}", detail);
            throw e;
        } catch (Exception e) {
            LOG.error("Error while executing mutation", e);
            throw new RuntimeException("Error while executing mutation", e);
        }
    }
}
