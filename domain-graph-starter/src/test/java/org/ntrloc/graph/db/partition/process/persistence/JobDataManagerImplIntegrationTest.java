package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.Page;
import org.flowable.engine.ManagementService;
import org.flowable.job.service.impl.persistence.entity.DeadLetterJobEntity;
import org.flowable.job.service.impl.persistence.entity.JobEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers AbstractJobDataManager's shared CRUD/finder/optimistic-locking logic via its two most
// illustrative concrete subclasses: JobDataManagerImpl (models lock fields for real, adds its own
// due/expired-job finders) and DeadLetterJobDataManagerImpl (inherits AbstractJobDataManager's
// no-op lock accessors unchanged -- see that class's own class comment on why). Both classes are
// plain `new X()` instances wired straight into Flowable's engine config (JobServiceConfigurator),
// not Spring beans, so they're constructed directly here too -- their behavior only depends on an
// active Flowable command context (Context.getCommandContext()), which
// ManagementService.executeCommand(...) provides, not on anything Spring-injected.
class JobDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final JobDataManagerImpl jobDataManager = new JobDataManagerImpl();
    private final DeadLetterJobDataManagerImpl deadLetterJobDataManager = new DeadLetterJobDataManagerImpl();

    private JobEntity newJob(String executionId, String processInstanceId, String correlationId) {
        JobEntity job = jobDataManager.create();
        job.setJobHandlerType("test-handler");
        job.setJobHandlerConfiguration("test-configuration");
        job.setRetries(3);
        job.setExecutionId(executionId);
        job.setProcessInstanceId(processInstanceId);
        job.setCorrelationId(correlationId);
        job.setCreateTime(new Date());
        return job;
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        JobEntity job = managementService.executeCommand(cc -> jobDataManager.create());
        assertThat(job.getId()).isNull();
    }

    @Test
    void insert_assignsAnIdWhenNoneWasSet() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });
        assertThat(id).isNotBlank();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", "corr-1");
            jobDataManager.insert(job);
            return job.getId();
        });

        // A fresh command, so this can't just be handed back the same cached in-memory object --
        // it has to have actually round-tripped through the database.
        JobEntity found = managementService.executeCommand(cc -> jobDataManager.findById(id));
        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getJobHandlerType()).isEqualTo("test-handler");
        assertThat(found.getJobHandlerConfiguration()).isEqualTo("test-configuration");
        assertThat(found.getRetries()).isEqualTo(3);
        assertThat(found.getExecutionId()).isEqualTo("exec-1");
        assertThat(found.getProcessInstanceId()).isEqualTo("proc-1");
        assertThat(found.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        JobEntity found = managementService.executeCommand(cc -> jobDataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        // ProcessSession's own class comment: Flowable relies on getting the *same* object back
        // from repeated finds within one command, mutating it in place. This is what makes that
        // true -- the second findById is a cache hit, not a second row-to-object mapping.
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        Boolean sameInstance = managementService.executeCommand(cc -> {
            JobEntity first = jobDataManager.findById(id);
            JobEntity second = jobDataManager.findById(id);
            return first == second;
        });
        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            JobEntity job = jobDataManager.findById(id);
            job.setRetries(0);
            return jobDataManager.update(job);
        });

        JobEntity reloaded = managementService.executeCommand(cc -> jobDataManager.findById(id));
        assertThat(reloaded.getRetries()).isEqualTo(0);
        assertThat(reloaded.getRevision()).isEqualTo(2); // inserted at revision 1, one update since
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        // Two independent commands both read the row at revision 1 -- simulating two concurrent
        // callers -- then both try to write. The first wins and bumps it to revision 2; the second
        // is now stale.
        JobEntity firstReader = managementService.executeCommand(cc -> jobDataManager.findById(id));
        JobEntity secondReader = managementService.executeCommand(cc -> jobDataManager.findById(id));

        managementService.executeCommand(cc -> jobDataManager.update(firstReader));

        assertThatThrownBy(() -> managementService.executeCommand(cc -> jobDataManager.update(secondReader)))
                .isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            jobDataManager.delete(id);
            return null;
        });

        JobEntity afterDelete = managementService.executeCommand(cc -> jobDataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Finders ---

    @Test
    void findJobsByExecutionId_isScopedToBothTheExecutionAndThisManagersOwnJobKind() {
        String executionId = "exec-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            jobDataManager.insert(newJob(executionId, "proc-1", null));
            jobDataManager.insert(newJob(executionId, "proc-1", null));
            jobDataManager.insert(newJob("some-other-execution", "proc-1", null));
            // Same execution id, but a DEADLETTER-kind row -- must not show up in the JOB-kind finder.
            DeadLetterJobEntity deadLetter = deadLetterJobDataManager.create();
            deadLetter.setExecutionId(executionId);
            deadLetterJobDataManager.insert(deadLetter);
            return null;
        });

        var found = managementService.executeCommand(cc -> jobDataManager.findJobsByExecutionId(executionId));
        assertThat(found).hasSize(2).allSatisfy(job -> assertThat(job.getExecutionId()).isEqualTo(executionId));
    }

    @Test
    void findJobsByProcessInstanceId_returnsOnlyJobsForThatProcessInstance() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            jobDataManager.insert(newJob("exec-1", processInstanceId, null));
            jobDataManager.insert(newJob("exec-2", "some-other-process", null));
            return null;
        });

        var found = managementService.executeCommand(cc -> jobDataManager.findJobsByProcessInstanceId(processInstanceId));
        assertThat(found).extracting(JobEntity::getProcessInstanceId).containsExactly(processInstanceId);
    }

    @Test
    void findJobByCorrelationId_returnsTheMatchingJob() {
        String correlationId = "corr-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            jobDataManager.insert(newJob("exec-1", "proc-1", correlationId));
            return null;
        });

        JobEntity found = managementService.executeCommand(cc -> jobDataManager.findJobByCorrelationId(correlationId));
        assertThat(found.getCorrelationId()).isEqualTo(correlationId);
    }

    // --- Job-kind transitions (insert()'s ON CONFLICT upsert, delete()'s job_kind scoping) ---

    @Test
    void insertingAnExistingIdUnderADifferentJobKind_upsertsInPlaceRatherThanColliding() {
        // Mirrors Flowable's own move-a-job-to-a-different-kind pattern (DefaultJobManager):
        // insert the same id again through a *different* kind's DataManager. insert()'s own class
        // comment explains why this has to be an upsert, not a plain INSERT -- both kinds share one
        // physical row via the job_kind column, so a second INSERT would collide on the primary key.
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            DeadLetterJobEntity deadLetter = deadLetterJobDataManager.create();
            deadLetter.setId(id);
            deadLetter.setExecutionId("exec-1");
            deadLetterJobDataManager.insert(deadLetter);
            return null;
        });

        // The row now belongs to DEADLETTER -- invisible to the JOB-kind manager, visible to the
        // DEADLETTER-kind one, despite never having been explicitly deleted anywhere.
        JobEntity viaOldKind = managementService.executeCommand(cc -> jobDataManager.findById(id));
        DeadLetterJobEntity viaNewKind = managementService.executeCommand(cc -> deadLetterJobDataManager.findById(id));
        assertThat(viaOldKind).isNull();
        assertThat(viaNewKind).isNotNull();
    }

    @Test
    void delete_isScopedToItsOwnJobKind_soItCannotRemoveARowAlreadyMovedToAnotherKind() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            DeadLetterJobEntity deadLetter = deadLetterJobDataManager.create();
            deadLetter.setId(id);
            deadLetterJobDataManager.insert(deadLetter);
            return null;
        });

        // The old JOB-kind manager's delete() must be a no-op now -- see delete()'s own comment on
        // why (it's scoped to `job_kind = :kind`, which no longer matches this row).
        managementService.executeCommand(cc -> {
            jobDataManager.delete(id);
            return null;
        });

        DeadLetterJobEntity stillThere = managementService.executeCommand(cc -> deadLetterJobDataManager.findById(id));
        assertThat(stillThere).isNotNull();
    }

    // --- JobDataManagerImpl's own additions, built on the same shared query machinery ---

    @Test
    void findJobsToExecute_returnsOnlyUnlockedJobsOfThisKind() {
        String marker = "due-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            JobEntity due = newJob(marker, "proc-1", null);
            jobDataManager.insert(due);
            JobEntity locked = newJob(marker, "proc-1", null);
            locked.setLockOwner("someone-else");
            locked.setLockExpirationTime(new Date(System.currentTimeMillis() + 60_000));
            jobDataManager.insert(locked);
            return null;
        });

        var due = managementService.executeCommand(cc -> jobDataManager.findJobsToExecute(null, new Page(0, 10)));
        assertThat(due).extracting(JobEntity::getExecutionId).containsOnly(marker);
        assertThat(due).hasSize(1);
    }

    @Test
    void findExpiredJobs_returnsOnlyJobsWhoseLockHasAlreadyExpired() {
        String marker = "expiry-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            JobEntity expired = newJob(marker, "proc-1", null);
            expired.setLockOwner("dead-node");
            expired.setLockExpirationTime(new Date(System.currentTimeMillis() - 60_000));
            jobDataManager.insert(expired);
            JobEntity stillValid = newJob(marker, "proc-1", null);
            stillValid.setLockOwner("live-node");
            stillValid.setLockExpirationTime(new Date(System.currentTimeMillis() + 60_000));
            jobDataManager.insert(stillValid);
            return null;
        });

        var expired = managementService.executeCommand(cc -> jobDataManager.findExpiredJobs(null, new Page(0, 10)));
        assertThat(expired).extracting(JobEntity::getExecutionId).containsOnly(marker);
        assertThat(expired).hasSize(1);
    }

    @Test
    void resetExpiredJob_clearsTheLockFields() {
        String id = managementService.executeCommand(cc -> {
            JobEntity job = newJob("exec-1", "proc-1", null);
            job.setLockOwner("dead-node");
            job.setLockExpirationTime(new Date());
            jobDataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            jobDataManager.resetExpiredJob(id);
            return null;
        });

        JobEntity reloaded = managementService.executeCommand(cc -> jobDataManager.findById(id));
        assertThat(reloaded.getLockOwner()).isNull();
        assertThat(reloaded.getLockExpirationTime()).isNull();
    }

    @Test
    void bulkUpdateJobLockWithoutRevisionCheck_locksEveryGivenJob_regardlessOfRevision() {
        var jobs = managementService.executeCommand(cc -> {
            JobEntity a = newJob("exec-1", "proc-1", null);
            jobDataManager.insert(a);
            JobEntity b = newJob("exec-2", "proc-1", null);
            jobDataManager.insert(b);
            return java.util.List.of(a, b);
        });

        Date lockExpiration = new Date(System.currentTimeMillis() + 30_000);
        managementService.executeCommand(cc -> {
            jobDataManager.bulkUpdateJobLockWithoutRevisionCheck(jobs, "acquiring-node", lockExpiration);
            return null;
        });

        for (JobEntity job : jobs) {
            JobEntity reloaded = managementService.executeCommand(cc -> jobDataManager.findById(job.getId()));
            assertThat(reloaded.getLockOwner()).isEqualTo("acquiring-node");
        }
    }

    @Test
    void deleteJobsByExecutionId_removesEveryJobOfThisKindForThatExecution() {
        String executionId = "exec-" + UUID.randomUUID();
        managementService.executeCommand(cc -> {
            jobDataManager.insert(newJob(executionId, "proc-1", null));
            jobDataManager.insert(newJob(executionId, "proc-1", null));
            return null;
        });

        managementService.executeCommand(cc -> {
            jobDataManager.deleteJobsByExecutionId(executionId);
            return null;
        });

        var remaining = managementService.executeCommand(cc -> jobDataManager.findJobsByExecutionId(executionId));
        assertThat(remaining).isEmpty();
    }

    @Test
    void queryObjectSurfaces_areStubbedEmpty_sinceNothingInThisAppUsesThem() {
        assertThat(jobDataManager.findJobsByQueryCriteria(null)).isEmpty();
        assertThat(jobDataManager.findJobCountByQueryCriteria(null)).isZero();
    }

    @Test
    void updateJobTenantIdForDeployment_isANoOpAndDoesNotThrow_tenantsArentModeledInThisApp() {
        jobDataManager.updateJobTenantIdForDeployment("some-deployment", "some-tenant");
    }
}
