package org.ntrloc.graph.db.partition.process;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

// Process-assignment groups (candidateGroups on a User Task) -- a distinct concept from
// security_group, see V1_0_0_1__baseline.sql's note on process_group. Individual user identity still
// comes from SecurityRepository/security_user; only the grouping is separate.
@Component
public class ProcessGroupRepository {

    public record ProcessGroupRow(UUID id, String name) {}

    private final JdbcClient jdbcClient;

    public ProcessGroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<ProcessGroupRow> listGroups() {
        return jdbcClient.sql("SELECT id, name FROM process_group ORDER BY name")
                .query((rs, n) -> new ProcessGroupRow(rs.getObject("id", UUID.class), rs.getString("name")))
                .list();
    }

    // Flowable's taskCandidateGroupIn() takes group *names*, not ids -- this is what
    // TaskAdminController uses to build that query for the current principal.
    public Set<String> getGroupNamesForUser(UUID userId) {
        return Set.copyOf(jdbcClient.sql("""
                SELECT g.name FROM process_group g
                JOIN process_group_member m ON m.group_id = g.id
                WHERE m.user_id = :userId
                """)
                .param("userId", userId)
                .query((rs, n) -> rs.getString("name"))
                .list());
    }

    public ProcessGroupRow createGroup(String name) {
        UUID id = jdbcClient.sql("INSERT INTO process_group (name) VALUES (:name) RETURNING id")
                .param("name", name)
                .query(UUID.class).single();
        return new ProcessGroupRow(id, name);
    }

    public void addUserToGroup(UUID userId, UUID groupId) {
        jdbcClient.sql("INSERT INTO process_group_member (group_id, user_id) VALUES (:groupId, :userId)")
                .param("groupId", groupId).param("userId", userId)
                .update();
    }
}
