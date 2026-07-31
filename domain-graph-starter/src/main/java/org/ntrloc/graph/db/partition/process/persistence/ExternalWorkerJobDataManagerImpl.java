package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.Page;
import org.flowable.job.api.ExternalWorkerJob;
import org.flowable.job.service.impl.ExternalWorkerJobAcquireBuilderImpl;
import org.flowable.job.service.impl.ExternalWorkerJobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.ExternalWorkerJobEntity;
import org.flowable.job.service.impl.persistence.entity.ExternalWorkerJobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.ExternalWorkerJobDataManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs external worker jobs (ACT_RU_EXTERNAL_JOB) with job_kind = 'EXTERNAL_WORKER' rows in
// process_job. Nothing in this app uses external worker tasks today -- built for zero-MyBatis
// completeness (the workflow persistence layer), acquire/expire shape is real (same as
// JobDataManagerImpl), the worker-specific finder surface is stubbed.
public class ExternalWorkerJobDataManagerImpl extends AbstractJobDataManager<ExternalWorkerJobEntity> implements ExternalWorkerJobDataManager {

    @Override
    protected String jobKind() {
        return "EXTERNAL_WORKER";
    }

    @Override
    protected ExternalWorkerJobEntity newEntity() {
        return new ExternalWorkerJobEntityImpl();
    }

    @Override
    protected Class<ExternalWorkerJobEntity> entityType() {
        return ExternalWorkerJobEntity.class;
    }

    @Override
    protected String lockOwner(ExternalWorkerJobEntity entity) {
        return entity.getLockOwner();
    }

    @Override
    protected void setLockOwner(ExternalWorkerJobEntity entity, String lockOwner) {
        entity.setLockOwner(lockOwner);
    }

    @Override
    protected Date lockExpirationTime(ExternalWorkerJobEntity entity) {
        return entity.getLockExpirationTime();
    }

    @Override
    protected void setLockExpirationTime(ExternalWorkerJobEntity entity, Date time) {
        entity.setLockExpirationTime(time);
    }

    @Override
    public List<ExternalWorkerJobEntity> findJobsToExecute(List<String> enabledCategories, Page page) {
        return jdbcClient().sql("""
                SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                       lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                       process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                       scope_definition_id, correlation_id, retries, exception_message, due_date,
                       repeat_cycle, end_date, max_iterations, create_time
                FROM process_job
                WHERE job_kind = :kind AND lock_expiration_time IS NULL
                ORDER BY id LIMIT :limit OFFSET :offset
                """)
                .param("kind", jobKind())
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<ExternalWorkerJobEntity> findExpiredJobs(List<String> enabledCategories, Page page) {
        return jdbcClient().sql("""
                SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                       lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                       process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                       scope_definition_id, correlation_id, retries, exception_message, due_date,
                       repeat_cycle, end_date, max_iterations, create_time
                FROM process_job
                WHERE job_kind = :kind AND lock_expiration_time IS NOT NULL AND lock_expiration_time < :now
                ORDER BY id LIMIT :limit OFFSET :offset
                """)
                .param("kind", jobKind())
                .param("now", java.sql.Timestamp.from(java.time.Instant.now()))
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public void bulkUpdateJobLockWithoutRevisionCheck(List<ExternalWorkerJobEntity> jobs, String lockOwner, Date lockExpirationTime) {
        for (ExternalWorkerJobEntity job : jobs) {
            jdbcClient().sql("UPDATE process_job SET lock_owner = :lockOwner, lock_expiration_time = :lockExpirationTime WHERE id = :id")
                    .param("id", job.getId())
                    .param("lockOwner", lockOwner)
                    .param("lockExpirationTime", AbstractJobDataManager.toTimestamp(lockExpirationTime))
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
    public void deleteJobsByExecutionId(String executionId) {
        jdbcClient().sql("DELETE FROM process_job WHERE job_kind = :kind AND execution_id = :executionId")
                .param("kind", jobKind())
                .param("executionId", executionId)
                .update();
    }

    // Worker-specific / query-object surfaces: stubbed, matching this package's convention --
    // nothing in this app dispatches external worker tasks yet.
    @Override
    public List<ExternalWorkerJobEntity> findExternalJobsToExecute(ExternalWorkerJobAcquireBuilderImpl builder, int numberOfTasks) {
        return new ArrayList<>();
    }

    @Override
    public List<ExternalWorkerJobEntity> findJobsByScopeIdAndSubScopeId(String scopeId, String subScopeId) {
        return new ArrayList<>();
    }

    @Override
    public List<ExternalWorkerJobEntity> findJobsByWorkerId(String workerId) {
        return new ArrayList<>();
    }

    @Override
    public List<ExternalWorkerJobEntity> findJobsByWorkerIdAndTenantId(String workerId, String tenantId) {
        return new ArrayList<>();
    }

    @Override
    public List<ExternalWorkerJob> findJobsByQueryCriteria(ExternalWorkerJobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findJobCountByQueryCriteria(ExternalWorkerJobQueryImpl jobQuery) {
        return 0;
    }
}
