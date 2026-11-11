package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.persistence.entity.ByteArrayEntity;
import org.flowable.common.engine.impl.persistence.entity.ByteArrayEntityImpl;
import org.flowable.common.engine.impl.persistence.entity.data.ByteArrayDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

// Backs Flowable's byte arrays (ACT_GE_BYTEARRAY) with process_bytearray. Without it, anything Flowable
// stores as bytes went to a table that doesn't exist: first found as a failing async job, whose stack
// trace is one (AbstractJobDataManager's exception_stack_id).
public class ByteArrayDataManagerImpl extends AbstractProcessDataManager implements ByteArrayDataManager {

    private static final String SELECT = "SELECT id, revision, name, deployment_id, bytes FROM process_bytearray";
    private static final String COL_REVISION = "revision";
    private static final String COL_BYTES = "bytes";

    @Override
    public ByteArrayEntity create() {
        return new ByteArrayEntityImpl();
    }

    @Override
    public ByteArrayEntity findById(String id) {
        return jdbcClient().sql(SELECT + " WHERE id = :id").param("id", id).query(this::cacheOrMap).optional().orElse(null);
    }

    @Override
    public List<ByteArrayEntity> findAll() {
        return jdbcClient().sql(SELECT).query(this::cacheOrMap).list();
    }

    @Override
    public void insert(ByteArrayEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_bytearray (id, revision, name, deployment_id, bytes)
                VALUES (:id, :revision, :name, :deploymentId, :bytes)
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, Math.max(entity.getRevision(), 1))
                .param("name", entity.getName())
                .param("deploymentId", entity.getDeploymentId())
                .param(COL_BYTES, entity.getBytes())
                .update();
        session().cache(ByteArrayEntity.class, entity.getId(), entity);
        session().registerFlush(ByteArrayEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public ByteArrayEntity update(ByteArrayEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_bytearray SET bytes = :bytes, revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, entity.getRevision())
                .param(COL_BYTES, entity.getBytes())
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException("Byte array " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(ByteArrayEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        deleteByteArrayNoRevisionCheck(id);
    }

    @Override
    public void delete(ByteArrayEntity entity) {
        deleteByteArrayNoRevisionCheck(entity.getId());
    }

    @Override
    public void deleteByteArrayNoRevisionCheck(String id) {
        jdbcClient().sql("DELETE FROM process_bytearray WHERE id = :id").param("id", id).update();
        session().evict(ByteArrayEntity.class, id);
    }

    @Override
    public void bulkDeleteByteArraysNoRevisionCheck(List<String> ids) {
        if (ids.isEmpty()) return;
        session().evictAll(ByteArrayEntity.class, ids);
        jdbcClient().sql("DELETE FROM process_bytearray WHERE id IN (:ids)").param("ids", ids).update();
    }

    private ByteArrayEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        ByteArrayEntity cached = session().getCached(ByteArrayEntity.class, id);
        if (cached != null) {
            return cached;
        }
        ByteArrayEntityImpl entity = new ByteArrayEntityImpl();
        entity.setId(id);
        entity.setRevision(rs.getInt(COL_REVISION));
        entity.setName(rs.getString("name"));
        entity.setDeploymentId(rs.getString("deployment_id"));
        entity.setBytes(rs.getBytes(COL_BYTES));
        session().cache(ByteArrayEntity.class, id, entity);
        session().registerFlush(ByteArrayEntity.class, id, entity, () -> update(entity));
        return entity;
    }
}
