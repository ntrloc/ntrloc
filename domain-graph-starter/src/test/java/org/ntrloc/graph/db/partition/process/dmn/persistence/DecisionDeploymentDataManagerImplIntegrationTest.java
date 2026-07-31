package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.engine.impl.persistence.entity.DmnDeploymentEntity;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers DecisionDeploymentDataManagerImpl's CRUD/finder logic directly, same style as
// DecisionDataManagerImplIntegrationTest. decision_deployment is a shared table, so every test
// gives its own rows a UUID-suffixed name and filters on that, never a shared literal.
class DecisionDeploymentDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    private final DecisionDeploymentDataManagerImpl dataManager = new DecisionDeploymentDataManagerImpl();

    private String insert(String name, Date deploymentTime) {
        return managementService.executeCommand(cc -> {
            DmnDeploymentEntity entity = dataManager.create();
            entity.setName(name);
            entity.setDeploymentTime(deploymentTime);
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        DmnDeploymentEntity entity = dataManager.create();
        assertThat(entity.getId()).isNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String name = "deployment-" + UUID.randomUUID();
        Date time = new Date();
        String id = insert(name, time);

        DmnDeploymentEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getName()).isEqualTo(name);
        assertThat(found.getDeploymentTime()).isEqualTo(time);
    }

    @Test
    void insertWithNoDeploymentTime_persistsANullTime() {
        String id = insert("deployment-" + UUID.randomUUID(), null);

        DmnDeploymentEntity found = managementService.executeCommand(cc -> dataManager.findById(id));

        assertThat(found.getDeploymentTime()).isNull();
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        DmnDeploymentEntity found = managementService.executeCommand(
                cc -> dataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert("deployment-" + UUID.randomUUID(), new Date());

        Boolean sameInstance = managementService.executeCommand(cc -> {
            DmnDeploymentEntity first = dataManager.findById(id);
            DmnDeploymentEntity second = dataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert("original-" + UUID.randomUUID(), new Date());
        String renamed = "renamed-" + UUID.randomUUID();

        managementService.executeCommand(cc -> {
            DmnDeploymentEntity entity = dataManager.findById(id);
            entity.setName(renamed);
            entity.setDeploymentTime(null);
            return dataManager.update(entity);
        });

        DmnDeploymentEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getName()).isEqualTo(renamed);
        assertThat(reloaded.getDeploymentTime()).isNull();
    }

    @Test
    void delete_removesTheRow() {
        String id = insert("deployment-" + UUID.randomUUID(), new Date());

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        DmnDeploymentEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert("deployment-" + UUID.randomUUID(), new Date());

        managementService.executeCommand(cc -> {
            DmnDeploymentEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        DmnDeploymentEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void getDeploymentResourceNames_returnsEveryResourceNameForThatDeployment() {
        String deploymentId = insert("deployment-" + UUID.randomUUID(), new Date());
        var resourceDataManager = new DecisionResourceDataManagerImpl();
        managementService.executeCommand(cc -> {
            var resource = resourceDataManager.create();
            resource.setDeploymentId(deploymentId);
            resource.setName("decision.dmn");
            resource.setBytes("<definitions/>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            resourceDataManager.insert(resource);
            return null;
        });

        List<String> names = managementService.executeCommand(
                cc -> dataManager.getDeploymentResourceNames(deploymentId));

        assertThat(names).containsExactly("decision.dmn");
    }

    @Test
    void getDeploymentResourceNames_forADeploymentWithNoResources_returnsEmpty() {
        String deploymentId = insert("deployment-" + UUID.randomUUID(), new Date());

        List<String> names = managementService.executeCommand(
                cc -> dataManager.getDeploymentResourceNames(deploymentId));

        assertThat(names).isEmpty();
    }

    // --- Query-object/native-query surfaces: all stubbed, matching this class's own comment --
    //     nothing in this app lists DMN deployments directly ---

    @Test
    void queryObjectAndNativeQuerySurfaces_areStubbedEmpty() {
        Long count = managementService.executeCommand(cc -> dataManager.findDeploymentCountByQueryCriteria(null));
        List<DmnDeployment> byQuery = managementService.executeCommand(cc -> dataManager.findDeploymentsByQueryCriteria(null));
        List<DmnDeployment> byNativeQuery = managementService.executeCommand(
                cc -> dataManager.findDeploymentsByNativeQuery(java.util.Map.of()));
        Long countByNativeQuery = managementService.executeCommand(
                cc -> dataManager.findDeploymentCountByNativeQuery(java.util.Map.of()));

        assertThat(count).isZero();
        assertThat(byQuery).isEmpty();
        assertThat(byNativeQuery).isEmpty();
        assertThat(countByNativeQuery).isZero();
    }
}
