package org.ntrloc.graph.db.partition.authorization.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@DependsOn("schemaInitializer")
public class AuthorizationInitializer {

    private final JdbcClient jdbcClient;

    public AuthorizationInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        initMarkerTable();
        initItemTypeMarkerTable();
        initGrantTable();
    }

    void initMarkerTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS authorization_marker (
                    id          UUID PRIMARY KEY DEFAULT uuidv7(),
                    name        TEXT NOT NULL UNIQUE,
                    description TEXT
                )
                """).update();
    }

    void initItemTypeMarkerTable() {
        // Static, admin-curated assignment table. This exact shape is expected to later
        // become "rule engine output" without structural change — no logic lives here.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS authorization_item_type_marker (
                    item_type_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    marker_id    UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
                    PRIMARY KEY (item_type_id, marker_id)
                )
                """).update();
    }

    void initGrantTable() {
        // Row-per-operation (not flags-per-row): keeps the door open for new primitives
        // via a CHECK-constraint edit rather than a schema migration, and avoids sparse
        // columns for primitives that don't apply to a given marker's kind.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS authorization_grant (
                    id             UUID PRIMARY KEY DEFAULT uuidv7(),
                    marker_id      UUID NOT NULL REFERENCES authorization_marker(id) ON DELETE CASCADE,
                    principal_type TEXT NOT NULL CHECK (principal_type IN ('USER', 'GROUP')),
                    principal_id   UUID NOT NULL,
                    operation      TEXT NOT NULL CHECK (operation IN (
                        'item:create','item:read','item:delete',
                        'property:read','property:write',
                        'link:create','link:read','link:delete',
                        'link_property:read','link_property:write',
                        'binary:download','security:override','marker:apply','marker:remove'
                    )),
                    UNIQUE (marker_id, principal_type, principal_id, operation)
                )
                """).update();
    }
}
