package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.impl.Page;
import org.flowable.engine.ManagementService;
import org.flowable.job.api.Job;
import org.flowable.job.service.impl.persistence.entity.TimerJobEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Covers TimerJobDataManagerImpl's own overrides and its real finder/mutator surface -- the shared
// CRUD it inherits from AbstractJobDataManager is already covered via
// JobDataManagerImplIntegrationTest, so this class's own tests focus on what's specific to it:
// findJobsToExecute's added due-date filter, and bulkDeleteWithoutRevision (not present on
// ExternalWorkerJobDataManagerImpl).
class TimerJobDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final TimerJobDataManagerImpl dataManager = new TimerJobDataManagerImpl();

    private String insert(Date dueDate) {
        return managementService.executeCommand(cc -> {
            TimerJobEntity job = dataManager.create();
            job.setJobHandlerType("test-handler");
            job.setRetries(3);
            job.setCreateTime(new Date());
            job.setDuedate(dueDate);
            dataManager.insert(job);
            return job.getId();
        });
    }

    @Test
    void create_returnsATimerJobEntity() {
        assertThat(dataManager.create()).isInstanceOf(TimerJobEntity.class);
    }

    @Test
    void findJobsToExecute_returnsOnlyUnlockedJobsWhoseDueDateHasArrived() {
        String dueId = insert(Date.from(java.time.Instant.now().minusSeconds(60)));
        String notYetDueId = insert(Date.from(java.time.Instant.now().plusSeconds(3600)));
        String lockedId = insert(Date.from(java.time.Instant.now().minusSeconds(60)));
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(lockedId)), "owner-1", new Date());
            return null;
        });

        List<TimerJobEntity> toExecute = managementService.executeCommand(
                cc -> dataManager.findJobsToExecute(List.of(), new Page(0, 1000)));

        assertThat(toExecute).extracting(TimerJobEntity::getId)
                .contains(dueId).doesNotContain(notYetDueId, lockedId);
    }

    @Test
    void findExpiredJobs_returnsOnlyJobsWhoseLockHasAlreadyExpired() {
        String expiredId = insert(new Date());
        String notYetExpiredId = insert(new Date());
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(expiredId)), "owner-1",
                    Date.from(java.time.Instant.now().minusSeconds(60)));
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(notYetExpiredId)), "owner-1",
                    Date.from(java.time.Instant.now().plusSeconds(3600)));
            return null;
        });

        List<TimerJobEntity> expired = managementService.executeCommand(
                cc -> dataManager.findExpiredJobs(List.of(), new Page(0, 1000)));

        assertThat(expired).extracting(TimerJobEntity::getId).contains(expiredId).doesNotContain(notYetExpiredId);
    }

    @Test
    void resetExpiredJob_clearsTheLockFields() {
        String id = insert(new Date());
        managementService.executeCommand(cc -> {
            dataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(dataManager.findById(id)), "owner-1", new Date());
            return null;
        });

        managementService.executeCommand(cc -> {
            dataManager.resetExpiredJob(id);
            return null;
        });

        TimerJobEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getLockOwner()).isNull();
        assertThat(reloaded.getLockExpirationTime()).isNull();
    }

    @Test
    void bulkDeleteWithoutRevision_removesEveryGivenJob() {
        String id1 = insert(new Date());
        String id2 = insert(new Date());

        managementService.executeCommand(cc -> {
            dataManager.bulkDeleteWithoutRevision(List.of(dataManager.findById(id1), dataManager.findById(id2)));
            return null;
        });

        TimerJobEntity first = managementService.executeCommand(cc -> dataManager.findById(id1));
        TimerJobEntity second = managementService.executeCommand(cc -> dataManager.findById(id2));
        assertThat(first).isNull();
        assertThat(second).isNull();
    }

    @Test
    void bulkDeleteWithoutRevision_forAnEmptyList_isANoOpAndDoesNotThrow() {
        managementService.executeCommand(cc -> {
            dataManager.bulkDeleteWithoutRevision(List.of());
            return null;
        });
    }

    // --- Tenant/key-variant and query-object surfaces: all stubbed, matching this package's
    //     convention -- see class comment ---

    @Test
    void tenantAndKeyVariantAndQueryObjectSurfaces_areStubbedEmpty() {
        List<TimerJobEntity> byTypeAndProcessDefinitionId = managementService.executeCommand(
                cc -> dataManager.findJobsByTypeAndProcessDefinitionId("type-1", "procdef-1"));
        List<TimerJobEntity> byTypeAndProcessDefinitionKey = managementService.executeCommand(
                cc -> dataManager.findJobsByTypeAndProcessDefinitionKeyNoTenantId("type-1", "key-1"));
        List<TimerJobEntity> byTypeAndProcessDefinitionKeyAndTenant = managementService.executeCommand(
                cc -> dataManager.findJobsByTypeAndProcessDefinitionKeyAndTenantId("type-1", "key-1", "tenant-1"));
        List<TimerJobEntity> byScopeAndSubScope = managementService.executeCommand(
                cc -> dataManager.findJobsByScopeIdAndSubScopeId("scope-1", "sub-scope-1"));
        List<Job> byQueryCriteria = managementService.executeCommand(cc -> dataManager.findJobsByQueryCriteria(null));
        Long countByQueryCriteria = managementService.executeCommand(cc -> dataManager.findJobCountByQueryCriteria(null));

        assertThat(byTypeAndProcessDefinitionId).isEmpty();
        assertThat(byTypeAndProcessDefinitionKey).isEmpty();
        assertThat(byTypeAndProcessDefinitionKeyAndTenant).isEmpty();
        assertThat(byScopeAndSubScope).isEmpty();
        assertThat(byQueryCriteria).isEmpty();
        assertThat(countByQueryCriteria).isZero();
    }
}
