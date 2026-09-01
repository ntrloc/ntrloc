package org.ntrloc.graph.db.partition.process;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// "Is this process / decision key still pointed at from somewhere in the schema?" -- the delete
// guard for ProcessAdminController / DecisionAdminController. Both a process id and a decision key
// are stored as plain strings (not FKs -- see the schema tables and authorization_marker_rule's
// own migration comment), so nothing in the DB stops a definition being deleted out from under a
// state machine or a marker rule; this scanner is what makes those deletes a hard "not in use"
// gate instead, matching how schema item-type / trait deletion already works.
//
// Read-only, and deliberately cross-partition (schema_* + authorization_*) via one JdbcClient:
// this is a short reference lookup for a delete check, not domain logic that belongs to either
// partition's repository.
@Component
public class WorkflowReferenceScanner {

    private final JdbcClient jdbcClient;

    WorkflowReferenceScanner(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    // Human-readable descriptions of everything currently referencing this process id. Empty means
    // "safe to delete".
    public List<String> processReferences(String processId) {
        List<String> refs = new ArrayList<>();
        jdbcClient.sql("""
                SELECT sm.name AS machine, s.name AS state,
                       CASE WHEN s.entry_process_id = :key THEN 'entry' ELSE 'exit' END AS role
                FROM schema_state s
                JOIN schema_state_machine sm ON sm.id = s.state_machine_id
                WHERE s.entry_process_id = :key OR s.exit_process_id = :key
                """)
                .param("key", processId)
                .query((rs, n) -> "state \"" + rs.getString("state") + "\" in state machine \""
                        + rs.getString("machine") + "\" (" + rs.getString("role") + " process)")
                .list().forEach(refs::add);
        jdbcClient.sql("""
                SELECT sm.name AS machine, t.name AS transition
                FROM schema_state_transition t
                JOIN schema_state fs ON fs.id = t.from_state_id
                JOIN schema_state_machine sm ON sm.id = fs.state_machine_id
                WHERE t.process_id = :key
                """)
                .param("key", processId)
                .query((rs, n) -> "transition \"" + rs.getString("transition") + "\" in state machine \""
                        + rs.getString("machine") + "\"")
                .list().forEach(refs::add);
        return refs;
    }

    // Human-readable descriptions of everything currently referencing this decision key. Empty
    // means "safe to delete".
    public List<String> decisionReferences(String decisionKey) {
        List<String> refs = new ArrayList<>();
        jdbcClient.sql("""
                SELECT sm.name AS machine, s.name AS state
                FROM schema_state s
                JOIN schema_state_machine sm ON sm.id = s.state_machine_id
                WHERE s.entry_marker_decision_key = :key
                """)
                .param("key", decisionKey)
                .query((rs, n) -> "state \"" + rs.getString("state") + "\" in state machine \""
                        + rs.getString("machine") + "\" (entry-marker decision)")
                .list().forEach(refs::add);
        jdbcClient.sql("SELECT name FROM authorization_marker_rule WHERE decision_key = :key")
                .param("key", decisionKey)
                .query((rs, n) -> "marker rule \"" + rs.getString("name") + "\"")
                .list().forEach(refs::add);
        return refs;
    }
}
