package org.ntrloc.graph.db.partition.security.repository;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class SecurityInitializer {

    private final JdbcClient jdbcClient;

    public SecurityInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        initUserTable();
        initGroupTable();
        initGroupMemberTable();
        initLocalCredentialsTable();
        initPersonalAccessTokenTable();
    }

    void initUserTable() {
        // is_superuser lives here (not on security_local_credentials) because it's an identity
        // attribute independent of how the principal authenticated — a superuser should bypass
        // marker authorization the same way whether they logged in locally, via LDAP, OAuth, or
        // the stand-in header, per the design doc's "superusers bypass all policy" principle.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_user (
                    id           UUID PRIMARY KEY DEFAULT uuidv7(),
                    external_id  TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL,
                    email        TEXT,
                    is_superuser BOOLEAN NOT NULL DEFAULT FALSE
                )
                """).update();
        jdbcClient.sql("ALTER TABLE security_user ADD COLUMN IF NOT EXISTS email TEXT").update();
    }

    void initGroupTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_group (
                    id   UUID PRIMARY KEY DEFAULT uuidv7(),
                    name TEXT NOT NULL UNIQUE
                )
                """).update();
    }

    void initGroupMemberTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_group_member (
                    user_id  UUID NOT NULL REFERENCES security_user(id)  ON DELETE CASCADE,
                    group_id UUID NOT NULL REFERENCES security_group(id) ON DELETE CASCADE,
                    PRIMARY KEY (user_id, group_id)
                )
                """).update();
    }

    void initLocalCredentialsTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_local_credentials (
                    user_id       UUID PRIMARY KEY REFERENCES security_user(id) ON DELETE CASCADE,
                    email         TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    role          TEXT NOT NULL CHECK (role IN ('ADMIN', 'USER')),
                    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    active        BOOLEAN NOT NULL DEFAULT TRUE
                )
                """).update();
    }

    void initPersonalAccessTokenTable() {
        // Only the SHA-256 hash of the token is ever stored — the raw token is shown exactly
        // once, at creation. Revocation is a hard delete, not a flag.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_personal_access_token (
                    id         UUID PRIMARY KEY DEFAULT uuidv7(),
                    user_id    UUID NOT NULL REFERENCES security_user(id) ON DELETE CASCADE,
                    token_hash TEXT NOT NULL UNIQUE,
                    name       TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    expires_at TIMESTAMPTZ
                )
                """).update();
    }
}
