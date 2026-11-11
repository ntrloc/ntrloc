package org.ntrloc.graph.db.partition.changelog;

import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;

// How long the live changelog keeps entries (docs/ntrloc-system-model-notes.md §13.6), set from the
// Operations screen; with nothing set, it keeps everything. Pruning runs hourly on the cluster's leader.
//
// It never removes an entry an open draft may still need: anything after the earliest open draft's
// starting position. That makes this the one live-side job that reads the draft table -- a maintenance
// job, not a live write, so a fault here can only keep too much or prune what a draft wanted; it can't
// hold up or break a change to live (§13.1).
@Component
public class ChangeLogRetention {

    static final String RETENTION_DAYS = "changelog.retention-days";

    private final JdbcClient jdbcClient;
    private final ClusterService clusterService;

    public ChangeLogRetention(JdbcClient jdbcClient, ClusterService clusterService) {
        this.jdbcClient = jdbcClient;
        this.clusterService = clusterService;
    }

    /** Days to keep entries; empty: keep everything. */
    public Optional<Integer> retentionDays() {
        return jdbcClient.sql("SELECT value FROM operations_setting WHERE name = :name").param("name", RETENTION_DAYS)
                .query(String.class).optional().map(Integer::valueOf);
    }

    /** `days` null keeps everything. */
    public void setRetentionDays(Integer days) {
        if (days == null) {
            jdbcClient.sql("DELETE FROM operations_setting WHERE name = :name").param("name", RETENTION_DAYS).update();
            return;
        }
        if (days < 1) throw new IllegalArgumentException("Retention is at least one day");
        jdbcClient.sql("""
                INSERT INTO operations_setting (name, value) VALUES (:name, :value)
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value
                """).param("name", RETENTION_DAYS).param("value", days.toString()).update();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    void pruneOnLeader() {
        if (clusterService.isLeaderNode()) prune();
    }

    /** Removes entries older than the retention period that no open draft needs; returns how many. */
    public int prune() {
        return retentionDays().map(days -> jdbcClient.sql("""
                DELETE FROM config_change_log
                WHERE at < now() - make_interval(days => :days)
                  AND seq <= COALESCE((SELECT MIN(changelog_position) FROM draft WHERE status = 'OPEN'), 9223372036854775807)
                """).param("days", days).update()).orElse(0);
    }
}
