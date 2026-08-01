package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.ManagementService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.Task;
import org.flowable.task.service.impl.persistence.entity.TaskEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers TaskDataManagerImpl's remaining uncovered surface: findTasksByProcessInstanceId, the
// scope/subScope and native-query stubs, findTaskCountByQueryCriteria, and whereClause()'s
// assignee/candidateUser/candidateGroups OR-group logic (see that method's own comment on why the
// OR-group semantics are load-bearing, not incidental). findTasksByQueryCriteria's plain taskId()
// path and the base CRUD are already covered elsewhere (TaskAdminController's own tests). Uses the
// real TaskService.createTaskQuery() fluent API for the OR-group tests -- same reasoning as
// DecisionDataManagerImplIntegrationTest's use of DmnRepositoryService: that's the actual
// production entry point for findTasksByQueryCriteria/findTaskCountByQueryCriteria, and it runs
// its own command internally so no executeCommand() wrapping is needed for those tests.
// process_task is a shared table, so every test gives its own rows a UUID-suffixed
// processInstanceId/assignee and filters on that, never a shared literal.
class TaskDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    @Autowired
    private TaskService taskService;

    private final TaskDataManagerImpl dataManager = new TaskDataManagerImpl();

    private String insert(String processInstanceId, String assignee) {
        return managementService.executeCommand(cc -> {
            TaskEntity entity = dataManager.create();
            entity.setProcessInstanceId(processInstanceId);
            entity.setAssignee(assignee);
            entity.setName("Some Task");
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Finders ---

    @Test
    void findTasksByProcessInstanceId_returnsEveryTaskForThatInstance() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id1 = insert(processInstanceId, null);
        String id2 = insert(processInstanceId, null);
        insert("proc-" + UUID.randomUUID(), null);

        List<TaskEntity> found = managementService.executeCommand(
                cc -> dataManager.findTasksByProcessInstanceId(processInstanceId));

        assertThat(found).extracting(TaskEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void findTaskCountByQueryCriteria_matchesTheListSize() {
        String assignee = "assignee-" + UUID.randomUUID();
        insert("proc-" + UUID.randomUUID(), assignee);
        insert("proc-" + UUID.randomUUID(), assignee);

        long count = taskService.createTaskQuery().taskAssignee(assignee).count();

        assertThat(count).isEqualTo(2);
    }

    @Test
    void findTasksWithRelatedEntitiesByQueryCriteria_delegatesToFindTasksByQueryCriteria() {
        String id = insert("proc-" + UUID.randomUUID(), null);

        List<Task> found = managementService.executeCommand(cc -> dataManager
                .findTasksWithRelatedEntitiesByQueryCriteria(
                        (org.flowable.task.service.impl.TaskQueryImpl) taskService.createTaskQuery().taskId(id)));

        assertThat(found).extracting(Task::getId).containsExactly(id);
    }

    @Test
    void queryByAssignee_returnsOnlyThatAssigneesTasks() {
        String assignee = "assignee-" + UUID.randomUUID();
        insert("proc-" + UUID.randomUUID(), assignee);
        insert("proc-" + UUID.randomUUID(), "someone-else");

        List<Task> found = taskService.createTaskQuery().taskAssignee(assignee).list();

        assertThat(found).hasSize(1);
    }

    @Test
    void queryByCandidateUser_returnsOnlyTasksWithThatCandidate() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String taskId = insert(processInstanceId, null);
        String candidateUser = "user-" + UUID.randomUUID();
        var identityLinkDataManager = new IdentityLinkDataManagerImpl();
        managementService.executeCommand(cc -> {
            var link = identityLinkDataManager.create();
            link.setTaskId(taskId);
            link.setType("candidate");
            link.setUserId(candidateUser);
            identityLinkDataManager.insert(link);
            return null;
        });

        List<Task> found = taskService.createTaskQuery().taskCandidateUser(candidateUser).list();

        assertThat(found).extracting(Task::getId).containsExactly(taskId);
    }

    @Test
    void queryByCandidateGroupIn_returnsOnlyTasksWithAnyOfThoseCandidateGroups() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String taskId = insert(processInstanceId, null);
        String candidateGroup = "group-" + UUID.randomUUID();
        var identityLinkDataManager = new IdentityLinkDataManagerImpl();
        managementService.executeCommand(cc -> {
            var link = identityLinkDataManager.create();
            link.setTaskId(taskId);
            link.setType("candidate");
            link.setGroupId(candidateGroup);
            identityLinkDataManager.insert(link);
            return null;
        });

        List<Task> found = taskService.createTaskQuery().taskCandidateGroupIn(List.of(candidateGroup)).list();

        assertThat(found).extracting(Task::getId).containsExactly(taskId);
    }

    @Test
    void queryWithOrGroup_matchesAnyAlternative_notAllOfThem() {
        // Regression coverage for the exact bug whereClause()'s own comment describes: an earlier
        // version ANDed every field on the shared or-sub-object together instead of ORing them,
        // so "assignee OR candidateUser" matched nothing. A task that satisfies ONLY the assignee
        // branch must still be found.
        String assignee = "assignee-" + UUID.randomUUID();
        String taskId = insert("proc-" + UUID.randomUUID(), assignee);
        String unrelatedCandidateUser = "user-" + UUID.randomUUID();

        List<Task> found = taskService.createTaskQuery()
                .or()
                .taskAssignee(assignee)
                .taskCandidateUser(unrelatedCandidateUser)
                .endOr()
                .list();

        assertThat(found).extracting(Task::getId).containsExactly(taskId);
    }

    // --- Scope/native-query surfaces: all stubbed, matching this class's own comment ---

    @Test
    void scopeAndNativeQuerySurfaces_areStubbedEmpty() {
        List<TaskEntity> byScope = managementService.executeCommand(
                cc -> dataManager.findTasksByScopeIdAndScopeType("scope-1", "scope-type-1"));
        List<TaskEntity> bySubScope = managementService.executeCommand(
                cc -> dataManager.findTasksBySubScopeIdAndScopeType("sub-scope-1", "scope-type-1"));
        List<Task> byNativeQuery = managementService.executeCommand(
                cc -> dataManager.findTasksByNativeQuery(java.util.Map.of()));
        Long countByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findTaskCountByNativeQuery(java.util.Map.of()));
        List<Task> byParentTaskId = managementService.executeCommand(
                cc -> dataManager.findTasksByParentTaskId("parent-task-1"));

        assertThat(byScope).isEmpty();
        assertThat(bySubScope).isEmpty();
        assertThat(byNativeQuery).isEmpty();
        assertThat(countByNativeQuery).isZero();
        assertThat(byParentTaskId).isEmpty();
    }

    @Test
    void tenantAndEntityCountSurfaces_areNoOpsAndDoNotThrow_notModeledInThisProof() {
        managementService.executeCommand(cc -> {
            dataManager.updateTaskTenantIdForDeployment(UUID.randomUUID().toString(), "some-tenant");
            dataManager.updateAllTaskRelatedEntityCountFlags(true);
            return null;
        });
    }
}
