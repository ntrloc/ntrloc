package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.Page;
import org.flowable.job.api.HistoryJob;
import org.flowable.job.service.impl.HistoryJobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.HistoryJobEntity;
import org.flowable.job.service.impl.persistence.entity.HistoryJobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.HistoryJobDataManager;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs history jobs (ACT_RU_HISTORY_JOB) with job_kind = 'HISTORY' rows in process_job. Standalone
// rather than extending AbstractJobDataManager -- HistoryJobEntity doesn't extend
// AbstractRuntimeJobEntity at all (no execution/process-instance/due-date fields, since a history
// job isn't tied to one execution the way a runtime job is), so it doesn't share that base class's
// field surface. Async history is never enabled (see ProcessEngineConfig), so this is unreachable
// in practice today -- built for zero-MyBatis completeness, not because anything currently
// creates a history job.
// java.util.Date is unavoidable here: overrides Flowable's own Date-based entity API.
@SuppressWarnings("java:S2143")
public class HistoryJobDataManagerImpl extends AbstractProcessDataManager implements HistoryJobDataManager {

    private static final String COL_REVISION = "revision";
    private static final String PARAM_LOCK_OWNER = "lockOwner";
    private static final String PARAM_LOCK_EXPIRATION_TIME = "lockExpirationTime";
    private static final String COL_RETRIES = "retries";

    @Override
    public HistoryJobEntity create() {
        return new HistoryJobEntityImpl();
    }

    @Override
    public HistoryJobEntity findById(String id) {
        return jdbcClient().sql(SELECT + " AND id = :id")
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    @Override
    public void insert(HistoryJobEntity entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_job
                    (id, revision, job_kind, job_handler_type, job_handler_configuration, lock_owner,
                     lock_expiration_time, scope_type, retries, exception_message, create_time)
                VALUES
                    (:id, :revision, 'HISTORY', :jobHandlerType, :jobHandlerConfiguration, :lockOwner,
                     :lockExpirationTime, :scopeType, :retries, :exceptionMessage, :createTime)
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, Math.max(entity.getRevision(), 1))
                .param("jobHandlerType", entity.getJobHandlerType())
                .param("jobHandlerConfiguration", entity.getJobHandlerConfiguration())
                .param(PARAM_LOCK_OWNER, entity.getLockOwner())
                .param(PARAM_LOCK_EXPIRATION_TIME, AbstractJobDataManager.toTimestamp(entity.getLockExpirationTime()))
                .param("scopeType", entity.getScopeType())
                .param(COL_RETRIES, entity.getRetries())
                .param("exceptionMessage", entity.getExceptionMessage())
                .param("createTime", AbstractJobDataManager.toTimestamp(entity.getCreateTime()))
                .update();
        session().cache(HistoryJobEntity.class, entity.getId(), entity);
        session().registerFlush(HistoryJobEntity.class, entity.getId(), entity, () -> update(entity));
    }

    @Override
    public HistoryJobEntity update(HistoryJobEntity entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_job SET
                    job_handler_type = :jobHandlerType, job_handler_configuration = :jobHandlerConfiguration,
                    lock_owner = :lockOwner, lock_expiration_time = :lockExpirationTime, scope_type = :scopeType,
                    retries = :retries, exception_message = :exceptionMessage, create_time = :createTime,
                    revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param(COL_REVISION, entity.getRevision())
                .param("jobHandlerType", entity.getJobHandlerType())
                .param("jobHandlerConfiguration", entity.getJobHandlerConfiguration())
                .param(PARAM_LOCK_OWNER, entity.getLockOwner())
                .param(PARAM_LOCK_EXPIRATION_TIME, AbstractJobDataManager.toTimestamp(entity.getLockExpirationTime()))
                .param("scopeType", entity.getScopeType())
                .param(COL_RETRIES, entity.getRetries())
                .param("exceptionMessage", entity.getExceptionMessage())
                .param("createTime", AbstractJobDataManager.toTimestamp(entity.getCreateTime()))
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    "History job " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(HistoryJobEntity.class, entity.getId(), entity);
        return entity;
    }

    @Override
    public void delete(String id) {
        jdbcClient().sql("DELETE FROM process_job WHERE id = :id").param("id", id).update();
        session().evict(HistoryJobEntity.class, id);
    }

    @Override
    public void delete(HistoryJobEntity entity) {
        delete(entity.getId());
    }

    @Override
    public List<HistoryJobEntity> findJobsToExecute(List<String> enabledCategories, Page page) {
        return jdbcClient().sql(SELECT + " AND lock_expiration_time IS NULL ORDER BY id LIMIT :limit OFFSET :offset")
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<HistoryJobEntity> findExpiredJobs(List<String> enabledCategories, Page page) {
        return jdbcClient().sql(SELECT + " AND lock_expiration_time IS NOT NULL AND lock_expiration_time < :now ORDER BY id LIMIT :limit OFFSET :offset")
                .param("now", java.sql.Timestamp.from(java.time.Instant.now()))
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    // No execution/process-instance concept on a history job -- always empty, matching
    // Flowable's own default behavior for this entity type.
    @Override
    public List<HistoryJobEntity> findJobsByExecutionId(String executionId) {
        return new ArrayList<>();
    }

    @Override
    public List<HistoryJobEntity> findJobsByProcessInstanceId(String processInstanceId) {
        return new ArrayList<>();
    }

    @Override
    public void updateJobTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this app.
    }

    @Override
    public void bulkUpdateJobLockWithoutRevisionCheck(List<HistoryJobEntity> jobs, String lockOwner, Date lockExpirationTime) {
        for (HistoryJobEntity job : jobs) {
            jdbcClient().sql("UPDATE process_job SET lock_owner = :lockOwner, lock_expiration_time = :lockExpirationTime WHERE id = :id")
                    .param("id", job.getId())
                    .param(PARAM_LOCK_OWNER, lockOwner)
                    .param(PARAM_LOCK_EXPIRATION_TIME, AbstractJobDataManager.toTimestamp(lockExpirationTime))
                    .update();
        }
    }

    @Override
    public void resetExpiredJob(String jobId) {
        jdbcClient().sql("UPDATE process_job SET lock_owner = NULL, lock_expiration_time = NULL WHERE id = :id")
                .param("id", jobId)
                .update();
    }

    @Override
    public List<HistoryJob> findHistoryJobsByQueryCriteria(HistoryJobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findHistoryJobCountByQueryCriteria(HistoryJobQueryImpl jobQuery) {
        return 0;
    }

    private static final String SELECT = """
            SELECT id, revision, job_handler_type, job_handler_configuration, lock_owner,
                   lock_expiration_time, scope_type, retries, exception_message, create_time
            FROM process_job WHERE job_kind = 'HISTORY'
            """;

    private HistoryJobEntity cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        HistoryJobEntity cached = session().getCached(HistoryJobEntity.class, id);
        if (cached != null) {
            return cached;
        }
        HistoryJobEntity mapped = mapRow(rs);
        session().cache(HistoryJobEntity.class, id, mapped);
        session().registerFlush(HistoryJobEntity.class, id, mapped, () -> update(mapped));
        return mapped;
    }

    private HistoryJobEntity mapRow(ResultSet rs) throws SQLException {
        HistoryJobEntityImpl entity = new HistoryJobEntityImpl();
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt(COL_REVISION));
        entity.setJobHandlerType(rs.getString("job_handler_type"));
        entity.setJobHandlerConfiguration(rs.getString("job_handler_configuration"));
        entity.setLockOwner(rs.getString("lock_owner"));
        entity.setLockExpirationTime(AbstractJobDataManager.fromTimestamp(rs.getTimestamp("lock_expiration_time")));
        entity.setScopeType(rs.getString("scope_type"));
        entity.setRetries(rs.getInt(COL_RETRIES));
        entity.setExceptionMessage(rs.getString("exception_message"));
        entity.setCreateTime(AbstractJobDataManager.fromTimestamp(rs.getTimestamp("create_time")));
        return entity;
    }
}
