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
        dropAllTables();
        initUserTable();
        initGroupTable();
        initGroupMemberTable();
    }

    void dropAllTables() {
        jdbcClient.sql("DROP TABLE IF EXISTS security_group_member CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS security_group CASCADE").update();
        jdbcClient.sql("DROP TABLE IF EXISTS security_user CASCADE").update();
    }

    void initUserTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS security_user (
                    id           UUID PRIMARY KEY DEFAULT uuidv7(),
                    external_id  TEXT NOT NULL UNIQUE,
                    display_name TEXT NOT NULL
                )
                """).update();
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
}
