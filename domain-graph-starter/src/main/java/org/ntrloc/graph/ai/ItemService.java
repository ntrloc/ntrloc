package org.ntrloc.graph.ai;

import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.mutation.MutationRequest;
import org.ntrloc.graph.db.language.mutation.MutationResponse;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ItemService {

    private static final Logger LOG = LoggerFactory.getLogger(ItemService.class);
    private ItemManager itemManager;

    public ItemService(ItemManager itemManager) {
        this.itemManager = itemManager;
        LOG.info("Item service initialized");
    }

    @McpTool(description = """
            Retrieves all items using a projection.
            
            This is a read-only operation, so it requires no permissions to execute.
            
            When retrieving items, consult with the available item types to determine how to construct the projection request.
            
            If a user requests specific properties for an item, only request those properties.  Otherwise, request all properties.
            If a user requests specific links for an item, only request those links.  If the user requests all links, request all links.  If the user does not request links, do not request them.
            
            If you don't need to filter items, you can leave the filter as null in the projection spec.
            If you don't need to return properties, you can set the properties to an empty list.
            If you don't need to return links, you can leave the links as null in the projection spec.
            
            Valid item properties, link properties, and item-link relationships can be discovered by retrieving item types and link types.
            It is generally not advisable to request properties that are not valid for a given item type or link type.
            
            Link definitions play an important role in item projects.  Given an item of type A, an item of type B, and link with (1) a source item type of A,
            (2) a target item type of B, (3) a source label "created" and (4) a target label "creator", item type A will have a link called "created" because it
            is the source item and "created" is the source label and item type B will have a link called "creator" because it is the target item and "created" is the
            target label.
            
            """)
    public List<ItemProjection> retrieveItems(@ToolParam(description = """
            A selectable item projection specification.
            """) SelectableItemProjectionSpec spec) {
        try {
            LOG.info("Retrieving items with spec {}", spec);
            var retProjection = itemManager.executeProjection(spec);
            LOG.info("Retrieved {}", retProjection);
            return retProjection;
        } catch (Exception e) {
            LOG.error("Error while retrieving items", e);
            throw new RuntimeException("Error while retrieving items", e);
        }
    }

    @McpTool(description = """
            Executes a mutation, which adds, updates, or deletes items.
            
            When working with ID selectors, note that when you are linking an item (A) to an existing item (B) that is NOT itself being created in the same
            mutation, you should use an ID selector with a "GLOBAL" scope and the unique ID of item B.  If you are creating an item (A) in a mutation and need to
            refer to it within another item (B) in the same operation, item mutation A should include a refId value and item mutation B should refer to it
            with an ID selector of LOCAL scope and an ID value mutation A's refID.
            """)
    public MutationResponse executeMutation(MutationRequest request) {
        try {
            LOG.info("Executing mutation {}", request);
            return itemManager.executeMutation(request);
        } catch (Exception e) {
            LOG.error("Error while executing mutation", e);
            throw new RuntimeException("Error while executing mutation", e);
        }
    }

}
