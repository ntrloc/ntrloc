package org.ntrloc.graph.db.partition.authorization.repository;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

// The Marker Store: marker definitions (authorization_marker). Grants made against markers are the
// Grant Store's; which items carry a marker is the register's (docs/ntrloc-drafts-plan.md step 1.1).
// Reads through MarkerStoreReader, writes through MarkerStoreWriter.
@Component
public class MarkerStore implements MarkerStoreReader, MarkerStoreWriter {

    private static final String COL_DESCRIPTION = "description";

    private final JdbcClient jdbcClient;

    public MarkerStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // scope_kind/scope_id are mandatory at creation -- a marker's scope is what makes its property/
    // link-perspective grants meaningful at all (see docs/ntrloc-marker-admin-ui-design-notes.md).
    // Deeper validation (does scope_id actually resolve to a real item/trait/perspective, are
    // referenced properties/perspectives actually within scope) is deliberately not implemented yet --
    // this pass is the schema/grant-shape refactor; scope *enforcement* is later, separately-scoped
    // work per the design doc's own sequencing.

    @Override
    public MarkerRow createMarker(String name, String description, String scopeKind, UUID scopeId) {
        UUID id = jdbcClient.sql("""
                INSERT INTO authorization_marker (name, description, scope_kind, scope_id)
                VALUES (:name, :description, :scopeKind, :scopeId) RETURNING id
                """)
                .param("name", name).param(COL_DESCRIPTION, description)
                .param("scopeKind", scopeKind).param("scopeId", scopeId)
                .query(UUID.class).single();
        return new MarkerRow(id, name, description, scopeKind, scopeId);
    }

    // Name/description only -- scope is immutable after creation (see the design doc: scope is
    // what a marker's grants are validated against, so changing it out from under existing grants
    // would silently invalidate them). The grants view keys everything by marker id, never by
    // name/description, so nothing to rebuild there.
    // Empty when there's no such marker -- e.g. someone else deleted it meanwhile.
    @Override
    public Optional<MarkerRow> updateMarker(UUID id, String name, String description) {
        return jdbcClient.sql("""
                UPDATE authorization_marker SET name = :name, description = :description
                WHERE id = :id RETURNING id, name, description, scope_kind, scope_id
                """)
                .param("id", id).param("name", name).param(COL_DESCRIPTION, description)
                .query((rs, _) -> new MarkerRow(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString(COL_DESCRIPTION),
                        rs.getString("scope_kind"), rs.getObject("scope_id", UUID.class)))
                .optional();
    }

    // Its grants must already be gone (GrantStoreWriter.deleteGrantsForMarker).
    @Override
    public void deleteMarker(UUID id) {
        jdbcClient.sql("DELETE FROM authorization_marker WHERE id = :id").param("id", id).update();
    }

    @Override
    public List<MarkerRow> getAllMarkers() {
        return jdbcClient.sql("SELECT id, name, description, scope_kind, scope_id FROM authorization_marker ORDER BY name")
                .query((rs, _) -> new MarkerRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("name"),
                        rs.getString(COL_DESCRIPTION),
                        rs.getString("scope_kind"),
                        rs.getObject("scope_id", UUID.class)))
                .list();
    }

    /** Each marker's name, for the given ids; unknown ids are absent. */
    @Override
    public Map<UUID, String> getMarkerNames(Set<UUID> markerIds) {
        if (markerIds.isEmpty()) return Map.of();
        Map<UUID, String> names = new HashMap<>();
        jdbcClient.sql("SELECT id, name FROM authorization_marker WHERE id IN (:ids)")
                .param("ids", markerIds)
                .query((RowCallbackHandler) rs -> names.put(rs.getObject("id", UUID.class), rs.getString("name")));
        return names;
    }
}
