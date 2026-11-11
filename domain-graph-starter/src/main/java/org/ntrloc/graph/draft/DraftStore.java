package org.ntrloc.graph.draft;

import org.ntrloc.graph.draft.DraftRecord.Status;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// The draft registry's storage: drafts and their participants (tables draft, draft_participant).
// Written only by DraftService.
@Component
public class DraftStore {

    private static final String DRAFT_COLUMNS = "d.id, d.name, d.status, d.created_by, d.created_at, d.closed_by, d.closed_at";
    private static final String SELECT_DRAFTS = "SELECT " + DRAFT_COLUMNS + " FROM draft d ";
    private static final String PARAM_DRAFT_ID = "draftId";
    private static final String PARAM_USER_ID = "userId";

    // Any key works, as long as only draft creation takes it.
    private static final long CREATE_LOCK_KEY = 0x6472616674L;

    private final JdbcClient jdbcClient;

    public DraftStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Serialises draft creation until the caller's transaction ends, so "how many are open" can't race. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForCreate() {
        jdbcClient.sql("SELECT pg_advisory_xact_lock(:key)").param("key", CREATE_LOCK_KEY).query((rs, _) -> 0).single();
    }

    public DraftRecord create(String name, UUID createdBy) {
        return jdbcClient.sql("INSERT INTO draft (name, created_by) VALUES (:name, :createdBy) RETURNING " + DRAFT_COLUMNS.replace("d.", ""))
                .param("name", name).param("createdBy", createdBy)
                .query((rs, _) -> row(rs)).single();
    }

    public Optional<DraftRecord> find(UUID id) {
        return jdbcClient.sql(SELECT_DRAFTS + "WHERE d.id = :id")
                .param("id", id).query((rs, _) -> row(rs)).optional();
    }

    public List<DraftRecord> listOpen() {
        return jdbcClient.sql(SELECT_DRAFTS + "WHERE d.status = 'OPEN' ORDER BY d.created_at")
                .query((rs, _) -> row(rs)).list();
    }

    public List<DraftRecord> listOpenFor(UUID userId) {
        return jdbcClient.sql("""
                SELECT %s FROM draft d JOIN draft_participant p ON p.draft_id = d.id
                WHERE d.status = 'OPEN' AND p.user_id = :userId ORDER BY d.created_at
                """.formatted(DRAFT_COLUMNS))
                .param(PARAM_USER_ID, userId).query((rs, _) -> row(rs)).list();
    }

    /** Locks an open draft until the caller's transaction ends. Empty when it isn't open. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<DraftRecord> lockOpen(UUID id) {
        return jdbcClient.sql(SELECT_DRAFTS + "WHERE d.id = :id AND d.status = 'OPEN' FOR UPDATE")
                .param("id", id).query((rs, _) -> row(rs)).optional();
    }

    /** Empty when the draft wasn't open (already closed, or no such draft). */
    public Optional<DraftRecord> close(UUID id, Status status, UUID closedBy) {
        return jdbcClient.sql("""
                UPDATE draft SET status = :status, closed_by = :closedBy, closed_at = now()
                WHERE id = :id AND status = 'OPEN' RETURNING %s
                """.formatted(DRAFT_COLUMNS.replace("d.", "")))
                .param("id", id).param("status", status.name()).param("closedBy", closedBy)
                .query((rs, _) -> row(rs)).optional();
    }

    public List<DraftParticipantRecord> participants(UUID draftId) {
        return jdbcClient.sql("""
                SELECT u.id, u.display_name, u.email, p.added_at FROM draft_participant p
                JOIN security_user u ON u.id = p.user_id WHERE p.draft_id = :draftId ORDER BY u.display_name
                """)
                .param(PARAM_DRAFT_ID, draftId)
                .query((rs, _) -> new DraftParticipantRecord(rs.getObject("id", UUID.class), rs.getString("display_name"),
                        rs.getString("email"), rs.getObject("added_at", OffsetDateTime.class)))
                .list();
    }

    public boolean isParticipant(UUID draftId, UUID userId) {
        return jdbcClient.sql("SELECT EXISTS (SELECT 1 FROM draft_participant WHERE draft_id = :draftId AND user_id = :userId)")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_USER_ID, userId).query(Boolean.class).single();
    }

    public void addParticipant(UUID draftId, UUID userId) {
        jdbcClient.sql("INSERT INTO draft_participant (draft_id, user_id) VALUES (:draftId, :userId) ON CONFLICT DO NOTHING")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_USER_ID, userId).update();
    }

    public void removeParticipant(UUID draftId, UUID userId) {
        jdbcClient.sql("DELETE FROM draft_participant WHERE draft_id = :draftId AND user_id = :userId")
                .param(PARAM_DRAFT_ID, draftId).param(PARAM_USER_ID, userId).update();
    }

    private static DraftRecord row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DraftRecord(rs.getObject("id", UUID.class), rs.getString("name"), Status.valueOf(rs.getString("status")),
                rs.getObject("created_by", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("closed_by", UUID.class), rs.getObject("closed_at", OffsetDateTime.class));
    }
}
