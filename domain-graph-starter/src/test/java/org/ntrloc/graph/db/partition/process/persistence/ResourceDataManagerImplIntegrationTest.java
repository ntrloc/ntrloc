package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.persistence.entity.ResourceEntity;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers ResourceDataManagerImpl's CRUD/finder logic directly, same style as
// DecisionResourceDataManagerImplIntegrationTest (this is that class's process-engine-side
// mirror -- identical shape, own table). process_resource is a shared table, so every test gives
// its own rows a UUID-suffixed deploymentId/name and filters on that, never a shared literal.
class ResourceDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final ResourceDataManagerImpl dataManager = new ResourceDataManagerImpl();

    private String insert(String deploymentId, String name, byte[] bytes) {
        return managementService.executeCommand(cc -> {
            ResourceEntity entity = dataManager.create();
            entity.setDeploymentId(deploymentId);
            entity.setName(name);
            entity.setBytes(bytes);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        ResourceEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        byte[] bytes = "<definitions/>".getBytes(StandardCharsets.UTF_8);
        String id = insert(UUID.randomUUID().toString(), "process.bpmn20.xml", bytes);

        ResourceEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getName()).isEqualTo("process.bpmn20.xml");
        assertThat(found.getBytes()).isEqualTo(bytes);
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        ResourceEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert(UUID.randomUUID().toString(), "process.bpmn20.xml", "bytes".getBytes(StandardCharsets.UTF_8));

        Boolean sameInstance = managementService.executeCommand(cc -> {
            ResourceEntity first = dataManager.findById(id);
            ResourceEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert(UUID.randomUUID().toString(), "original.bpmn20.xml", "old".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            ResourceEntity entity = dataManager.findById(id);
            entity.setName("renamed.bpmn20.xml");
            entity.setBytes("new".getBytes(StandardCharsets.UTF_8));
            return dataManager.update(entity);
        });

        ResourceEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getName()).isEqualTo("renamed.bpmn20.xml");
        assertThat(reloaded.getBytes()).isEqualTo("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void delete_removesTheRow() {
        String id = insert(UUID.randomUUID().toString(), "process.bpmn20.xml", "bytes".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        ResourceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert(UUID.randomUUID().toString(), "process.bpmn20.xml", "bytes".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            ResourceEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        ResourceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteResourcesByDeploymentId_removesEveryResourceInThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String id1 = insert(deploymentId, "a.bpmn20.xml", "a".getBytes(StandardCharsets.UTF_8));
        String id2 = insert(deploymentId, "b.bpmn20.xml", "b".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            dataManager.deleteResourcesByDeploymentId(deploymentId);
            return null;
        });

        ResourceEntity first = managementService.executeCommand(cc -> dataManager.findById(id1));
        ResourceEntity second = managementService.executeCommand(cc -> dataManager.findById(id2));
        assertThat(first).isNull();
        assertThat(second).isNull();
    }

    @Test
    void findResourceByDeploymentIdAndResourceName_returnsTheMatchingResource() {
        String deploymentId = UUID.randomUUID().toString();
        String id = insert(deploymentId, "process.bpmn20.xml", "bytes".getBytes(StandardCharsets.UTF_8));
        insert(deploymentId, "other.bpmn20.xml", "other".getBytes(StandardCharsets.UTF_8));

        ResourceEntity found = managementService.executeCommand(
                cc -> dataManager.findResourceByDeploymentIdAndResourceName(deploymentId, "process.bpmn20.xml"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findResourceByDeploymentIdAndResourceName_forNoMatch_returnsNull() {
        ResourceEntity found = managementService.executeCommand(cc -> dataManager
                .findResourceByDeploymentIdAndResourceName(UUID.randomUUID().toString(), "nonexistent.bpmn20.xml"));
        assertThat(found).isNull();
    }

    @Test
    void findResourcesByDeploymentId_returnsEveryResourceInThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String id1 = insert(deploymentId, "a.bpmn20.xml", "a".getBytes(StandardCharsets.UTF_8));
        String id2 = insert(deploymentId, "b.bpmn20.xml", "b".getBytes(StandardCharsets.UTF_8));
        insert(UUID.randomUUID().toString(), "c.bpmn20.xml", "c".getBytes(StandardCharsets.UTF_8));

        List<ResourceEntity> found = managementService.executeCommand(
                cc -> dataManager.findResourcesByDeploymentId(deploymentId));

        assertThat(found).extracting(ResourceEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }
}
