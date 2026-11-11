package org.ntrloc.graph.db.partition.changelog;

import org.ntrloc.graph.events.ClusterEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Live's changelog (config_change_log): every change to users, groups, memberships and grants, appended by
 * the store that makes it, in the same transaction (docs/ntrloc-system-model-notes.md §13.6).
 *
 * Once that transaction commits, browsers are told ("accessChanged", admins only, once per transaction),
 * so a tab showing Access -- live, or a draft floating on live -- reads it again. Live doesn't know
 * whether any drafts exist; and a failure to tell browsers is logged, never thrown back into the change,
 * which has already committed.
 */
@Component
public class ChangeLog extends ChangeEntries {

    public static final String ACCESS_CHANGED = "accessChanged";

    private static final Logger LOG = LoggerFactory.getLogger(ChangeLog.class);

    private final ClusterEventBroadcaster events;

    public ChangeLog(JdbcClient jdbcClient, ClusterEventBroadcaster events) {
        super(jdbcClient, "config_change_log", null);
        this.events = events;
    }

    public void append(String kind, String elementKey, Collection<UUID> refs, Map<String, ?> before, Map<String, ?> after) {
        append(null, kind, elementKey, refs, before, after);
        announceAfterCommit();
    }

    private void announceAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            announce();
            return;
        }
        if (TransactionSynchronizationManager.hasResource(this)) return;
        TransactionSynchronizationManager.bindResource(this, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResource(ChangeLog.this);
                if (status == STATUS_COMMITTED) announce();
            }
        });
    }

    private void announce() {
        try {
            events.publish(ACCESS_CHANGED, "");
        } catch (RuntimeException e) {
            LOG.warn("Couldn't tell browsers that access changed", e);
        }
    }
}
