package org.ntrloc.graph.db.partition.changelog;

import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.ntrloc.graph.principal.NtrlocPrincipalHolder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * An append-only log of configuration changes (docs/ntrloc-system-model-notes.md §13.6), in one table:
 * live's (ChangeLog), or drafts' (DraftChangeLog, each entry scoped to its draft). Appending joins the
 * caller's transaction, so an entry commits or rolls back with the change it records. Who made it comes
 * from the request's principal; {@link #withSource} names where it came from, and with no principal (work
 * outside a request) the change is the system's.
 */
public abstract class ChangeEntries {

    /** One change: `before`/`after` describe it (names included), `refs` every id it touches. */
    public record Entry(long seq, OffsetDateTime at, UUID actorId, String actorName, String source, String kind,
                        String elementKey, List<UUID> refs, Map<String, Object> before, Map<String, Object> after) {}

    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final JdbcClient jdbcClient;
    private final String table;
    // The column an entry's scope goes in (draft_id), or null for an unscoped log (live's).
    private final String scopeColumn;

    protected ChangeEntries(JdbcClient jdbcClient, String table, String scopeColumn) {
        this.jdbcClient = jdbcClient;
        this.table = table;
        this.scopeColumn = scopeColumn;
    }

    /**
     * Runs `work` with its changes recorded as coming from `source`: e.g. "startup" (no principal, so the
     * system's), or "draft 'Q4 access'" (an admin applying a draft).
     */
    public static void withSource(String source, Runnable work) {
        String previous = SOURCE.get();
        SOURCE.set(source);
        try {
            work.run();
        } finally {
            if (previous == null) SOURCE.remove();
            else SOURCE.set(previous);
        }
    }

    public static <T> T withSource(String source, Supplier<T> work) {
        String previous = SOURCE.get();
        SOURCE.set(source);
        try {
            return work.get();
        } finally {
            if (previous == null) SOURCE.remove();
            else SOURCE.set(previous);
        }
    }

    protected void append(UUID scope, String kind, String elementKey, Collection<UUID> refs, Map<String, ?> before, Map<String, ?> after) {
        NtrlocPrincipal actor = currentActor();
        String source = SOURCE.get();
        String scoped = scopeColumn == null ? "" : scopeColumn + ", ";
        jdbcClient.sql("INSERT INTO " + table + " (" + scoped + "actor_id, actor_name, source, kind, element_key, refs, before, after)"
                        + " VALUES (" + (scopeColumn == null ? "" : ":scope, ") + ":actorId, :actorName, :source, :kind, :elementKey,"
                        + " CAST(:refs AS uuid[]), CAST(:before AS jsonb), CAST(:after AS jsonb))")
                .param("scope", scope)
                .param("actorId", actor == null ? null : actor.id())
                .param("actorName", actor == null ? null : actor.displayName())
                .param("source", actor == null && source == null ? "system" : source)
                .param("kind", kind)
                .param("elementKey", elementKey)
                .param("refs", refs.stream().filter(Objects::nonNull).distinct().map(UUID::toString).collect(Collectors.joining(",", "{", "}")))
                .param("before", before == null ? null : JSON.writeValueAsString(before))
                .param("after", after == null ? null : JSON.writeValueAsString(after))
                .update();
    }

    /** Every entry in the scope (a draft's), oldest first. */
    protected List<Entry> entriesIn(UUID scope) {
        return entries(" WHERE " + scopeColumn + " = :value", scope);
    }

    /** The latest entry's position; 0 when there are none. */
    public long latestPosition() {
        return jdbcClient.sql("SELECT COALESCE(MAX(seq), 0) FROM " + table).query(Long.class).single();
    }

    /** Every entry after `position`, oldest first. */
    public List<Entry> entriesAfter(long position) {
        return entries(" WHERE seq > :value", position);
    }

    /** The columns an Entry is read from, for queries over a log's table (aliased `l`). */
    public static final String ENTRY_COLUMNS = "l.seq, l.at, l.actor_id, l.actor_name, l.source, l.kind, l.element_key,"
            + " l.refs::text AS refs, l.before::text AS before, l.after::text AS after";

    private List<Entry> entries(String where, Object value) {
        return jdbcClient.sql("SELECT " + ENTRY_COLUMNS + " FROM " + table + " l" + where.replace(" WHERE ", " WHERE l.") + " ORDER BY l.seq")
                .param("value", value)
                .query((rs, _) -> entry(rs))
                .list();
    }

    /** One Entry, from a row with ENTRY_COLUMNS. */
    public static Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getLong("seq"), rs.getObject("at", OffsetDateTime.class),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("source"),
                rs.getString("kind"), rs.getString("element_key"), refs(rs.getString("refs")),
                map(rs.getString("before")), map(rs.getString("after")));
    }

    private static NtrlocPrincipal currentActor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) return null;
        if (auth.getPrincipal() instanceof NtrlocPrincipal principal) return principal;
        if (auth.getPrincipal() instanceof NtrlocPrincipalHolder holder) return holder.ntrlocPrincipal();
        return null;
    }

    private static List<UUID> refs(String array) {
        String inner = array.substring(1, array.length() - 1);
        if (inner.isEmpty()) return List.of();
        return Arrays.stream(inner.split(",")).map(UUID::fromString).collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
    }

    private static Map<String, Object> map(String json) {
        return json == null ? null : JSON.readValue(json, MAP);
    }
}
