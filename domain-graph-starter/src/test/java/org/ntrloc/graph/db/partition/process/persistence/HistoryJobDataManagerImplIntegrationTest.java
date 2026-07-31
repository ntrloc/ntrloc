package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.common.engine.impl.Page;
import org.flowable.job.service.impl.persistence.entity.HistoryJobEntity;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers HistoryJobDataManagerImpl's CRUD/finder logic, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(), which ProcessSession resolves regardless of which entity
// kind is in play. Async history is never enabled (see ProcessEngineConfig's own comment: no code
// path in this app currently creates a history job), so every row here is manufactured directly
// through the DataManager rather than via any real engine trigger.
//
// findJobsToExecute/findExpiredJobs scan every HISTORY-kind row with no id filter, so -- same
// contamination risk as any shared-table finder against the singleton Postgres container (see
// AbstractIntegrationTest's own comment) -- tests that exercise them only assert that their own
// known ids are present/absent among the results, never that the results are exactly one row.
class HistoryJobDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final HistoryJobDataManagerImpl historyJobDataManager = new HistoryJobDataManagerImpl();

    private HistoryJobEntity newHistoryJob() {
        HistoryJobEntity job = historyJobDataManager.create();
        job.setJobHandlerType("test-handler");
        job.setJobHandlerConfiguration("test-configuration-" + UUID.randomUUID());
        job.setRetries(3);
        job.setCreateTime(new Date());
        return job;
    }

    private String insert() {
        return managementService.executeCommand(cc -> {
            HistoryJobEntity job = newHistoryJob();
            historyJobDataManager.insert(job);
            return job.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        HistoryJobEntity job = historyJobDataManager.create();
        assertThat(job.getId()).isNull();
    }

    @Test
    void insert_assignsAnIdWhenNoneWasSet() {
        String id = insert();
        assertThat(id).isNotNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String id = insert();

        HistoryJobEntity found = managementService.executeCommand(cc -> historyJobDataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getRetries()).isEqualTo(3);
        assertThat(found.getRevision()).isEqualTo(1);
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        HistoryJobEntity found = managementService.executeCommand(
                cc -> historyJobDataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert();

        Boolean sameInstance = managementService.executeCommand(cc -> {
            HistoryJobEntity first = historyJobDataManager.findById(id);
            HistoryJobEntity second = historyJobDataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String id = insert();

        managementService.executeCommand(cc -> {
            HistoryJobEntity job = historyJobDataManager.findById(id);
            job.setRetries(0);
            return historyJobDataManager.update(job);
        });

        HistoryJobEntity reloaded = managementService.executeCommand(cc -> historyJobDataManager.findById(id));
        assertThat(reloaded.getRetries()).isEqualTo(0);
        assertThat(reloaded.getRevision()).isEqualTo(2);
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = insert();

        HistoryJobEntity firstReader = managementService.executeCommand(cc -> historyJobDataManager.findById(id));
        HistoryJobEntity secondReader = managementService.executeCommand(cc -> historyJobDataManager.findById(id));

        managementService.executeCommand(cc -> {
            firstReader.setRetries(1);
            return historyJobDataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setRetries(2);
            return historyJobDataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String id = insert();

        managementService.executeCommand(cc -> {
            historyJobDataManager.delete(id);
            return null;
        });

        HistoryJobEntity afterDelete = managementService.executeCommand(cc -> historyJobDataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert();

        managementService.executeCommand(cc -> {
            HistoryJobEntity job = historyJobDataManager.findById(id);
            historyJobDataManager.delete(job);
            return null;
        });

        HistoryJobEntity afterDelete = managementService.executeCommand(cc -> historyJobDataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Finders ---

    @Test
    void findJobsToExecute_returnsOnlyUnlockedJobs() {
        String unlockedId = insert();
        String lockedId = insert();
        managementService.executeCommand(cc -> {
            historyJobDataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(historyJobDataManager.findById(lockedId)), "some-owner", new Date());
            return null;
        });

        List<HistoryJobEntity> toExecute = managementService.executeCommand(
                cc -> historyJobDataManager.findJobsToExecute(List.of(), new Page(0, 1000)));

        assertThat(toExecute).extracting(HistoryJobEntity::getId).contains(unlockedId).doesNotContain(lockedId);
    }

    @Test
    void findExpiredJobs_returnsOnlyJobsWhoseLockHasAlreadyExpired() {
        String expiredId = insert();
        String notYetExpiredId = insert();
        managementService.executeCommand(cc -> {
            historyJobDataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(historyJobDataManager.findById(expiredId)), "some-owner",
                    Date.from(java.time.Instant.now().minusSeconds(60)));
            historyJobDataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(historyJobDataManager.findById(notYetExpiredId)), "some-owner",
                    Date.from(java.time.Instant.now().plusSeconds(3600)));
            return null;
        });

        List<HistoryJobEntity> expired = managementService.executeCommand(
                cc -> historyJobDataManager.findExpiredJobs(List.of(), new Page(0, 1000)));

        assertThat(expired).extracting(HistoryJobEntity::getId).contains(expiredId).doesNotContain(notYetExpiredId);
    }

    @Test
    void resetExpiredJob_clearsTheLockFields() {
        String id = insert();
        managementService.executeCommand(cc -> {
            historyJobDataManager.bulkUpdateJobLockWithoutRevisionCheck(
                    List.of(historyJobDataManager.findById(id)), "some-owner", new Date());
            return null;
        });

        managementService.executeCommand(cc -> {
            historyJobDataManager.resetExpiredJob(id);
            return null;
        });

        HistoryJobEntity reloaded = managementService.executeCommand(cc -> historyJobDataManager.findById(id));
        assertThat(reloaded.getLockOwner()).isNull();
        assertThat(reloaded.getLockExpirationTime()).isNull();
    }

    @Test
    void findJobsByExecutionIdAndByProcessInstanceId_areAlwaysEmpty_historyJobsHaveNoExecutionConcept() {
        List<HistoryJobEntity> byExecution = managementService.executeCommand(
                cc -> historyJobDataManager.findJobsByExecutionId("some-execution"));
        List<HistoryJobEntity> byProcessInstance = managementService.executeCommand(
                cc -> historyJobDataManager.findJobsByProcessInstanceId("some-process-instance"));

        assertThat(byExecution).isEmpty();
        assertThat(byProcessInstance).isEmpty();
    }

    @Test
    void updateJobTenantIdForDeployment_isANoOpAndDoesNotThrow_tenantsArentModeledInThisApp() {
        managementService.executeCommand(cc -> {
            historyJobDataManager.updateJobTenantIdForDeployment(UUID.randomUUID().toString(), "some-tenant");
            return null;
        });
    }

    @Test
    void queryObjectSurfaces_areStubbedEmpty_sinceNothingInThisAppUsesThem() {
        List<org.flowable.job.api.HistoryJob> results = managementService.executeCommand(
                cc -> historyJobDataManager.findHistoryJobsByQueryCriteria(null));
        Long count = managementService.executeCommand(
                cc -> historyJobDataManager.findHistoryJobCountByQueryCriteria(null));

        assertThat(results).isEmpty();
        assertThat(count).isZero();
    }
}
