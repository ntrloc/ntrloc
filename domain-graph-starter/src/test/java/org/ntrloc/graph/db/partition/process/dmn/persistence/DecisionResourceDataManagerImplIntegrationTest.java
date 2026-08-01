package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.engine.impl.persistence.entity.DmnResourceEntity;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers DecisionResourceDataManagerImpl's CRUD/finder logic directly, same style as
// DecisionDataManagerImplIntegrationTest (this class's own comment: "mirrors
// ResourceDataManagerImpl on the process-engine side exactly"). decision_resource is a shared
// table, so every test gives its own rows a UUID-suffixed deploymentId/name and filters on that,
// never a shared literal.
class DecisionResourceDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final DecisionResourceDataManagerImpl dataManager = new DecisionResourceDataManagerImpl();

    private String insert(String deploymentId, String name, byte[] bytes) {
        return managementService.executeCommand(cc -> {
            DmnResourceEntity entity = dataManager.create();
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
        DmnResourceEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        byte[] bytes = "<definitions/>".getBytes(StandardCharsets.UTF_8);
        String id = insert(UUID.randomUUID().toString(), "decision.dmn", bytes);

        DmnResourceEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getName()).isEqualTo("decision.dmn");
        assertThat(found.getBytes()).isEqualTo(bytes);
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        DmnResourceEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert(UUID.randomUUID().toString(), "decision.dmn", "bytes".getBytes(StandardCharsets.UTF_8));

        Boolean sameInstance = managementService.executeCommand(cc -> {
            DmnResourceEntity first = dataManager.findById(id);
            DmnResourceEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert(UUID.randomUUID().toString(), "original.dmn", "old".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            DmnResourceEntity entity = dataManager.findById(id);
            entity.setName("renamed.dmn");
            entity.setBytes("new".getBytes(StandardCharsets.UTF_8));
            return dataManager.update(entity);
        });

        DmnResourceEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getName()).isEqualTo("renamed.dmn");
        assertThat(reloaded.getBytes()).isEqualTo("new".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void delete_removesTheRow() {
        String id = insert(UUID.randomUUID().toString(), "decision.dmn", "bytes".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        DmnResourceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert(UUID.randomUUID().toString(), "decision.dmn", "bytes".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            DmnResourceEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        DmnResourceEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteResourcesByDeploymentId_removesEveryResourceInThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String id1 = insert(deploymentId, "a.dmn", "a".getBytes(StandardCharsets.UTF_8));
        String id2 = insert(deploymentId, "b.dmn", "b".getBytes(StandardCharsets.UTF_8));

        managementService.executeCommand(cc -> {
            dataManager.deleteResourcesByDeploymentId(deploymentId);
            return null;
        });

        DmnResourceEntity first = managementService.executeCommand(cc -> dataManager.findById(id1));
        DmnResourceEntity second = managementService.executeCommand(cc -> dataManager.findById(id2));
        assertThat(first).isNull();
        assertThat(second).isNull();
    }

    @Test
    void findResourceByDeploymentIdAndResourceName_returnsTheMatchingResource() {
        String deploymentId = UUID.randomUUID().toString();
        String id = insert(deploymentId, "decision.dmn", "bytes".getBytes(StandardCharsets.UTF_8));
        insert(deploymentId, "other.dmn", "other".getBytes(StandardCharsets.UTF_8));

        DmnResourceEntity found = managementService.executeCommand(
                cc -> dataManager.findResourceByDeploymentIdAndResourceName(deploymentId, "decision.dmn"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findResourceByDeploymentIdAndResourceName_forNoMatch_returnsNull() {
        DmnResourceEntity found = managementService.executeCommand(cc -> dataManager
                .findResourceByDeploymentIdAndResourceName(UUID.randomUUID().toString(), "nonexistent.dmn"));
        assertThat(found).isNull();
    }

    @Test
    void findResourcesByDeploymentId_returnsEveryResourceInThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String id1 = insert(deploymentId, "a.dmn", "a".getBytes(StandardCharsets.UTF_8));
        String id2 = insert(deploymentId, "b.dmn", "b".getBytes(StandardCharsets.UTF_8));
        insert(UUID.randomUUID().toString(), "c.dmn", "c".getBytes(StandardCharsets.UTF_8));

        List<DmnResourceEntity> found = managementService.executeCommand(
                cc -> dataManager.findResourcesByDeploymentId(deploymentId));

        assertThat(found).extracting(DmnResourceEntity::getId).containsExactlyInAnyOrder(id1, id2);
    }
}
