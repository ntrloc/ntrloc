package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.Page;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.ExternalWorkerJob;
import org.flowable.job.service.impl.persistence.entity.ExternalWorkerJobEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers ExternalWorkerJobDataManagerImpl's own overrides (jobKind/newEntity/entityType/lock
// accessors) and its real finder/mutator surface (findJobsToExecute, findExpiredJobs,
// bulkUpdateJobLockWithoutRevisionCheck, resetExpiredJob, deleteJobsByExecutionId) -- the shared
// CRUD it inherits from AbstractJobDataManager is already covered via
// JobDataManagerImplIntegrationTest, so this class's own tests focus on what's specific to it.
// Nothing in this app dispatches external worker tasks (see the class's own comment), so every
// row here is manufactured directly through the DataManager.
class ExternalWorkerJobDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final ExternalWorkerJobDataManagerImpl dataManager = new ExternalWorkerJobDataManagerImpl();

    private ExternalWorkerJobEntity newJob() {
        ExternalWorkerJobEntity job = dataManager.create();
        job.setJobHandlerType("test-handler");
        job.setRetries(3);
        job.setCreateTime(new Date());
        return job;
    }

    private String insert() {
        return managementService.executeCommand(cc -> {
            ExternalWorkerJobEntity job = newJob();
            dataManager.insert(job);
            return job.getId();
        });
    }

    @Test
    void create_returnsAnExternalWorkerJobEntity() {
        assertThat(dataManager.create()).isInstanceOf(ExternalWorkerJobEntity.class);
    }

    @Test
    void insertThenFindById_roundTripsTheLockFields() {
        Date expiration = new Date();
        String id = managementService.executeCommand(cc -> {
            ExternalWorkerJobEntity job = newJob();
            job.setLockOwner("worker-1");
            job.setLockExpirationTime(expiration);
            dataManager.insert(job);
            return job.getId();
        });

        ExternalWorkerJobEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getLockOwner()).isEqualTo("worker-1");
        assertThat(found.getLockExpirationTime()).isEqualTo(expiration);
    }

    @Test
    void findJobsToExecute_returnsOnlyUnlockedJobsOfThisKind() {
        String unlockedId = insert();
        String lockedId = insert();
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(lockedId)), "worker-1", new Date());
            return null;
        });

        List<ExternalWorkerJobEntity> toExecute = managementService.executeCommand(
                cc -> dataManager.findJobsToExecute(List.of(), new Page(0, 1000)));

        assertThat(toExecute).extracting(ExternalWorkerJobEntity::getId).contains(unlockedId).doesNotContain(lockedId);
    }

    @Test
    void findExpiredJobs_returnsOnlyJobsWhoseLockHasAlreadyExpired() {
        String expiredId = insert();
        String notYetExpiredId = insert();
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(expiredId)), "worker-1",
                    Date.from(java.time.Instant.now().minusSeconds(60)));
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(notYetExpiredId)), "worker-1",
                    Date.from(java.time.Instant.now().plusSeconds(3600)));
            return null;
        });

        List<ExternalWorkerJobEntity> expired = managementService.executeCommand(
                cc -> dataManager.findExpiredJobs(List.of(), new Page(0, 1000)));

        assertThat(expired).extracting(ExternalWorkerJobEntity::getId).contains(expiredId).doesNotContain(notYetExpiredId);
    }

    @Test
    void resetExpiredJob_clearsTheLockFields() {
        String id = insert();
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(id)), "worker-1", new Date());
            return null;
        });

        managementService.executeCommand(cc -> {
            dataManager.resetExpiredJob(id);
            return null;
        });

        ExternalWorkerJobEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getLockOwner()).isNull();
        assertThat(reloaded.getLockExpirationTime()).isNull();
    }

    @Test
    void deleteJobsByExecutionId_removesEveryJobOfThisKindForThatExecution() {
        String executionId = "exec-" + UUID.randomUUID();
        String id = managementService.executeCommand(cc -> {
            ExternalWorkerJobEntity job = newJob();
            job.setExecutionId(executionId);
            dataManager.insert(job);
            return job.getId();
        });

        managementService.executeCommand(cc -> {
            dataManager.deleteJobsByExecutionId(executionId);
            return null;
        });

        ExternalWorkerJobEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Worker-specific/query-object surfaces: all stubbed, matching this package's convention
    //     -- nothing in this app dispatches external worker tasks yet ---

    @Test
    void workerSpecificAndQueryObjectSurfaces_areStubbedEmpty() {
        List<ExternalWorkerJobEntity> byQueryBuilder = managementService.executeCommand(
                cc -> dataManager.findExternalJobsToExecute(null, 5));
        List<ExternalWorkerJobEntity> byScopeAndSubScope = managementService.executeCommand(
                cc -> dataManager.findJobsByScopeIdAndSubScopeId("scope-1", "sub-scope-1"));
        List<ExternalWorkerJobEntity> byWorkerId = managementService.executeCommand(
                cc -> dataManager.findJobsByWorkerId("worker-1"));
        List<ExternalWorkerJobEntity> byWorkerIdAndTenantId = managementService.executeCommand(
                cc -> dataManager.findJobsByWorkerIdAndTenantId("worker-1", "tenant-1"));
        List<ExternalWorkerJob> byQueryCriteria = managementService.executeCommand(
                cc -> dataManager.findJobsByQueryCriteria(null));
        Long countByQueryCriteria = managementService.executeCommand(
                cc -> dataManager.findJobCountByQueryCriteria(null));

        assertThat(byQueryBuilder).isEmpty();
        assertThat(byScopeAndSubScope).isEmpty();
        assertThat(byWorkerId).isEmpty();
        assertThat(byWorkerIdAndTenantId).isEmpty();
        assertThat(byQueryCriteria).isEmpty();
        assertThat(countByQueryCriteria).isZero();
    }
}
