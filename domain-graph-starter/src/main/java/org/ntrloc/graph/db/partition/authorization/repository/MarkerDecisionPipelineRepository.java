package org.ntrloc.graph.db.partition.authorization.repository;

import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.PipelineSource;
import org.ntrloc.graph.access.WatchDerivation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// Persists marker decision pipelines, bound one-to-one with the item type they evaluate against --
// enforced at the database level by a unique index on item_type_id (see the baseline migration), not
// just a convention: an item type has at most one pipeline, never several, and a pipeline has no name
// of its own -- it's identified by the item type it belongs to, the same way a state machine or a
// trait is. The pipeline graph itself round-trips through JSONB via org.ntrloc.graph.access's own
// sealed-interface Jackson annotations -- this repository never inspects its structure, only stores
// and retrieves it. The real (only, for now) PipelineSource: LedgerRegisterCoordinatorImpl's
// prepare()-time evaluation reads through pipelinesWatching below; everything else here is the
// admin-authoring surface (raw JSON for now -- no visual editor yet).
//
// layout is the pipeline's own diagram (see PipelineLayout's own comment) -- stored alongside the
// pipeline it describes, in the same row and the same create/update call, but never read by
// pipelinesWatching: evaluation only ever needs the pipeline itself, never how it's drawn.
//
// Owns the watch-set invariant: createPipeline/updatePipeline always (re)derive and persist a
// pipeline's watches (see WatchDerivation) in the same call that writes its graph, so there's no
// path through this repository that can leave them out of sync -- callers never compute or pass
// watches themselves.
@Component
public class MarkerDecisionPipelineRepository implements PipelineSource {

    public record PipelineRow(UUID id, UUID itemTypeId, boolean enabled, Pipeline pipeline, PipelineLayout layout) {}

    private static final String PARAM_ITEM_TYPE_ID = "itemTypeId";
    private static final String COL_ITEM_TYPE_ID = "item_type_id";
    private static final String SELECT_ROW = """
            SELECT id, item_type_id, enabled, pipeline::text AS pipeline, layout::text AS layout
            FROM marker_decision_pipeline
            """;

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public MarkerDecisionPipelineRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    // Empty changedFactIds short-circuits before touching the database -- an entry with nothing
    // watchable in it (no property/state change at all) can never match any pipeline's watch row,
    // and an empty SQL IN-list is invalid syntax anyway.
    @Override
    public List<Pipeline> pipelinesWatching(UUID itemTypeId, Set<UUID> changedFactIds) {
        if (changedFactIds.isEmpty()) return List.of();
        return jdbcClient.sql("""
                SELECT p.pipeline::text AS pipeline
                FROM marker_decision_pipeline p
                WHERE p.item_type_id = :itemTypeId AND p.enabled = true
                  AND EXISTS (
                      SELECT 1 FROM marker_decision_pipeline_watch w
                      WHERE w.pipeline_id = p.id AND w.fact_id IN (:factIds)
                  )
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .param("factIds", changedFactIds)
                .query((rs, n) -> readPipeline(rs.getString("pipeline")))
                .list();
    }

    public PipelineRow createPipeline(UUID itemTypeId, Pipeline pipeline, PipelineLayout layout) {
        UUID id = jdbcClient.sql("""
                INSERT INTO marker_decision_pipeline (item_type_id, enabled, pipeline, layout)
                VALUES (:itemTypeId, true, :pipeline::jsonb, :layout::jsonb) RETURNING id
                """)
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .param("pipeline", writePipeline(pipeline))
                .param("layout", writeLayout(layout))
                .query(UUID.class).single();
        writeWatches(id, pipeline);
        return new PipelineRow(id, itemTypeId, true, pipeline, layout);
    }

    public PipelineRow updatePipeline(UUID id, Pipeline pipeline, boolean enabled, PipelineLayout layout) {
        jdbcClient.sql("""
                UPDATE marker_decision_pipeline SET pipeline = :pipeline::jsonb, enabled = :enabled, layout = :layout::jsonb
                WHERE id = :id
                """)
                .param("pipeline", writePipeline(pipeline))
                .param("enabled", enabled)
                .param("layout", writeLayout(layout))
                .param("id", id)
                .update();
        writeWatches(id, pipeline);
        UUID itemTypeId = jdbcClient.sql("SELECT item_type_id FROM marker_decision_pipeline WHERE id = :id")
                .param("id", id).query(UUID.class).single();
        return new PipelineRow(id, itemTypeId, enabled, pipeline, layout);
    }

    // Re-derived wholesale from the pipeline's own graph, not diffed against what's already stored
    // -- a pipeline's watch set is small and this never runs on a mutation's own critical path (only
    // on pipeline create/update, by an admin), so there's no cost worth optimizing away here.
    private void writeWatches(UUID pipelineId, Pipeline pipeline) {
        jdbcClient.sql("DELETE FROM marker_decision_pipeline_watch WHERE pipeline_id = :pipelineId")
                .param("pipelineId", pipelineId)
                .update();
        for (UUID factId : WatchDerivation.derive(pipeline)) {
            jdbcClient.sql("INSERT INTO marker_decision_pipeline_watch (pipeline_id, fact_id) VALUES (:pipelineId, :factId)")
                    .param("pipelineId", pipelineId)
                    .param("factId", factId)
                    .update();
        }
    }

    public void deletePipeline(UUID id) {
        jdbcClient.sql("DELETE FROM marker_decision_pipeline WHERE id = :id").param("id", id).update();
    }

    public List<PipelineRow> getAllPipelines() {
        return jdbcClient.sql(SELECT_ROW + " ORDER BY item_type_id")
                .query((rs, n) -> new PipelineRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getBoolean("enabled"),
                        readPipeline(rs.getString("pipeline")),
                        readLayout(rs.getString("layout"))))
                .list();
    }

    public Optional<PipelineRow> findById(UUID id) {
        return jdbcClient.sql(SELECT_ROW + " WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> new PipelineRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getBoolean("enabled"),
                        readPipeline(rs.getString("pipeline")),
                        readLayout(rs.getString("layout"))))
                .optional();
    }

    // The admin surface's primary lookup -- one item type has at most one pipeline (see this class's
    // own comment), so "the pipeline for this item type" is a direct lookup, not a filtered list.
    public Optional<PipelineRow> findByItemTypeId(UUID itemTypeId) {
        return jdbcClient.sql(SELECT_ROW + " WHERE item_type_id = :itemTypeId")
                .param(PARAM_ITEM_TYPE_ID, itemTypeId)
                .query((rs, n) -> new PipelineRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject(COL_ITEM_TYPE_ID, UUID.class),
                        rs.getBoolean("enabled"),
                        readPipeline(rs.getString("pipeline")),
                        readLayout(rs.getString("layout"))))
                .optional();
    }

    private String writePipeline(Pipeline pipeline) {
        try {
            return objectMapper.writeValueAsString(pipeline);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize pipeline " + pipeline.id(), e);
        }
    }

    private Pipeline readPipeline(String json) {
        try {
            return objectMapper.readValue(json, Pipeline.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored pipeline", e);
        }
    }

    private String writeLayout(PipelineLayout layout) {
        try {
            return objectMapper.writeValueAsString(layout);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize pipeline layout", e);
        }
    }

    private PipelineLayout readLayout(String json) {
        try {
            return objectMapper.readValue(json, PipelineLayout.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize stored pipeline layout", e);
        }
    }
}
