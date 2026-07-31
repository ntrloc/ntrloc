package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnHistoricDecisionExecution;
import org.flowable.dmn.engine.impl.persistence.entity.HistoricDecisionExecutionEntity;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers HistoricDecisionExecutionDataManagerImpl's CRUD, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). Every DMN Task evaluation writes one of these rows for real
// (see the class's own comment), but this test manufactures rows directly through the
// DataManager rather than by evaluating a real decision table, for the same reason
// DecisionDataManagerImplIntegrationTest does: it's the DataManager's own CRUD/finder logic under
// test here, not the DMN engine's audit-trail wiring.
class HistoricDecisionExecutionDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final HistoricDecisionExecutionDataManagerImpl dataManager = new HistoricDecisionExecutionDataManagerImpl();

    private HistoricDecisionExecutionEntity newExecution(String decisionDefinitionId, String deploymentId) {
        HistoricDecisionExecutionEntity entity = dataManager.create();
        entity.setDecisionDefinitionId(decisionDefinitionId);
        entity.setDeploymentId(deploymentId);
        entity.setStartTime(new Date());
        entity.setEndTime(new Date());
        entity.setInstanceId("instance-" + UUID.randomUUID());
        entity.setExecutionId("execution-" + UUID.randomUUID());
        entity.setActivityId("decisionTask");
        entity.setScopeType("bpmn");
        entity.setFailed(false);
        entity.setExecutionJson("{}");
        return entity;
    }

    private String insert(String decisionDefinitionId, String deploymentId) {
        return managementService.executeCommand(cc -> {
            HistoricDecisionExecutionEntity entity = newExecution(decisionDefinitionId, deploymentId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        HistoricDecisionExecutionEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insert_assignsAnIdWhenNoneWasSet() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());
        assertThat(id).isNotNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String decisionDefinitionId = "decdef-" + UUID.randomUUID();
        String id = insert(decisionDefinitionId, "deployment-" + UUID.randomUUID());

        HistoricDecisionExecutionEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getDecisionDefinitionId()).isEqualTo(decisionDefinitionId);
        assertThat(found.getActivityId()).isEqualTo("decisionTask");
        assertThat(found.isFailed()).isFalse();
        assertThat(found.getExecutionJson()).isEqualTo("{}");
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        HistoricDecisionExecutionEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());

        Boolean sameInstance = managementService.executeCommand(cc -> {
            HistoricDecisionExecutionEntity first = dataManager.findById(id);
            HistoricDecisionExecutionEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());

        managementService.executeCommand(cc -> {
            HistoricDecisionExecutionEntity entity = dataManager.findById(id);
            entity.setFailed(true);
            entity.setExecutionJson("{\"result\":\"failed\"}");
            return dataManager.update(entity);
        });

        HistoricDecisionExecutionEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.isFailed()).isTrue();
        assertThat(reloaded.getExecutionJson()).isEqualTo("{\"result\":\"failed\"}");
    }

    @Test
    void delete_removesTheRow() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        HistoricDecisionExecutionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());

        managementService.executeCommand(cc -> {
            HistoricDecisionExecutionEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        HistoricDecisionExecutionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteHistoricDecisionExecutionsByDeploymentId_removesEveryExecutionInThatDeployment() {
        String deploymentId = "deployment-" + UUID.randomUUID();
        String id1 = insert("decdef-1", deploymentId);
        String id2 = insert("decdef-2", deploymentId);

        managementService.executeCommand(cc -> {
            dataManager.deleteHistoricDecisionExecutionsByDeploymentId(deploymentId);
            return null;
        });

        HistoricDecisionExecutionEntity first = managementService.executeCommand(cc -> dataManager.findById(id1));
        HistoricDecisionExecutionEntity second = managementService.executeCommand(cc -> dataManager.findById(id2));
        assertThat(first).isNull();
        assertThat(second).isNull();
    }

    // --- Query/native-query surfaces and bulk-delete-by-query: all stubbed, matching this
    //     package's convention -- nothing in this app's admin UI surfaces this history yet ---

    @Test
    void queryAndNativeQuerySurfaces_areStubbedEmpty() {
        List<DmnHistoricDecisionExecution> byQuery = managementService.executeCommand(
                cc -> dataManager.findHistoricDecisionExecutionsByQueryCriteria(null));
        Long countByQuery = managementService.executeCommand(
                cc -> dataManager.findHistoricDecisionExecutionCountByQueryCriteria(null));
        List<DmnHistoricDecisionExecution> byNativeQuery = managementService.executeCommand(
                cc -> dataManager.findHistoricDecisionExecutionsByNativeQuery(Map.of()));
        Long countByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findHistoricDecisionExecutionCountByNativeQuery(Map.of()));

        assertThat(byQuery).isEmpty();
        assertThat(countByQuery).isZero();
        assertThat(byNativeQuery).isEmpty();
        assertThat(countByNativeQuery).isZero();
    }

    @Test
    void bulkDeleteSurfaces_areNoOpsAndDoNotThrow_nothingInThisAppIssuesThemYet() {
        String id = insert("decdef-" + UUID.randomUUID(), "deployment-" + UUID.randomUUID());

        managementService.executeCommand(cc -> {
            dataManager.delete((org.flowable.dmn.engine.impl.HistoricDecisionExecutionQueryImpl) null);
            dataManager.bulkDeleteHistoricDecisionExecutionsByInstanceIdsAndScopeType(List.of("instance-1"), "bpmn");
            return null;
        });

        HistoricDecisionExecutionEntity stillPresent = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(stillPresent).isNotNull();
    }
}
