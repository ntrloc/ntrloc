package org.ntrloc.graph.db.partition.process;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

// Deliberately separate from security_group (SecurityInitializer): a process-assignment group
// (who can pick up a User Task) is a different concept from a permission group (what a set of
// users can do to schema/graph data) even though both are "a group of users" -- coincidentally
// similar shape, unrelated lifecycle and ownership.
@Component
@DependsOn("securityInitializer")
public class ProcessGroupInitializer {

    private final JdbcClient jdbcClient;

    public ProcessGroupInitializer(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        initGroupTable();
        initGroupMemberTable();
    }

    void initGroupTable() {
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_group (
                    id   UUID PRIMARY KEY DEFAULT uuidv7(),
                    name TEXT NOT NULL UNIQUE
                )
                """).update();
    }

    void initGroupMemberTable() {
        // user_id references security_user directly -- individual identity is shared app-wide,
        // it's specifically the grouping mechanism that's kept separate from security_group.
        jdbcClient.sql("""
                CREATE TABLE IF NOT EXISTS process_group_member (
                    group_id UUID NOT NULL REFERENCES process_group(id) ON DELETE CASCADE,
                    user_id  UUID NOT NULL REFERENCES security_user(id) ON DELETE CASCADE,
                    PRIMARY KEY (group_id, user_id)
                )
                """).update();
    }
}
