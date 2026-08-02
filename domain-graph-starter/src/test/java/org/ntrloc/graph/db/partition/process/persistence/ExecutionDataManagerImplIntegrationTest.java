package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.flowable.engine.impl.persistence.entity.ExecutionEntityImpl;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers ExecutionDataManagerImpl's CRUD/finder logic directly, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). process_execution is a shared table with no per-row
// test-scoping column, so every test gives its own rows a UUID-suffixed processInstanceId/
// parentId and filters on that, never a shared literal.
class ExecutionDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final ExecutionDataManagerImpl dataManager = new ExecutionDataManagerImpl();

    private ExecutionEntityImpl newExecution(String processInstanceId, String parentId, String rootProcessInstanceId) {
        ExecutionEntityImpl entity = (ExecutionEntityImpl) dataManager.create();
        entity.setProcessInstanceId(processInstanceId);
        entity.setParentId(parentId);
        entity.setRootProcessInstanceId(rootProcessInstanceId);
        entity.setActive(true);
        return entity;
    }

    private String insert(String processInstanceId, String parentId, String rootProcessInstanceId) {
        return managementService.executeCommand(cc -> {
            var entity = newExecution(processInstanceId, parentId, rootProcessInstanceId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- insert()'s double-insert guard ---

    @Test
    void insertingTheSameEntityTwiceInOneCommand_isANoOpTheSecondTime() {
        String processInstanceId = "proc-" + UUID.randomUUID();

        String id = managementService.executeCommand(cc -> {
            var entity = newExecution(processInstanceId, null, null);
            dataManager.insert(entity);
            dataManager.insert(entity);
            return entity.getId();
        });

        List<ExecutionEntity> found = managementService.executeCommand(
                cc -> dataManager.findExecutionsByProcessInstanceId(processInstanceId));
        assertThat(found).extracting(ExecutionEntity::getId).containsExactly(id);
    }

    // --- update() optimistic locking ---

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = insert("proc-" + UUID.randomUUID(), null, null);

        ExecutionEntity firstReader = managementService.executeCommand(cc -> dataManager.findById(id));
        ExecutionEntity secondReader = managementService.executeCommand(cc -> dataManager.findById(id));

        managementService.executeCommand(cc -> {
            firstReader.setBusinessKey("first-write");
            return dataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setBusinessKey("second-write");
            return dataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    // --- Finders ---

    @Test
    void findSubProcessInstanceBySuperExecutionId_returnsTheMatchingExecution() {
        String superExecutionId = "super-" + UUID.randomUUID();
        String id = managementService.executeCommand(cc -> {
            var entity = newExecution("proc-" + UUID.randomUUID(), null, null);
            entity.setSuperExecutionId(superExecutionId);
            dataManager.insert(entity);
            return entity.getId();
        });

        ExecutionEntity found = managementService.executeCommand(
                cc -> dataManager.findSubProcessInstanceBySuperExecutionId(superExecutionId));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findChildExecutionsByParentExecutionId_returnsOnlyDirectChildren() {
        String parentId = insert("proc-" + UUID.randomUUID(), null, null);
        String childId = insert("proc-" + UUID.randomUUID(), parentId, null);
        insert("proc-" + UUID.randomUUID(), null, null);

        List<ExecutionEntity> found = managementService.executeCommand(
                cc -> dataManager.findChildExecutionsByParentExecutionId(parentId));

        assertThat(found).extracting(ExecutionEntity::getId).containsExactly(childId);
    }

    @Test
    void findChildExecutionsByProcessInstanceId_excludesTheRootExecutionItself() {
        String processInstanceId = insert("root-marker-" + UUID.randomUUID(), null, null);
        managementService.executeCommand(cc -> {
            ExecutionEntity root = dataManager.findById(processInstanceId);
            root.setProcessInstanceId(processInstanceId);
            return dataManager.update(root);
        });
        String childId = insert(processInstanceId, processInstanceId, processInstanceId);

        List<ExecutionEntity> found = managementService.executeCommand(
                cc -> dataManager.findChildExecutionsByProcessInstanceId(processInstanceId));

        assertThat(found).extracting(ExecutionEntity::getId).containsExactly(childId);
    }

    @Test
    void findExecutionsByParentExecutionAndActivityIds_isScopedToBoth() {
        String parentId = insert("proc-" + UUID.randomUUID(), null, null);
        String matchingId = managementService.executeCommand(cc -> {
            var entity = newExecution("proc-" + UUID.randomUUID(), parentId, null);
            entity.setActivityId("theActivity");
            dataManager.insert(entity);
            return entity.getId();
        });
        managementService.executeCommand(cc -> {
            var entity = newExecution("proc-" + UUID.randomUUID(), parentId, null);
            entity.setActivityId("aDifferentActivity");
            dataManager.insert(entity);
            return entity.getId();
        });

        List<ExecutionEntity> found = managementService.executeCommand(cc -> dataManager
                .findExecutionsByParentExecutionAndActivityIds(parentId, List.of("theActivity")));

        assertThat(found).extracting(ExecutionEntity::getId).containsExactly(matchingId);
    }

    @Test
    void findExecutionsByRootProcessInstanceId_returnsEveryExecutionInThatTree() {
        String rootProcessInstanceId = "root-" + UUID.randomUUID();
        String rootId = insert(rootProcessInstanceId, null, rootProcessInstanceId);
        String childId = insert(rootProcessInstanceId, rootId, rootProcessInstanceId);

        List<ExecutionEntity> found = managementService.executeCommand(
                cc -> dataManager.findExecutionsByRootProcessInstanceId(rootProcessInstanceId));

        assertThat(found).extracting(ExecutionEntity::getId).containsExactlyInAnyOrder(rootId, childId);
    }

    @Test
    void findExecutionsByProcessInstanceId_returnsEveryExecutionForThatInstance() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id1 = insert(processInstanceId, null, null);
        String id2 = insert(processInstanceId, id1, null);

        List<ExecutionEntity> found = managementService.executeCommand(
                cc -> dataManager.findExecutionsByProcessInstanceId(processInstanceId));

        assertThat(found).extracting(ExecutionEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void findInactiveExecutions_seesAnInPlaceMutationFromEarlierInTheSameCommand_becauseItFlushesFirst() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String activityId = "theActivity";
        String id = managementService.executeCommand(cc -> {
            var entity = newExecution(processInstanceId, null, null);
            entity.setActivityId(activityId);
            dataManager.insert(entity);
            return entity.getId();
        });

        // Mutates the cached entity in place (no explicit update() call) and immediately queries by
        // the same is_active flag within the same command -- exactly the ParallelGatewayActivityBehavior
        // pattern described in the class comment. Without session().flush() inside the finder, this
        // execution wouldn't show up: the SELECT would still see is_active=true from before the mutation.
        Collection<ExecutionEntity> byProcessInstance = managementService.executeCommand(cc -> {
            ExecutionEntity found = dataManager.findById(id);
            found.setActive(false);
            return dataManager.findInactiveExecutionsByProcessInstanceId(processInstanceId);
        });
        Collection<ExecutionEntity> byActivityAndProcessInstance = managementService.executeCommand(cc -> {
            ExecutionEntity found = dataManager.findById(id);
            found.setActive(false);
            return dataManager.findInactiveExecutionsByActivityIdAndProcessInstanceId(activityId, processInstanceId);
        });

        assertThat(byProcessInstance).extracting(ExecutionEntity::getId).containsExactly(id);
        assertThat(byActivityAndProcessInstance).extracting(ExecutionEntity::getId).containsExactly(id);
    }

    @Test
    void findProcessInstanceIdsByProcessDefinitionId_returnsOnlyRootExecutions() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String rootId = managementService.executeCommand(cc -> {
            var entity = newExecution(null, null, null);
            entity.setProcessDefinitionId(processDefinitionId);
            dataManager.insert(entity);
            entity.setProcessInstanceId(entity.getId());
            dataManager.update(entity);
            return entity.getId();
        });
        managementService.executeCommand(cc -> {
            var child = newExecution(rootId, rootId, rootId);
            child.setProcessDefinitionId(processDefinitionId);
            dataManager.insert(child);
            return child.getId();
        });

        List<String> found = managementService.executeCommand(
                cc -> dataManager.findProcessInstanceIdsByProcessDefinitionId(processDefinitionId));

        assertThat(found).containsExactly(rootId);
    }

    @Test
    void countActiveExecutionsByParentId_countsOnlyActiveChildren() {
        String parentId = insert("proc-" + UUID.randomUUID(), null, null);
        managementService.executeCommand(cc -> {
            var active = newExecution("proc-" + UUID.randomUUID(), parentId, null);
            active.setActive(true);
            dataManager.insert(active);
            return null;
        });
        managementService.executeCommand(cc -> {
            var inactive = newExecution("proc-" + UUID.randomUUID(), parentId, null);
            inactive.setActive(false);
            dataManager.insert(inactive);
            return null;
        });

        Long count = managementService.executeCommand(cc -> dataManager.countActiveExecutionsByParentId(parentId));

        assertThat(count).isEqualTo(1);
    }

    // --- Query-object/native-query surfaces: all stubbed, matching this class's own comment on
    //     covering exactly the single-linear-execution case ---

    @Test
    void queryObjectAndNativeQuerySurfaces_areStubbedEmpty() {
        Long executionCount = managementService.executeCommand(cc -> dataManager.findExecutionCountByQueryCriteria(null));
        List<ExecutionEntity> executions = managementService.executeCommand(cc -> dataManager.findExecutionsByQueryCriteria(null));
        Long processInstanceCount = managementService.executeCommand(cc -> dataManager.findProcessInstanceCountByQueryCriteria(null));
        List<ProcessInstance> processInstances = managementService.executeCommand(cc -> dataManager.findProcessInstanceByQueryCriteria(null));
        List<ProcessInstance> processInstancesWithVariables = managementService.executeCommand(
                cc -> dataManager.findProcessInstanceAndVariablesByQueryCriteria(null));
        List<Execution> byNativeQuery = managementService.executeCommand(
                cc -> dataManager.findExecutionsByNativeQuery(java.util.Map.of()));
        List<ProcessInstance> processInstancesByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findProcessInstanceByNativeQuery(java.util.Map.of()));
        Long executionCountByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findExecutionCountByNativeQuery(java.util.Map.of()));

        assertThat(executionCount).isZero();
        assertThat(executions).isEmpty();
        assertThat(processInstanceCount).isZero();
        assertThat(processInstances).isEmpty();
        assertThat(processInstancesWithVariables).isEmpty();
        assertThat(byNativeQuery).isEmpty();
        assertThat(processInstancesByNativeQuery).isEmpty();
        assertThat(executionCountByNativeQuery).isZero();
    }

    @Test
    void tenantAndLockingSurfaces_areAllNoOpsAndDoNotThrow_notModeledInThisProof() {
        managementService.executeCommand(cc -> {
            dataManager.updateExecutionTenantIdForDeployment(UUID.randomUUID().toString(), "some-tenant");
            dataManager.updateAllExecutionRelatedEntityCountFlags(true);
            dataManager.updateProcessInstanceLockTime("proc-1", new java.util.Date(), "owner", new java.util.Date());
            dataManager.clearProcessInstanceLockTime("proc-1");
            dataManager.clearAllProcessInstanceLockTimes("owner");
            return null;
        });
    }
}
