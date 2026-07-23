package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.job.service.impl.persistence.entity.AbstractRuntimeJobEntity;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;

// Shared CRUD/finder logic for the five AbstractRuntimeJobEntity-shaped job kinds (Job, TimerJob,
// SuspendedJob, DeadLetterJob, ExternalWorkerJob) -- all backed by one process_job table with a
// job_kind discriminator (see ProcessPersistenceInitializer), matching this package's existing
// process_task_identitylink discriminator convention. HistoryJob is different enough in shape (no
// execution/process-instance fields at all) to warrant its own standalone class instead.
//
// Lock fields (lock_owner/lock_expiration_time) only exist at the entity-interface level on the
// JobInfoEntity-bound subset (Job/TimerJob/ExternalWorkerJob) -- a job is delocked before ever
// moving to Suspended or DeadLetter, so those two override lockOwner()/setLockOwner()/
// lockExpirationTime()/setLockExpirationTime() as no-ops; the table column just stays NULL for
// those rows.
abstract class AbstractJobDataManager<T extends AbstractRuntimeJobEntity> extends AbstractProcessDataManager {

    protected abstract String jobKind();

    protected abstract T newEntity();

    protected abstract Class<T> entityType();

    protected String lockOwner(T entity) {
        return null;
    }

    protected void setLockOwner(T entity, String lockOwner) {
        // no-op: not modeled on this job kind's entity interface.
    }

    protected Date lockExpirationTime(T entity) {
        return null;
    }

    protected void setLockExpirationTime(T entity, Date time) {
        // no-op: not modeled on this job kind's entity interface.
    }

    public T create() {
        return newEntity();
    }

    public T findById(String id) {
        return jdbcClient().sql(selectSql() + " AND id = :id")
                .param("kind", jobKind())
                .param("id", id)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    // Flowable's own move* methods (DefaultJobManager, e.g. moveTimerJobToExecutableJob) implement a
    // job-kind transition as insert-the-new-kind-then-delete-the-old-kind, copying the *same* id across
    // (copyJobInfo: copyToJob.setId(copyFromJob.getId())) -- a pattern that only works if the two kinds
    // live in separate tables, so the same id can transiently exist in both. This table deliberately
    // uses one shared row per job (ProcessPersistenceInitializer's process_job comment: a kind change is
    // meant to be "a plain UPDATE of job_kind"), so a plain INSERT here collided with the still-present
    // old-kind row on the id primary key -- confirmed live: every AcquireTimerJobsRunnable poll cycle
    // threw "duplicate key value violates unique constraint process_job_pkey" and the timer job could
    // never be promoted to executable, so it never actually ran (docs/ntrloc-workflow-summary.md "Job
    // control: AsyncExecutor" addendum). ON CONFLICT DO UPDATE turns Flowable's insert call into the
    // in-place kind-update the table was designed for; delete() below is scoped to this manager's own
    // job_kind so the old-kind manager's follow-up delete() can't then remove the row just upserted here.
    public void insert(T entity) {
        assignIdIfMissing(entity);
        jdbcClient().sql("""
                INSERT INTO process_job
                    (id, revision, job_kind, category, job_type, job_handler_type, job_handler_configuration,
                     lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                     process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                     scope_definition_id, correlation_id, retries, exception_message, due_date, repeat_cycle,
                     end_date, max_iterations, create_time)
                VALUES
                    (:id, :revision, :jobKind, :category, :jobType, :jobHandlerType, :jobHandlerConfiguration,
                     :lockOwner, :lockExpirationTime, :isExclusive, :executionId, :processInstanceId,
                     :processDefinitionId, :elementId, :elementName, :scopeId, :subScopeId, :scopeType,
                     :scopeDefinitionId, :correlationId, :retries, :exceptionMessage, :dueDate, :repeatCycle,
                     :endDate, :maxIterations, :createTime)
                ON CONFLICT (id) DO UPDATE SET
                    revision = EXCLUDED.revision, job_kind = EXCLUDED.job_kind, category = EXCLUDED.category,
                    job_type = EXCLUDED.job_type, job_handler_type = EXCLUDED.job_handler_type,
                    job_handler_configuration = EXCLUDED.job_handler_configuration,
                    lock_owner = EXCLUDED.lock_owner, lock_expiration_time = EXCLUDED.lock_expiration_time,
                    is_exclusive = EXCLUDED.is_exclusive, execution_id = EXCLUDED.execution_id,
                    process_instance_id = EXCLUDED.process_instance_id,
                    process_definition_id = EXCLUDED.process_definition_id, element_id = EXCLUDED.element_id,
                    element_name = EXCLUDED.element_name, scope_id = EXCLUDED.scope_id,
                    sub_scope_id = EXCLUDED.sub_scope_id, scope_type = EXCLUDED.scope_type,
                    scope_definition_id = EXCLUDED.scope_definition_id, correlation_id = EXCLUDED.correlation_id,
                    retries = EXCLUDED.retries, exception_message = EXCLUDED.exception_message,
                    due_date = EXCLUDED.due_date, repeat_cycle = EXCLUDED.repeat_cycle,
                    end_date = EXCLUDED.end_date, max_iterations = EXCLUDED.max_iterations,
                    create_time = EXCLUDED.create_time
                """)
                .param("id", entity.getId())
                .param("revision", Math.max(entity.getRevision(), 1))
                .param("jobKind", jobKind())
                .param("category", entity.getCategory())
                .param("jobType", entity.getJobType())
                .param("jobHandlerType", entity.getJobHandlerType())
                .param("jobHandlerConfiguration", entity.getJobHandlerConfiguration())
                .param("lockOwner", lockOwner(entity))
                .param("lockExpirationTime", toTimestamp(lockExpirationTime(entity)))
                .param("isExclusive", entity.isExclusive())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("elementId", entity.getElementId())
                .param("elementName", entity.getElementName())
                .param("scopeId", entity.getScopeId())
                .param("subScopeId", entity.getSubScopeId())
                .param("scopeType", entity.getScopeType())
                .param("scopeDefinitionId", entity.getScopeDefinitionId())
                .param("correlationId", entity.getCorrelationId())
                .param("retries", entity.getRetries())
                .param("exceptionMessage", entity.getExceptionMessage())
                .param("dueDate", toTimestamp(entity.getDuedate()))
                .param("repeatCycle", entity.getRepeat())
                .param("endDate", toTimestamp(entity.getEndDate()))
                .param("maxIterations", entity.getMaxIterations())
                .param("createTime", toTimestamp(entity.getCreateTime()))
                .update();
        session().cache(entityType(), entity.getId(), entity);
        session().registerFlush(entityType(), entity.getId(), entity, () -> update(entity));
    }

    public T update(T entity) {
        int rowsAffected = jdbcClient().sql("""
                UPDATE process_job SET
                    category = :category, job_type = :jobType, job_handler_type = :jobHandlerType,
                    job_handler_configuration = :jobHandlerConfiguration, lock_owner = :lockOwner,
                    lock_expiration_time = :lockExpirationTime, is_exclusive = :isExclusive,
                    execution_id = :executionId, process_instance_id = :processInstanceId,
                    process_definition_id = :processDefinitionId, element_id = :elementId,
                    element_name = :elementName, scope_id = :scopeId, sub_scope_id = :subScopeId,
                    scope_type = :scopeType, scope_definition_id = :scopeDefinitionId,
                    correlation_id = :correlationId, retries = :retries, exception_message = :exceptionMessage,
                    due_date = :dueDate, repeat_cycle = :repeatCycle, end_date = :endDate,
                    max_iterations = :maxIterations, create_time = :createTime, revision = revision + 1
                WHERE id = :id AND revision = :revision
                """)
                .param("id", entity.getId())
                .param("revision", entity.getRevision())
                .param("category", entity.getCategory())
                .param("jobType", entity.getJobType())
                .param("jobHandlerType", entity.getJobHandlerType())
                .param("jobHandlerConfiguration", entity.getJobHandlerConfiguration())
                .param("lockOwner", lockOwner(entity))
                .param("lockExpirationTime", toTimestamp(lockExpirationTime(entity)))
                .param("isExclusive", entity.isExclusive())
                .param("executionId", entity.getExecutionId())
                .param("processInstanceId", entity.getProcessInstanceId())
                .param("processDefinitionId", entity.getProcessDefinitionId())
                .param("elementId", entity.getElementId())
                .param("elementName", entity.getElementName())
                .param("scopeId", entity.getScopeId())
                .param("subScopeId", entity.getSubScopeId())
                .param("scopeType", entity.getScopeType())
                .param("scopeDefinitionId", entity.getScopeDefinitionId())
                .param("correlationId", entity.getCorrelationId())
                .param("retries", entity.getRetries())
                .param("exceptionMessage", entity.getExceptionMessage())
                .param("dueDate", toTimestamp(entity.getDuedate()))
                .param("repeatCycle", entity.getRepeat())
                .param("endDate", toTimestamp(entity.getEndDate()))
                .param("maxIterations", entity.getMaxIterations())
                .param("createTime", toTimestamp(entity.getCreateTime()))
                .update();
        if (rowsAffected == 0) {
            throw new FlowableOptimisticLockingException(
                    jobKind() + " job " + entity.getId() + " was updated by another transaction concurrently");
        }
        entity.setRevision(entity.getRevisionNext());
        session().cache(entityType(), entity.getId(), entity);
        return entity;
    }

    public void delete(String id) {
        // Scoped to this manager's own kind (see insert()'s comment): if the row was already
        // upserted to a different job_kind by a move that ran first in the same transaction, this
        // correctly becomes a no-op instead of deleting the row the move just produced.
        jdbcClient().sql("DELETE FROM process_job WHERE id = :id AND job_kind = :kind")
                .param("id", id)
                .param("kind", jobKind())
                .update();
        session().evict(entityType(), id);
    }

    public void delete(T entity) {
        delete(entity.getId());
    }

    public List<T> findJobsByExecutionId(String executionId) {
        return jdbcClient().sql(selectSql() + " AND execution_id = :executionId")
                .param("kind", jobKind())
                .param("executionId", executionId)
                .query(this::cacheOrMap)
                .list();
    }

    public List<T> findJobsByProcessInstanceId(String processInstanceId) {
        return jdbcClient().sql(selectSql() + " AND process_instance_id = :processInstanceId")
                .param("kind", jobKind())
                .param("processInstanceId", processInstanceId)
                .query(this::cacheOrMap)
                .list();
    }

    public T findJobByCorrelationId(String correlationId) {
        return jdbcClient().sql(selectSql() + " AND correlation_id = :correlationId")
                .param("kind", jobKind())
                .param("correlationId", correlationId)
                .query(this::cacheOrMap)
                .optional()
                .orElse(null);
    }

    public void updateJobTenantIdForDeployment(String deploymentId, String newTenantId) {
        // no-op: tenants aren't modeled in this app.
    }

    private String selectSql() {
        return """
                SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                       lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                       process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                       scope_definition_id, correlation_id, retries, exception_message, due_date,
                       repeat_cycle, end_date, max_iterations, create_time
                FROM process_job WHERE job_kind = :kind
                """;
    }

    // protected, not private: subclasses issuing their own queries beyond this base class's finders
    // (e.g. JobDataManagerImpl.findJobsToExecute) reuse this as their row mapper too.
    protected T cacheOrMap(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        T cached = session().getCached(entityType(), id);
        if (cached != null) {
            return cached;
        }
        T mapped = mapRow(rs);
        session().cache(entityType(), id, mapped);
        session().registerFlush(entityType(), id, mapped, () -> update(mapped));
        return mapped;
    }

    private T mapRow(ResultSet rs) throws SQLException {
        T entity = newEntity();
        entity.setId(rs.getString("id"));
        entity.setRevision(rs.getInt("revision"));
        entity.setCategory(rs.getString("category"));
        entity.setJobType(rs.getString("job_type"));
        entity.setJobHandlerType(rs.getString("job_handler_type"));
        entity.setJobHandlerConfiguration(rs.getString("job_handler_configuration"));
        setLockOwner(entity, rs.getString("lock_owner"));
        setLockExpirationTime(entity, fromTimestamp(rs.getTimestamp("lock_expiration_time")));
        entity.setExclusive(rs.getBoolean("is_exclusive"));
        entity.setExecutionId(rs.getString("execution_id"));
        entity.setProcessInstanceId(rs.getString("process_instance_id"));
        entity.setProcessDefinitionId(rs.getString("process_definition_id"));
        entity.setElementId(rs.getString("element_id"));
        entity.setElementName(rs.getString("element_name"));
        entity.setScopeId(rs.getString("scope_id"));
        entity.setSubScopeId(rs.getString("sub_scope_id"));
        entity.setScopeType(rs.getString("scope_type"));
        entity.setScopeDefinitionId(rs.getString("scope_definition_id"));
        entity.setCorrelationId(rs.getString("correlation_id"));
        entity.setRetries(rs.getInt("retries"));
        entity.setExceptionMessage(rs.getString("exception_message"));
        entity.setDuedate(fromTimestamp(rs.getTimestamp("due_date")));
        entity.setRepeat(rs.getString("repeat_cycle"));
        entity.setEndDate(fromTimestamp(rs.getTimestamp("end_date")));
        entity.setMaxIterations(rs.getInt("max_iterations"));
        entity.setCreateTime(fromTimestamp(rs.getTimestamp("create_time")));
        return entity;
    }

    static Timestamp toTimestamp(Date date) {
        return date == null ? null : new Timestamp(date.getTime());
    }

    static Date fromTimestamp(Timestamp timestamp) {
        return timestamp == null ? null : new Date(timestamp.getTime());
    }
}
