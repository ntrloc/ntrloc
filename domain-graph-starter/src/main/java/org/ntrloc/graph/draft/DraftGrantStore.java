package org.ntrloc.graph.draft;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStoreReader;
import org.ntrloc.graph.db.partition.authorization.repository.GrantsView;
import org.ntrloc.graph.db.partition.authorization.repository.Permission;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Drafts' grants (docs/ntrloc-system-model-notes.md §7.2.1): each draft's overrides (table
// draft_grant_override), and each draft's GrantsView -- live's rows with the overrides applied. Written
// only by DraftAccessService.
@Component
public class DraftGrantStore {

    private static final String DRAFT_GRANTS_CHANGED_TOPIC = "draftGrantsChanged";
    private static final String PARAM_DRAFT_ID = "draftId";

    // The same permission: the descriptor's columns, NULL matching NULL.
    private static final String SAME_PERMISSION = """
            draft_id = :draftId AND principal_type = :principalType AND principal_id = :principalId AND kind = :kind
              AND item_type_id IS NOT DISTINCT FROM CAST(:itemTypeId AS uuid) AND marker_id IS NOT DISTINCT FROM CAST(:markerId AS uuid)
              AND area IS NOT DISTINCT FROM CAST(:area AS text) AND target_id IS NOT DISTINCT FROM CAST(:targetId AS uuid)
              AND op IS NOT DISTINCT FROM CAST(:op AS text)
            """;

    // A draft's view, and the live view it was built from: a new live view means a rebuild.
    private record CachedView(GrantsView live, GrantsView draft) {}

    private final JdbcClient jdbcClient;
    private final GrantStoreReader liveGrants;
    private final ITopic<String> draftGrantsChangedTopic;
    private final Map<UUID, CachedView> views = new ConcurrentHashMap<>();

    public DraftGrantStore(JdbcClient jdbcClient, GrantStoreReader liveGrants, ClusterService clusterService) {
        this.jdbcClient = jdbcClient;
        this.liveGrants = liveGrants;
        this.draftGrantsChangedTopic = clusterService.getTopic(DRAFT_GRANTS_CHANGED_TOPIC);
        draftGrantsChangedTopic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) {
                views.remove(UUID.fromString(message.getMessageObject()));
            }
        });
    }

    /** The draft's grants, as GrantsView answers them: live's, with the draft's overrides applied. */
    public GrantsView getGrantsView(UUID draftId) {
        GrantsView live = liveGrants.getGrantsView();
        // compute() holds the draft's entry while building, so an eviction after a commit can't be
        // overtaken by a build that read the overrides before it.
        return views.compute(draftId, (id, cached) -> cached != null && cached.live() == live
                ? cached
                : new CachedView(live, new GrantsView(DraftGrantRows.apply(live.rows(), overrides(id))))).draft();
    }

    public List<DraftGrantOverride> overrides(UUID draftId) {
        return jdbcClient.sql("""
                SELECT principal_type, principal_id, kind, item_type_id, marker_id, area, target_id, op, value, base_value
                FROM draft_grant_override WHERE draft_id = :draftId
                """)
                .param(PARAM_DRAFT_ID, draftId)
                .query((rs, _) -> new DraftGrantOverride(rs.getString("principal_type"), rs.getObject("principal_id", UUID.class),
                        new Permission(rs.getString("kind"), rs.getObject("item_type_id", UUID.class), rs.getObject("marker_id", UUID.class),
                                rs.getString("area"), rs.getObject("target_id", UUID.class), rs.getString("op")),
                        rs.getBoolean("value"), rs.getBoolean("base_value")))
                .list();
    }

    /**
     * Keeps the draft open until the caller's transaction ends: applying or discarding it waits. False
     * when it's no longer open.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdOpen(UUID draftId) {
        return jdbcClient.sql("SELECT 1 FROM draft WHERE id = :draftId AND status = 'OPEN' FOR SHARE")
                .param(PARAM_DRAFT_ID, draftId).query((rs, _) -> 1).optional().isPresent();
    }

    boolean hasCachedView(UUID draftId) {
        return views.containsKey(draftId);
    }

    /** The draft has closed: its view goes, on every node, once the caller's transaction commits. */
    public void forget(UUID draftId) {
        draftGrantsChanged(draftId);
    }

    /**
     * One permission on or off in the draft. Set to live's value, the draft no longer changes it (its
     * override goes); otherwise the override records the value and live's value now as its base.
     */
    public void setPermission(UUID draftId, String principalType, UUID principalId, Permission permission, boolean value) {
        boolean live = liveGrants.getPermissions(principalType, principalId).contains(permission);
        bind(jdbcClient.sql("DELETE FROM draft_grant_override WHERE " + SAME_PERMISSION), draftId, principalType, principalId, permission).update();
        if (value != live) {
            bind(jdbcClient.sql("""
                    INSERT INTO draft_grant_override
                        (draft_id, principal_type, principal_id, kind, item_type_id, marker_id, area, target_id, op, value, base_value)
                    VALUES (:draftId, :principalType, :principalId, :kind, :itemTypeId, :markerId, :area, :targetId, :op, :value, :baseValue)
                    """), draftId, principalType, principalId, permission)
                    .param("value", value).param("baseValue", live)
                    .update();
        }
        draftGrantsChanged(draftId);
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec sql, UUID draftId, String principalType, UUID principalId, Permission p) {
        return sql.param(PARAM_DRAFT_ID, draftId).param("principalType", principalType).param("principalId", principalId)
                .param("kind", p.kind()).param("itemTypeId", p.itemTypeId()).param("markerId", p.markerId())
                .param("area", p.area()).param("targetId", p.targetId()).param("op", p.op());
    }

    // Once the caller's transaction commits: drop this node's view of the draft, then tell the other nodes.
    private void draftGrantsChanged(UUID draftId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishDraftGrantsChanged(draftId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishDraftGrantsChanged(draftId);
            }
        });
    }

    private void publishDraftGrantsChanged(UUID draftId) {
        views.remove(draftId);
        draftGrantsChangedTopic.publish(draftId.toString());
    }
}
