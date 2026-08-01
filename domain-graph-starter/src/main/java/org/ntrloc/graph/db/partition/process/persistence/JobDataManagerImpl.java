package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.Page;
import org.flowable.job.api.Job;
import org.flowable.job.service.impl.JobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.JobDataManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs Flowable's "due, waiting to be claimed" job (ACT_RU_JOB) with job_kind = 'JOB' rows in
// process_job. Claim safety needs no bespoke logic: AcquireJobsCmd just loads via
// findJobsToExecute, mutates lockOwner/
// lockExpirationTime in memory, and lets the normal flush-time update() do the revision-checked
// write -- that already works correctly via AbstractJobDataManager's cache/registerFlush pattern.
// The async executor is never activated (see ProcessEngineConfig), so findJobsToExecute has no
// caller today -- still implemented for real, since a future timer-start-event's job row needs
// somewhere correct to land the moment it's inserted, independent of whether anything ever polls it.
// java.util.Date is unavoidable here: overrides Flowable's own Date-based entity API.
@SuppressWarnings("java:S2143")
public class JobDataManagerImpl extends AbstractJobDataManager<JobEntity> implements JobDataManager {

    private static final String SELECT_DUE = """
            SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                   lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                   process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                   scope_definition_id, correlation_id, retries, exception_message, due_date,
                   repeat_cycle, end_date, max_iterations, create_time
            FROM process_job
            WHERE job_kind = :kind AND lock_expiration_time IS NULL
            ORDER BY id LIMIT :limit OFFSET :offset
            """;

    private static final String SELECT_EXPIRED = """
            SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                   lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                   process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                   scope_definition_id, correlation_id, retries, exception_message, due_date,
                   repeat_cycle, end_date, max_iterations, create_time
            FROM process_job
            WHERE job_kind = :kind AND lock_expiration_time IS NOT NULL AND lock_expiration_time < :now
            ORDER BY id LIMIT :limit OFFSET :offset
            """;

    @Override
    protected String jobKind() {
        return "JOB";
    }

    @Override
    protected JobEntity newEntity() {
        return new JobEntityImpl();
    }

    @Override
    protected Class<JobEntity> entityType() {
        return JobEntity.class;
    }

    @Override
    protected String lockOwner(JobEntity entity) {
        return entity.getLockOwner();
    }

    @Override
    protected void setLockOwner(JobEntity entity, String lockOwner) {
        entity.setLockOwner(lockOwner);
    }

    @Override
    protected Date lockExpirationTime(JobEntity entity) {
        return entity.getLockExpirationTime();
    }

    @Override
    protected void setLockExpirationTime(JobEntity entity, Date time) {
        entity.setLockExpirationTime(time);
    }

    @Override
    public List<JobEntity> findJobsToExecute(List<String> enabledCategories, Page page) {
        return jdbcClient().sql(SELECT_DUE)
                .param("kind", jobKind())
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    @Override
    public List<JobEntity> findExpiredJobs(List<String> enabledCategories, Page page) {
        return jdbcClient().sql(SELECT_EXPIRED)
                .param("kind", jobKind())
                .param("now", java.sql.Timestamp.from(java.time.Instant.now()))
                .param("limit", page.getMaxResults())
                .param("offset", page.getFirstResult())
                .query(this::cacheOrMap)
                .list();
    }

    // Deliberately no revision check -- matches Flowable's own bytecode-verified dead-node reclaim
    // behavior (ResetExpiredJobsRunnable), which is unconditional by design: a node that died mid-
    // claim can't have a "current" revision to race against.
    @Override
    public void bulkUpdateJobLockWithoutRevisionCheck(List<JobEntity> jobs, String lockOwner, Date lockExpirationTime) {
        for (JobEntity job : jobs) {
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

    // Query-object/native-query surfaces: stubbed to empty, matching this package's convention
    // (e.g. ExecutionDataManagerImpl) -- nothing in this app calls ManagementService.createJobQuery().
    @Override
    public List<Job> findJobsByQueryCriteria(JobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findJobCountByQueryCriteria(JobQueryImpl jobQuery) {
        return 0;
    }
}
