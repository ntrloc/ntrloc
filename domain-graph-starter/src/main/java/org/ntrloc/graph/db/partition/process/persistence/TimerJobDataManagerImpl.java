package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.Page;
import org.flowable.job.api.Job;
import org.flowable.job.service.impl.TimerJobQueryImpl;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntity;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntityImpl;
import org.flowable.job.service.impl.persistence.entity.data.TimerJobDataManager;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// Backs timer jobs (ACT_RU_TIMER_JOB) with job_kind = 'TIMER' rows in process_job -- same
// due-date-driven acquire/expire shape as JobDataManagerImpl, see its class comment.
// java.util.Date is unavoidable here: overrides Flowable's own Date-based entity API.
@SuppressWarnings("java:S2143")
public class TimerJobDataManagerImpl extends AbstractJobDataManager<TimerJobEntity> implements TimerJobDataManager {

    @Override
    protected String jobKind() {
        return "TIMER";
    }

    @Override
    protected TimerJobEntity newEntity() {
        return new TimerJobEntityImpl();
    }

    @Override
    protected Class<TimerJobEntity> entityType() {
        return TimerJobEntity.class;
    }

    @Override
    protected String lockOwner(TimerJobEntity entity) {
        return entity.getLockOwner();
    }

    @Override
    protected void setLockOwner(TimerJobEntity entity, String lockOwner) {
        entity.setLockOwner(lockOwner);
    }

    @Override
    protected Date lockExpirationTime(TimerJobEntity entity) {
        return entity.getLockExpirationTime();
    }

    @Override
    protected void setLockExpirationTime(TimerJobEntity entity, Date time) {
        entity.setLockExpirationTime(time);
    }

    @Override
    public List<TimerJobEntity> findJobsToExecute(List<String> enabledCategories, Page page) {
        return jdbcClient().sql("""
                SELECT id, revision, category, job_type, job_handler_type, job_handler_configuration,
                       lock_owner, lock_expiration_time, is_exclusive, execution_id, process_instance_id,
                       process_definition_id, element_id, element_name, scope_id, sub_scope_id, scope_type,
                       scope_definition_id, correlation_id, retries, exception_message, due_date,
                       repeat_cycle, end_date, max_iterations, create_time
                FROM process_job
                WHERE job_kind = :kind AND lock_expiration_time IS NULL AND due_date <= :now
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
    public List<TimerJobEntity> findExpiredJobs(List<String> enabledCategories, Page page) {
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
    public void bulkUpdateJobLockWithoutRevisionCheck(List<TimerJobEntity> jobs, String lockOwner, Date lockExpirationTime) {
        for (TimerJobEntity job : jobs) {
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
    public void bulkDeleteWithoutRevision(List<TimerJobEntity> jobs) {
        List<String> ids = jobs.stream().map(TimerJobEntity::getId).toList();
        if (ids.isEmpty()) {
            return;
        }
        jdbcClient().sql("DELETE FROM process_job WHERE id IN (:ids)").param("ids", ids).update();
        for (TimerJobEntity job : jobs) {
            session().evict(TimerJobEntity.class, job.getId());
        }
    }

    // Tenant/key-variant and query-object/native-query surfaces: stubbed, matching this package's
    // convention -- tenants aren't modeled, and nothing calls ManagementService.createTimerJobQuery().
    @Override
    public List<TimerJobEntity> findJobsByTypeAndProcessDefinitionId(String jobHandlerType, String processDefinitionId) {
        return new ArrayList<>();
    }

    @Override
    public List<TimerJobEntity> findJobsByTypeAndProcessDefinitionKeyNoTenantId(String jobHandlerType, String processDefinitionKey) {
        return new ArrayList<>();
    }

    @Override
    public List<TimerJobEntity> findJobsByTypeAndProcessDefinitionKeyAndTenantId(String jobHandlerType, String processDefinitionKey, String tenantId) {
        return new ArrayList<>();
    }

    @Override
    public List<TimerJobEntity> findJobsByScopeIdAndSubScopeId(String scopeId, String subScopeId) {
        return new ArrayList<>();
    }

    @Override
    public List<Job> findJobsByQueryCriteria(TimerJobQueryImpl jobQuery) {
        return new ArrayList<>();
    }

    @Override
    public long findJobCountByQueryCriteria(TimerJobQueryImpl jobQuery) {
        return 0;
    }
}
