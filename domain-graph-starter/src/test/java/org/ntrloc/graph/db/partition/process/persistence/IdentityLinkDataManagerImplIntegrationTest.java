package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.ManagementService;
import org.flowable.identitylink.api.history.HistoricIdentityLink;
import org.flowable.identitylink.service.impl.persistence.entity.IdentityLinkEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Covers IdentityLinkDataManagerImpl's CRUD/finder logic directly, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). process_task_identitylink is a shared table with no
// per-row test-scoping column, so every test gives its own rows a UUID-suffixed taskId/
// processInstanceId and filters on that, never a shared literal.
class IdentityLinkDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final IdentityLinkDataManagerImpl dataManager = new IdentityLinkDataManagerImpl();

    private String insert(String taskId, String processInstanceId, String type, String userId, String groupId) {
        return managementService.executeCommand(cc -> {
            IdentityLinkEntity entity = dataManager.create();
            entity.setTaskId(taskId);
            entity.setProcessInstanceId(processInstanceId);
            entity.setType(type);
            entity.setUserId(userId);
            entity.setGroupId(groupId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        IdentityLinkEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String taskId = "task-" + UUID.randomUUID();
        String id = insert(taskId, null, "candidate", "alice", null);

        IdentityLinkEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getTaskId()).isEqualTo(taskId);
        assertThat(found.getType()).isEqualTo("candidate");
        assertThat(found.getUserId()).isEqualTo("alice");
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        IdentityLinkEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert("task-" + UUID.randomUUID(), null, "candidate", "alice", null);

        Boolean sameInstance = managementService.executeCommand(cc -> {
            IdentityLinkEntity first = dataManager.findById(id);
            IdentityLinkEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert("task-" + UUID.randomUUID(), null, "candidate", "alice", null);

        managementService.executeCommand(cc -> {
            IdentityLinkEntity entity = dataManager.findById(id);
            entity.setUserId("bob");
            return dataManager.update(entity);
        });

        IdentityLinkEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getUserId()).isEqualTo("bob");
    }

    @Test
    void delete_removesTheRow() {
        String id = insert("task-" + UUID.randomUUID(), null, "candidate", "alice", null);

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        IdentityLinkEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert("task-" + UUID.randomUUID(), null, "candidate", "alice", null);

        managementService.executeCommand(cc -> {
            IdentityLinkEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        IdentityLinkEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void createIdentityLinkFromHistoricIdentityLink_copiesEveryField() {
        HistoricIdentityLink historic = mock(HistoricIdentityLink.class);
        when(historic.getTaskId()).thenReturn("task-1");
        when(historic.getType()).thenReturn("candidate");
        when(historic.getUserId()).thenReturn("alice");
        when(historic.getGroupId()).thenReturn(null);
        when(historic.getProcessInstanceId()).thenReturn("proc-1");

        IdentityLinkEntity entity = dataManager.createIdentityLinkFromHistoricIdentityLink(historic);

        assertThat(entity.getTaskId()).isEqualTo("task-1");
        assertThat(entity.getType()).isEqualTo("candidate");
        assertThat(entity.getUserId()).isEqualTo("alice");
        assertThat(entity.getGroupId()).isNull();
        assertThat(entity.getProcessInstanceId()).isEqualTo("proc-1");
    }

    // --- Finders ---

    @Test
    void findIdentityLinksByTaskId_returnsEveryLinkForThatTask() {
        String taskId = "task-" + UUID.randomUUID();
        String id1 = insert(taskId, null, "candidate", "alice", null);
        String id2 = insert(taskId, null, "candidate", "bob", null);
        insert("task-" + UUID.randomUUID(), null, "candidate", "carol", null);

        List<IdentityLinkEntity> found = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksByTaskId(taskId));

        assertThat(found).extracting(IdentityLinkEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void findIdentityLinkByTaskUserGroupAndType_filtersOnWhicheverCriteriaAreNonNull() {
        String taskId = "task-" + UUID.randomUUID();
        String aliceCandidateId = insert(taskId, null, "candidate", "alice", null);
        insert(taskId, null, "candidate", "bob", null);
        String aliceAssigneeId = insert(taskId, null, "assignee", "alice", null);
        String groupLinkId = insert(taskId, null, "candidate", null, "some-group");

        List<IdentityLinkEntity> byTaskOnly = managementService.executeCommand(
                cc -> dataManager.findIdentityLinkByTaskUserGroupAndType(taskId, null, null, null));
        List<IdentityLinkEntity> byTaskAndUser = managementService.executeCommand(
                cc -> dataManager.findIdentityLinkByTaskUserGroupAndType(taskId, "alice", null, null));
        List<IdentityLinkEntity> byTaskAndGroup = managementService.executeCommand(
                cc -> dataManager.findIdentityLinkByTaskUserGroupAndType(taskId, null, "some-group", null));
        List<IdentityLinkEntity> byTaskUserAndType = managementService.executeCommand(
                cc -> dataManager.findIdentityLinkByTaskUserGroupAndType(taskId, "alice", null, "candidate"));

        assertThat(byTaskOnly).hasSize(4);
        assertThat(byTaskAndUser).extracting(IdentityLinkEntity::getId)
                .containsExactlyInAnyOrder(aliceCandidateId, aliceAssigneeId);
        assertThat(byTaskAndGroup).extracting(IdentityLinkEntity::getId).containsExactly(groupLinkId);
        assertThat(byTaskUserAndType).extracting(IdentityLinkEntity::getId).containsExactly(aliceCandidateId);
    }

    @Test
    void deleteIdentityLinksByTaskId_removesEveryLinkForThatTask() {
        String taskId = "task-" + UUID.randomUUID();
        String id = insert(taskId, null, "candidate", "alice", null);

        managementService.executeCommand(cc -> {
            dataManager.deleteIdentityLinksByTaskId(taskId);
            return null;
        });

        IdentityLinkEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteIdentityLinksByProcessInstanceId_removesEveryParticipantLinkForThatInstance() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id = insert(null, processInstanceId, "participant", "alice", null);

        managementService.executeCommand(cc -> {
            dataManager.deleteIdentityLinksByProcessInstanceId(processInstanceId);
            return null;
        });

        IdentityLinkEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Scope/process-definition-level surfaces: all stubbed, matching TaskDataManagerImpl's own
    //     precedent -- this app has no CMMN usage or process-definition-level candidate tracking ---

    @Test
    void scopeAndProcessDefinitionLevelFinders_areAllStubbedEmpty() {
        List<IdentityLinkEntity> byProcessInstance = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksByProcessInstanceId("proc-1"));
        List<IdentityLinkEntity> byProcessDefinition = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksByProcessDefinitionId("procdef-1"));
        List<IdentityLinkEntity> byScopeIdAndType = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksByScopeIdAndType("scope-1", "scope-type-1"));
        List<IdentityLinkEntity> bySubScopeIdAndType = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksBySubScopeIdAndType("sub-scope-1", "scope-type-1"));
        List<IdentityLinkEntity> byScopeDefinitionIdAndType = managementService.executeCommand(
                cc -> dataManager.findIdentityLinksByScopeDefinitionIdAndType("scope-def-1", "scope-type-1"));
        List<IdentityLinkEntity> byProcessInstanceUserGroupAndType = managementService.executeCommand(cc -> dataManager
                .findIdentityLinkByProcessInstanceUserGroupAndType("proc-1", "alice", null, "candidate"));
        List<IdentityLinkEntity> byProcessDefinitionUserAndGroup = managementService.executeCommand(cc -> dataManager
                .findIdentityLinkByProcessDefinitionUserAndGroup("procdef-1", "alice", null));
        List<IdentityLinkEntity> byScopeIdScopeTypeUserGroupAndType = managementService.executeCommand(cc -> dataManager
                .findIdentityLinkByScopeIdScopeTypeUserGroupAndType("scope-1", "scope-type-1", "alice", null, "candidate"));
        List<IdentityLinkEntity> byScopeDefinitionScopeTypeUserAndGroup = managementService.executeCommand(cc -> dataManager
                .findIdentityLinkByScopeDefinitionScopeTypeUserAndGroup("scope-def-1", "scope-type-1", "alice", null));

        assertThat(byProcessInstance).isEmpty();
        assertThat(byProcessDefinition).isEmpty();
        assertThat(byScopeIdAndType).isEmpty();
        assertThat(bySubScopeIdAndType).isEmpty();
        assertThat(byScopeDefinitionIdAndType).isEmpty();
        assertThat(byProcessInstanceUserGroupAndType).isEmpty();
        assertThat(byProcessDefinitionUserAndGroup).isEmpty();
        assertThat(byScopeIdScopeTypeUserGroupAndType).isEmpty();
        assertThat(byScopeDefinitionScopeTypeUserAndGroup).isEmpty();
    }

    @Test
    void scopeAndProcessDefinitionLevelMutators_areAllNoOpsAndDoNotThrow() {
        managementService.executeCommand(cc -> {
            dataManager.deleteIdentityLinksByProcDef("procdef-1");
            dataManager.deleteIdentityLinksByScopeIdAndScopeType("scope-1", "scope-type-1");
            dataManager.deleteIdentityLinksByScopeDefinitionIdAndScopeType("scope-def-1", "scope-type-1");
            dataManager.bulkDeleteIdentityLinksForScopeIdsAndScopeType(List.of("scope-1", "scope-2"), "scope-type-1");
            return null;
        });
    }
}
