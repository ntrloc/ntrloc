package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.security.repository.UserAccessCache;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
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

// Drafts' groups and memberships (docs/ntrloc-drafts-plan.md step 2.4): each draft's changes (tables
// draft_group_change, draft_membership_change) and its DraftGroups view, built from live's groups as they
// are now. Written only by DraftUserService; other draft code reads through DraftGroupStoreReader.
@Component
public class DraftGroupStore implements DraftGroupStoreReader {

    private static final String GROUPS_CHANGED_TOPIC = "groupsChanged";
    private static final String PARAM_DRAFT_ID = "draftId";
    private static final String PARAM_GROUP_ID = "groupId";

    // A draft's view, and the live generation it was built at: a later one means a rebuild.
    private record CachedView(long liveGeneration, DraftGroups draft) {}

    private final JdbcClient jdbcClient;
    private final UserGroupStore liveGroups;
    private final UserAccessCache liveUsers;
    private final DraftTopics.DraftTopic<String> groupsChangedTopic;
    private final Map<UUID, CachedView> views = new ConcurrentHashMap<>();

    public DraftGroupStore(JdbcClient jdbcClient, UserGroupStore liveGroups, UserAccessCache liveUsers, DraftTopics topics) {
        this.jdbcClient = jdbcClient;
        this.liveGroups = liveGroups;
        this.liveUsers = liveUsers;
        this.groupsChangedTopic = topics.topic(GROUPS_CHANGED_TOPIC, draftId -> views.remove(UUID.fromString(draftId)));
    }

    @Override
    public DraftGroups view(UUID draftId) {
        // Read before building: a live change committed during the build leaves the view a generation
        // behind, so the next read builds it again.
        long generation = liveUsers.generation();
        return views.compute(draftId, (id, cached) -> cached != null && cached.liveGeneration() == generation
                ? cached
                : new CachedView(generation, build(id))).draft();
    }

    /** As view, built afresh and not kept: for apply, whose live writes a view mustn't be cached with. */
    public DraftGroups build(UUID draftId) {
        return new DraftGroups(liveGroups.listUserGroups(), liveGroups.listMemberships(), groupChanges(draftId), membershipChanges(draftId));
    }

    @Override
    public List<DraftGroupChange> groupChanges(UUID draftId) {
        return jdbcClient.sql("""
                SELECT group_id, created, deleted, name, base_name, parent_id, base_parent_id
                FROM draft_group_change WHERE draft_id = :draftId
                """)
                .param(PARAM_DRAFT_ID, draftId)
                .query((rs, _) -> new DraftGroupChange(rs.getObject("group_id", UUID.class), rs.getBoolean("created"), rs.getBoolean("deleted"),
                        rs.getString("name"), rs.getString("base_name"), rs.getObject("parent_id", UUID.class), rs.getObject("base_parent_id", UUID.class)))
                .list();
    }

    @Override
    public List<DraftMembershipChange> membershipChanges(UUID draftId) {
        return jdbcClient.sql("SELECT user_id, group_id, member, base_member FROM draft_membership_change WHERE draft_id = :draftId")
                .param(PARAM_DRAFT_ID, draftId)
                .query((rs, _) -> new DraftMembershipChange(rs.getObject("user_id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getBoolean("member"), rs.getBoolean("base_member")))
                .list();
    }

    /** As DraftGrantStore.holdOpen: applying or discarding the draft waits for the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean holdOpen(UUID draftId) {
        return jdbcClient.sql("SELECT 1 FROM draft WHERE id = :draftId AND status = 'OPEN' FOR SHARE")
                .param(PARAM_DRAFT_ID, draftId).query((rs, _) -> 1).optional().isPresent();
    }

    /** A group the draft makes; its id, which it keeps when the draft is applied. */
    public UUID create(UUID draftId, String name, UUID parentId) {
        UUID id = jdbcClient.sql("""
                INSERT INTO draft_group_change (draft_id, group_id, created, name, parent_id)
                VALUES (:draftId, uuidv7(), TRUE, :name, :parentId) RETURNING group_id
                """)
                .param(PARAM_DRAFT_ID, draftId).param("name", name).param("parentId", parentId).query(UUID.class).single();
        changed(draftId);
        return id;
    }

    /** The group's name in the draft; `liveName` is live's now, or null for a group the draft makes. */
    public void setName(UUID draftId, UUID groupId, String name, String liveName) {
        setField(draftId, groupId, "name", "base_name", "text", name.equals(liveName) ? null : name, liveName);
    }

    /** The group's parent in the draft; `liveParentId` is live's now, or null for a group the draft makes. */
    public void setParent(UUID draftId, UUID groupId, UUID parentId, UUID liveParentId) {
        setField(draftId, groupId, "parent_id", "base_parent_id", "uuid", parentId.equals(liveParentId) ? null : parentId, liveParentId);
    }

    // A group the draft makes takes the value as it is. A live group: the value and live's as its base, or,
    // set back to live's, neither (and a row left with no change goes).
    private void setField(UUID draftId, UUID groupId, String column, String baseColumn, String type, Object value, Object base) {
        int updated = jdbcClient.sql("UPDATE draft_group_change SET " + column + " = CAST(:value AS " + type + ") WHERE draft_id = :draftId AND group_id = :groupId AND created")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).param("value", value).update();
        if (updated == 0) {
            jdbcClient.sql("INSERT INTO draft_group_change (draft_id, group_id, " + column + ", " + baseColumn + ") VALUES (:draftId, :groupId, CAST(:value AS " + type + "), CAST(:base AS " + type + "))"
                            + " ON CONFLICT (draft_id, group_id) DO UPDATE SET " + column + " = EXCLUDED." + column + ", " + baseColumn + " = EXCLUDED." + baseColumn)
                    .param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId)
                    .param("value", value).param("base", value == null ? null : base).update();
            removeIfUnchanged(draftId, groupId);
        }
        changed(draftId);
    }

    /** The draft removes the group: a live one is marked deleted; one the draft makes goes, with its memberships. */
    public void delete(UUID draftId, UUID groupId) {
        int removed = jdbcClient.sql("DELETE FROM draft_group_change WHERE draft_id = :draftId AND group_id = :groupId AND created")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).update();
        if (removed > 0) {
            jdbcClient.sql("DELETE FROM draft_membership_change WHERE draft_id = :draftId AND group_id = :groupId")
                    .param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).update();
        } else {
            jdbcClient.sql("""
                    INSERT INTO draft_group_change (draft_id, group_id, deleted) VALUES (:draftId, :groupId, TRUE)
                    ON CONFLICT (draft_id, group_id) DO UPDATE SET deleted = TRUE
                    """).param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).update();
        }
        changed(draftId);
    }

    /** A live group the draft deleted is back, with whatever else the draft changed about it. */
    public void restore(UUID draftId, UUID groupId) {
        jdbcClient.sql("UPDATE draft_group_change SET deleted = FALSE WHERE draft_id = :draftId AND group_id = :groupId")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).update();
        removeIfUnchanged(draftId, groupId);
        changed(draftId);
    }

    /** Whether the user is in the group in the draft; set to live's value, the draft no longer changes it. */
    public void setMembership(UUID draftId, UUID userId, UUID groupId, boolean member, boolean liveMember) {
        jdbcClient.sql("DELETE FROM draft_membership_change WHERE draft_id = :draftId AND user_id = :userId AND group_id = :groupId")
                .param(PARAM_DRAFT_ID, draftId).param("userId", userId).param(PARAM_GROUP_ID, groupId).update();
        if (member != liveMember) {
            jdbcClient.sql("""
                    INSERT INTO draft_membership_change (draft_id, user_id, group_id, member, base_member)
                    VALUES (:draftId, :userId, :groupId, :member, :baseMember)
                    """).param(PARAM_DRAFT_ID, draftId).param("userId", userId).param(PARAM_GROUP_ID, groupId)
                    .param("member", member).param("baseMember", liveMember).update();
        }
        changed(draftId);
    }

    private void removeIfUnchanged(UUID draftId, UUID groupId) {
        jdbcClient.sql("""
                DELETE FROM draft_group_change WHERE draft_id = :draftId AND group_id = :groupId
                  AND NOT created AND NOT deleted AND name IS NULL AND parent_id IS NULL
                """).param(PARAM_DRAFT_ID, draftId).param(PARAM_GROUP_ID, groupId).update();
    }

    boolean hasCachedView(UUID draftId) {
        return views.containsKey(draftId);
    }

    /** The draft has closed: its view goes, on every node, once the caller's transaction commits. */
    public void forget(UUID draftId) {
        changed(draftId);
    }

    // Once the caller's transaction ends: drop this node's view of the draft, then tell the other nodes. Rolled
    // back too, since a view read inside the transaction may hold what it wrote.
    private void changed(UUID draftId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            publishChanged(draftId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                publishChanged(draftId);
            }
        });
    }

    private void publishChanged(UUID draftId) {
        views.remove(draftId);
        groupsChangedTopic.publish(draftId.toString());
    }
}
