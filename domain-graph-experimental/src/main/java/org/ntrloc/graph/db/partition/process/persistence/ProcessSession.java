package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.interceptor.Session;
import org.flowable.common.engine.impl.persistence.entity.Entity;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

// Replaces MyBatis's DbSqlSession as Flowable's low-level persistence unit-of-work. Writes
// immediately via JdbcClient rather than batching/deferring to flush() -- JdbcClient already
// participates in the ambient Spring-managed transaction via the shared DataSource, so there's
// no connection or transaction lifecycle to manage here beyond exposing the client itself.
//
// Also holds a per-command entity cache, keyed by (entity type, id). This turned out to be load-
// bearing, not an optimization: Flowable's engine calls findById() repeatedly for the same
// execution/variable within a single command and expects the *same* Java object back each time --
// it mutates that object in place (activity position, ended flag, etc.) and only later calls
// update()/delete() on it, relying on object identity to know which in-flight entity it's talking
// about. Without this cache, each findById() mapped a fresh row into a fresh object, so mutations
// made via one lookup were invisible to the next -- the process would silently lose all
// intermediate state and only "complete" by the accident of whichever object happened to still be
// held at delete() time.
//
// flush() is NOT actually a no-op in practice (see registerFlush below) -- a lot of Flowable's own
// engine code mutates an already-cached entity's fields directly (e.g. moving an execution's
// activityId/currentFlowElement as the token advances) without ever calling the DataManager's
// update() again itself; it relies on the default DbSqlSession noticing the entity is dirty and
// re-persisting it at flush() time. Every command that stayed entirely synchronous (no User Task,
// nothing pausing mid-process) happened to reach a state where the explicit update()/delete() calls
// that DID occur were sufficient, so this gap stayed invisible until a real wait state was added.
public class ProcessSession implements Session {

    private final JdbcClient jdbcClient;
    private final Map<Class<?>, Map<String, Object>> entityCache = new HashMap<>();
    // Keyed by "type:id" so registering the same entity again (e.g. re-cached on a later findById
    // within the same command) replaces rather than duplicates its flush callback.
    private final Map<String, Runnable> flushCallbacks = new LinkedHashMap<>();
    private final Map<String, Entity> flushEntities = new LinkedHashMap<>();
    // Snapshot of getPersistentState() as of the *first* registration for a given key -- entities
    // are only ever registered once per command (cacheOrMap()-style finders short-circuit on a
    // cache hit without re-registering), so this correctly captures "state when this command first
    // saw it," which flush() compares against to decide whether a write-back is actually needed.
    private final Map<String, Object> flushSnapshots = new LinkedHashMap<>();

    public ProcessSession(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public JdbcClient jdbcClient() {
        return jdbcClient;
    }

    @SuppressWarnings("unchecked")
    public <T> T getCached(Class<T> type, String id) {
        Map<String, Object> byId = entityCache.get(type);
        return byId == null ? null : (T) byId.get(id);
    }

    public <T> void cache(Class<T> type, String id, T entity) {
        entityCache.computeIfAbsent(type, t -> new HashMap<>()).put(id, entity);
    }

    public void evict(Class<?> type, String id) {
        Map<String, Object> byId = entityCache.get(type);
        if (byId != null) {
            byId.remove(id);
        }
        String key = type.getName() + ":" + id;
        flushCallbacks.remove(key);
        flushEntities.remove(key);
        flushSnapshots.remove(key);
    }

    // Bulk-delete methods (e.g. deleteActivityInstancesByProcessInstanceId) issue a single DELETE
    // for every row matching some criteria, bypassing the per-id delete()+evict() path entirely.
    // Without this, a row that was found and mutated in-memory earlier in the same command (e.g. an
    // activity instance being marked ended) but bulk-deleted later still has a pending flush
    // callback -- flush() then tries to UPDATE a row that's already gone, either silently losing
    // the write or (for revision-checked managers) throwing a false "optimistic lock" error, since
    // 0 rows affected looks identical to a real concurrent update. Callers must evict every id a
    // bulk delete is about to remove before issuing the DELETE.
    public void evictAll(Class<?> type, java.util.Collection<String> ids) {
        ids.forEach(id -> evict(type, id));
    }

    // Re-runs `writeBack` (typically the owning DataManager's own update(entity)) at flush() time,
    // using whatever the entity's current in-memory field values are then -- not just whatever
    // they were when this was registered. See the class comment for why this exists. `entity` must
    // be the same object flush() will re-read state from -- flush() only actually calls `writeBack`
    // if its persistent state has changed since this registration (see flush()).
    public void registerFlush(Class<?> type, String id, Entity entity, Runnable writeBack) {
        String key = type.getName() + ":" + id;
        flushCallbacks.put(key, writeBack);
        flushEntities.put(key, entity);
        flushSnapshots.putIfAbsent(key, entity.getPersistentState());
    }

    // Now that update() does a real revision-checked write (Section 6), unconditionally re-running
    // writeBack for every cached entity -- including ones that were only ever read -- would bump
    // revisions and create lock contention between commands that never actually conflicted. Only
    // entities whose persistent state actually changed since they were first seen this command are
    // written back; the rest are skipped entirely.
    @Override
    public void flush() {
        for (Map.Entry<String, Runnable> entry : flushCallbacks.entrySet()) {
            String key = entry.getKey();
            Entity entity = flushEntities.get(key);
            if (entity == null || !Objects.equals(entity.getPersistentState(), flushSnapshots.get(key))) {
                entry.getValue().run();
            }
        }
    }

    @Override
    public void close() {
        // no-op: connection lifecycle belongs to Spring's DataSource/transaction, not this session.
    }
}
