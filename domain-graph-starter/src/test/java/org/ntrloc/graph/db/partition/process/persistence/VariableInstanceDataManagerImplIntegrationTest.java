package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.ManagementService;
import org.flowable.variable.api.persistence.entity.VariableInstance;
import org.flowable.variable.service.impl.InternalVariableInstanceQueryImpl;
import org.flowable.variable.service.impl.persistence.entity.VariableInstanceEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers VariableInstanceDataManagerImpl's CRUD/finder logic directly, same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(). findVariablesInstancesByQuery/findVariablesInstanceByQuery
// are exercised via InternalVariableInstanceQueryImpl's own fluent list()/singleResult() -- the
// real production entry point Flowable's own execution/variable-scope loading code calls (see the
// class's own comment on why this path is load-bearing, not stubbed like the other query
// surfaces). process_variable is a shared table, so every test gives its own rows a UUID-suffixed
// executionId and filters on that, never a shared literal.
class VariableInstanceDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final VariableInstanceDataManagerImpl dataManager = new VariableInstanceDataManagerImpl();

    private String insertString(String executionId, String processInstanceId, String name, String value) {
        return managementService.executeCommand(cc -> {
            VariableInstanceEntity entity = dataManager.create();
            entity.setExecutionId(executionId);
            entity.setProcessInstanceId(processInstanceId);
            entity.setName(name);
            entity.setTypeName("string");
            entity.setTextValue(value);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        VariableInstanceEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity_andResolvesItsVariableType() {
        String executionId = "exec-" + UUID.randomUUID();
        String id = insertString(executionId, executionId, "greeting", "hello");

        VariableInstanceEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getExecutionId()).isEqualTo(executionId);
        assertThat(found.getName()).isEqualTo("greeting");
        assertThat(found.getTextValue()).isEqualTo("hello");
        assertThat(found.getType()).isNotNull();
    }

    @Test
    void insertThenFindById_forADoubleTypedVariable_mapsTheDoubleValue() {
        String id = managementService.executeCommand(cc -> {
            VariableInstanceEntity entity = dataManager.create();
            entity.setExecutionId("exec-" + UUID.randomUUID());
            entity.setName("price");
            entity.setTypeName("double");
            entity.setDoubleValue(19.99);
            dataManager.insert(entity);
            return entity.getId();
        });

        VariableInstanceEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getDoubleValue()).isEqualTo(19.99);
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        VariableInstanceEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insertString("exec-" + UUID.randomUUID(), null, "greeting", "hello");

        Boolean sameInstance = managementService.executeCommand(cc -> {
            VariableInstanceEntity first = dataManager.findById(id);
            VariableInstanceEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insertString("exec-" + UUID.randomUUID(), null, "greeting", "hello");

        managementService.executeCommand(cc -> {
            VariableInstanceEntity entity = dataManager.findById(id);
            entity.setTextValue("goodbye");
            return dataManager.update(entity);
        });

        VariableInstanceEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getTextValue()).isEqualTo("goodbye");
    }

    @Test
    void delete_removesTheRow() {
        String id = insertString("exec-" + UUID.randomUUID(), null, "greeting", "hello");

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        VariableInstanceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insertString("exec-" + UUID.randomUUID(), null, "greeting", "hello");

        managementService.executeCommand(cc -> {
            VariableInstanceEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        VariableInstanceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteVariablesByExecutionId_removesEveryVariableForThatExecution() {
        String executionId = "exec-" + UUID.randomUUID();
        String id = insertString(executionId, null, "greeting", "hello");

        managementService.executeCommand(cc -> {
            dataManager.deleteVariablesByExecutionId(executionId);
            return null;
        });

        VariableInstanceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Internal query (the real production entry point Flowable's own execution/variable-scope
    //     loading code calls -- see class comment) ---

    @Test
    void internalQuery_byExecutionId_returnsOnlyThatExecutionsVariables() {
        String executionId = "exec-" + UUID.randomUUID();
        String id1 = insertString(executionId, null, "a", "1");
        String id2 = insertString(executionId, null, "b", "2");
        insertString("exec-" + UUID.randomUUID(), null, "c", "3");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .executionId(executionId).list());

        assertThat(found).extracting(VariableInstanceEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void internalQuery_byExecutionIds_returnsVariablesForAnyOfThem() {
        String executionId1 = "exec-" + UUID.randomUUID();
        String executionId2 = "exec-" + UUID.randomUUID();
        String id1 = insertString(executionId1, null, "a", "1");
        String id2 = insertString(executionId2, null, "b", "2");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .executionIds(List.of(executionId1, executionId2)).list());

        assertThat(found).extracting(VariableInstanceEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void internalQuery_byProcessInstanceId_returnsOnlyThoseVariables() {
        String processInstanceId = "proc-" + UUID.randomUUID();
        String id = insertString("exec-" + UUID.randomUUID(), processInstanceId, "a", "1");
        insertString("exec-" + UUID.randomUUID(), "proc-" + UUID.randomUUID(), "b", "2");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .processInstanceId(processInstanceId).list());

        assertThat(found).extracting(VariableInstanceEntity::getId).containsExactly(id);
    }

    @Test
    void internalQuery_byName_returnsOnlyExactMatches() {
        String executionId = "exec-" + UUID.randomUUID();
        String name = "unique-name-" + UUID.randomUUID();
        String id = insertString(executionId, null, name, "1");
        insertString(executionId, null, "a-different-name", "2");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .executionId(executionId).name(name).list());

        assertThat(found).extracting(VariableInstanceEntity::getId).containsExactly(id);
    }

    @Test
    void internalQuery_byNames_returnsVariablesMatchingAnyOfThem() {
        String executionId = "exec-" + UUID.randomUUID();
        String id1 = insertString(executionId, null, "alpha", "1");
        String id2 = insertString(executionId, null, "beta", "2");
        insertString(executionId, null, "gamma", "3");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .executionId(executionId).names(List.of("alpha", "beta")).list());

        assertThat(found).extracting(VariableInstanceEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void internalQuery_byId_returnsASingleResult() {
        String id = insertString("exec-" + UUID.randomUUID(), null, "a", "1");

        VariableInstanceEntity found = managementService.executeCommand(
                cc -> new InternalVariableInstanceQueryImpl(dataManager).id(id).singleResult());

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void internalQuery_withATaskIdCriterion_excludesEverything_taskScopingIsNotModeledOnThisTable() {
        String executionId = "exec-" + UUID.randomUUID();
        insertString(executionId, null, "a", "1");

        List<VariableInstanceEntity> found = managementService.executeCommand(cc -> new InternalVariableInstanceQueryImpl(dataManager)
                .executionId(executionId).taskId("some-task").list());

        assertThat(found).isEmpty();
    }

    // --- Query-object/native-query surfaces: all stubbed, matching this class's own comment ---

    @Test
    void queryObjectAndNativeQuerySurfaces_areStubbedEmpty() {
        Long count = managementService.executeCommand(cc -> dataManager.findVariableInstanceCountByQueryCriteria(null));
        List<VariableInstance> byQuery = managementService.executeCommand(cc -> dataManager.findVariableInstancesByQueryCriteria(null));
        List<VariableInstance> byNativeQuery = managementService.executeCommand(
                cc -> dataManager.findVariableInstancesByNativeQuery(java.util.Map.of()));
        Long countByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findVariableInstanceCountByNativeQuery(java.util.Map.of()));

        assertThat(count).isZero();
        assertThat(byQuery).isEmpty();
        assertThat(byNativeQuery).isEmpty();
        assertThat(countByNativeQuery).isZero();
    }

    @Test
    void taskAndScopeLevelDeletes_areAllNoOpsAndDoNotThrow_notModeledInThisProof() {
        managementService.executeCommand(cc -> {
            dataManager.deleteVariablesByTaskId("task-1");
            dataManager.deleteByScopeIdAndScopeType("scope-1", "scope-type-1");
            dataManager.deleteByScopeIdAndScopeTypes("scope-1", List.of("scope-type-1"));
            dataManager.deleteBySubScopeIdAndScopeTypes("sub-scope-1", List.of("scope-type-1"));
            return null;
        });
    }
}
