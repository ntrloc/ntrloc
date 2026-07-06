package org.ntrloc.graph.acl.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@DependsOn("schemaInitializer")
public class AclInitializer {

    private final JdbcClient jdbcClient;

    public AclInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        dropAllTables();
        initUserTable();
        initGroupTable();
        initGroupMemberTable();
        initMarkerTable();
        initItemTypeMarkerTable();
        initGrantTable();
    }

    void dropAllTables() {
        jdbcClient.sql("DROP TABLE IF EXISTS acl_grant CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS acl_item_type_marker CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS acl_group_member CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS acl_marker CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS acl_group CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS acl_user CASCADE").update();
    }

    void initUserTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS acl_user (
                    id           UUID PRIMARY KEY DEFAULT uuidv7(),
                    external_id  TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL
                )
                """).update();
    }

    void initGroupTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS acl_group (
                    id   UUID PRIMARY KEY DEFAULT uuidv7(),
                    name TEXT NOT NULL UNIQUE
                )
                """).update();
    }

    void initGroupMemberTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS acl_group_member (
                    user_id  UUID NOT NULL REFERENCES acl_user(id)  ON DELETE CASCADE,
                    group_id UUID NOT NULL REFERENCES acl_group(id) ON DELETE CASCADE,
                    PRIMARY KEY (user_id, group_id)
                )
                """).update();
    }

    void initMarkerTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS acl_marker (
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
                CREATE TABLE IF NOT EXISTS acl_item_type_marker (
                    item_type_id UUID NOT NULL REFERENCES schema_item(id) ON DELETE CASCADE,
                    marker_id    UUID NOT NULL REFERENCES acl_marker(id)  ON DELETE CASCADE,
                    PRIMARY KEY (item_type_id, marker_id)
                )
                """).update();
    }

    void initGrantTable() {
        // Row-per-operation (not flags-per-row): keeps the door open for new primitives
        // via a CHECK-constraint edit rather than a schema migration, and avoids sparse
        // columns for primitives that don't apply to a given marker's kind.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS acl_grant (
                    id             UUID PRIMARY KEY DEFAULT uuidv7(),
                    marker_id      UUID NOT NULL REFERENCES acl_marker(id) ON DELETE CASCADE,
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
