package org.ntrloc.graph.db.partition.security.repository;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// Who each user is now, for every request (docs/ntrloc-drafts-plan.md step 2.3): name, email, effective
// groups and admin flag, or that they no longer exist. The principal resolver refreshes a signed-in
// principal from here, so a change to someone's groups, admin flag or existence applies on their next
// request rather than at their next sign-in -- at the cost of a map lookup, never a query per request.
//
// Filled per user as they're asked for. Any committed change to users, groups or memberships clears it on
// this node and, through a cluster topic, on the others (UserStore and UserGroupStore call changed()).
@Component
public class UserAccessCache {

    public record UserAccess(String displayName, String email, Set<UUID> groupIds, boolean isAdmin) {}

    private static final String TOPIC = "usersChanged";

    private final JdbcClient jdbcClient;
    private final ClusterService clusterService;
    private final ITopic<String> topic;
    private final Map<UUID, Optional<UserAccess>> users = new ConcurrentHashMap<>();
    // Bumped on every clear: a load that began before a clear isn't kept, since it may hold what the
    // clear was for.
    private final AtomicLong generation = new AtomicLong();

    public UserAccessCache(JdbcClient jdbcClient, ClusterService clusterService) {
        this.jdbcClient = jdbcClient;
        this.clusterService = clusterService;
        this.topic = clusterService.getTopic(TOPIC);
        topic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) clear();
        });
    }

    /** The user as they are now; empty if they no longer exist. */
    public Optional<UserAccess> of(UUID userId) {
        Optional<UserAccess> cached = users.get(userId);
        if (cached != null) return cached;
        long loadedAt = generation.get();
        Optional<UserAccess> loaded = load(userId);
        if (generation.get() == loadedAt) users.putIfAbsent(userId, loaded);
        return loaded;
    }

    /**
     * Moves on whenever this node learns that users, groups or memberships changed. Something built from them
     * when it stood lower is out of date (a draft's group view checks it, docs/ntrloc-drafts-plan.md step 2.4).
     */
    public long generation() {
        return generation.get();
    }

    /** Users, groups or memberships changed: clear once the caller's transaction commits, here and on every node. */
    void changed() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clearEverywhere();
            return;
        }
        if (TransactionSynchronizationManager.hasResource(this)) return;
        TransactionSynchronizationManager.bindResource(this, Boolean.TRUE);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResource(UserAccessCache.this);
                if (status == STATUS_COMMITTED) clearEverywhere();
            }
        });
    }

    private void clearEverywhere() {
        clear();
        topic.publish(UUID.randomUUID().toString());
    }

    private void clear() {
        generation.incrementAndGet();
        users.clear();
    }

    private record UserRow(String displayName, String email, boolean isAdmin) {}

    private Optional<UserAccess> load(UUID userId) {
        return jdbcClient.sql("SELECT display_name, email, is_admin FROM security_user WHERE id = :userId")
                .param("userId", userId)
                .query((rs, _) -> new UserRow(rs.getString("display_name"), rs.getString("email"), rs.getBoolean("is_admin")))
                .optional()
                .map(u -> new UserAccess(u.displayName(), u.email(), groupsOf(userId), u.isAdmin()));
    }

    // The user's groups and every group above them (as UserGroupStore.getUserGroupIdsForUser).
    private Set<UUID> groupsOf(UUID userId) {
        return Set.copyOf(jdbcClient.sql("""
                WITH RECURSIVE effective_groups(group_id) AS (
                    SELECT group_id FROM security_user_group_member WHERE user_id = :userId
                    UNION
                    SELECT g.parent_id FROM security_user_group g JOIN effective_groups eg ON g.id = eg.group_id
                    WHERE g.parent_id IS NOT NULL
                )
                SELECT group_id FROM effective_groups
                """).param("userId", userId).query(UUID.class).list());
    }
}
