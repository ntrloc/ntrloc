package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionInfoEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers ProcessDefinitionInfoDataManagerImpl's CRUD, same style as JobDataManagerImplIntegrationTest
// -- run inside a Flowable command via ManagementService.executeCommand(). Nothing in this app ever
// creates a process-definition-info override in practice (see the class's own comment), so every
// row here is manufactured directly through the DataManager.
class ProcessDefinitionInfoDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final ProcessDefinitionInfoDataManagerImpl dataManager = new ProcessDefinitionInfoDataManagerImpl();

    private String insert(String processDefinitionId, String infoJsonId) {
        return managementService.executeCommand(cc -> {
            ProcessDefinitionInfoEntity entity = dataManager.create();
            entity.setProcessDefinitionId(processDefinitionId);
            entity.setInfoJsonId(infoJsonId);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        ProcessDefinitionInfoEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String id = insert(processDefinitionId, "info-json-1");

        ProcessDefinitionInfoEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getProcessDefinitionId()).isEqualTo(processDefinitionId);
        assertThat(found.getInfoJsonId()).isEqualTo("info-json-1");
        assertThat(found.getRevision()).isEqualTo(1);
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        ProcessDefinitionInfoEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert("procdef-" + UUID.randomUUID(), "info-json-1");

        Boolean sameInstance = managementService.executeCommand(cc -> {
            ProcessDefinitionInfoEntity first = dataManager.findById(id);
            ProcessDefinitionInfoEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void findProcessDefinitionInfoByProcessDefinitionId_returnsTheMatchingEntity() {
        String processDefinitionId = "procdef-" + UUID.randomUUID();
        String id = insert(processDefinitionId, "info-json-1");

        ProcessDefinitionInfoEntity found = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionInfoByProcessDefinitionId(processDefinitionId));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findProcessDefinitionInfoByProcessDefinitionId_forAnUnknownProcessDefinition_returnsNull() {
        ProcessDefinitionInfoEntity found = managementService.executeCommand(cc -> dataManager
                .findProcessDefinitionInfoByProcessDefinitionId(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String id = insert("procdef-" + UUID.randomUUID(), "info-json-1");

        managementService.executeCommand(cc -> {
            ProcessDefinitionInfoEntity entity = dataManager.findById(id);
            entity.setInfoJsonId("info-json-2");
            return dataManager.update(entity);
        });

        ProcessDefinitionInfoEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getInfoJsonId()).isEqualTo("info-json-2");
        assertThat(reloaded.getRevision()).isEqualTo(2);
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = insert("procdef-" + UUID.randomUUID(), "info-json-1");

        ProcessDefinitionInfoEntity firstReader = managementService.executeCommand(cc -> dataManager.findById(id));
        ProcessDefinitionInfoEntity secondReader = managementService.executeCommand(cc -> dataManager.findById(id));

        managementService.executeCommand(cc -> {
            firstReader.setInfoJsonId("first-write");
            return dataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setInfoJsonId("second-write");
            return dataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String id = insert("procdef-" + UUID.randomUUID(), "info-json-1");

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        ProcessDefinitionInfoEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert("procdef-" + UUID.randomUUID(), "info-json-1");

        managementService.executeCommand(cc -> {
            ProcessDefinitionInfoEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        ProcessDefinitionInfoEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }
}
