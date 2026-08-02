package org.ntrloc.graph.db.partition.process.persistence;

import org.flowable.common.engine.api.FlowableOptimisticLockingException;
import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Covers ProcessDefinitionDataManagerImpl's CRUD/finder logic directly (same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand()), plus findProcessDefinitionsByQueryCriteria/
// findProcessDefinitionCountByQueryCriteria via the real RepositoryService query DSL rather than
// constructing ProcessDefinitionQueryImpl by hand -- see DecisionDataManagerImplIntegrationTest's
// class comment for why that's the actual production entry point for those two methods.
//
// create()/findById()/insert()/findLatestProcessDefinitionByKey()/most of the query-criteria
// filters are already exercised elsewhere (real process deployment fixtures) -- this test targets
// specifically what jacoco showed as still uncovered: update()'s revision-checked write,
// delete(), the *AndTenantId/derived/parentDeployment delegate and stub methods, and the
// query-criteria filters (ids/keyLike/name/nameLike/version comparisons) nothing else happened to
// exercise. process_definition is a shared table, so every test gives its own rows a
// UUID-suffixed key/deploymentId and filters on that, never a shared literal.
class ProcessDefinitionDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    @Autowired
    private RepositoryService repositoryService;

    private final ProcessDefinitionDataManagerImpl dataManager = new ProcessDefinitionDataManagerImpl();

    private String insert(String deploymentId, String key, String name, int version) {
        return managementService.executeCommand(cc -> {
            ProcessDefinitionEntity entity = dataManager.create();
            entity.setDeploymentId(deploymentId);
            entity.setKey(key);
            entity.setName(name);
            entity.setVersion(version);
            entity.setResourceName(key + ".bpmn20.xml");
            dataManager.insert(entity);
            return entity.getId();
        });
    }

    // --- update() / delete() ---

    @Test
    void update_persistsChangesAndIncrementsRevision() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Original Name", 1);

        managementService.executeCommand(cc -> {
            ProcessDefinitionEntity entity = dataManager.findById(id);
            entity.setName("Renamed");
            return dataManager.update(entity);
        });

        ProcessDefinitionEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getName()).isEqualTo("Renamed");
        assertThat(reloaded.getRevision()).isEqualTo(2);
    }

    @Test
    void update_withAStaleRevision_throwsOptimisticLockingException() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Original Name", 1);

        ProcessDefinitionEntity firstReader = managementService.executeCommand(cc -> dataManager.findById(id));
        ProcessDefinitionEntity secondReader = managementService.executeCommand(cc -> dataManager.findById(id));

        managementService.executeCommand(cc -> {
            firstReader.setName("First Write");
            return dataManager.update(firstReader);
        });

        assertThatThrownBy(() -> managementService.executeCommand(cc -> {
            secondReader.setName("Second Write");
            return dataManager.update(secondReader);
        })).isInstanceOf(FlowableOptimisticLockingException.class);
    }

    @Test
    void delete_removesTheRow() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Process", 1);

        managementService.executeCommand(cc -> {
            dataManager.delete(id);
            return null;
        });

        ProcessDefinitionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Process", 1);

        managementService.executeCommand(cc -> {
            ProcessDefinitionEntity entity = dataManager.findById(id);
            dataManager.delete(entity);
            return null;
        });

        ProcessDefinitionEntity afterDelete = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Key/version/deployment-based finders ---

    @Test
    void findLatestProcessDefinitionByKeyAndTenantId_delegatesToFindLatestProcessDefinitionByKey() {
        String key = "key-" + UUID.randomUUID();
        String latestId = insert(UUID.randomUUID().toString(), key, "Latest", 2);
        insert(UUID.randomUUID().toString(), key, "Older", 1);

        ProcessDefinitionEntity found = managementService.executeCommand(
                cc -> dataManager.findLatestProcessDefinitionByKeyAndTenantId(key, "ignored-tenant"));

        assertThat(found.getId()).isEqualTo(latestId);
    }

    @Test
    void derivedProcessDefinitionFinders_areStubbedNull_derivationIsntModeledInThisApp() {
        ProcessDefinitionEntity byKey = managementService.executeCommand(
                cc -> dataManager.findLatestDerivedProcessDefinitionByKey("some-key"));
        ProcessDefinitionEntity byKeyAndTenant = managementService.executeCommand(
                cc -> dataManager.findLatestDerivedProcessDefinitionByKeyAndTenantId("some-key", "some-tenant"));

        assertThat(byKey).isNull();
        assertThat(byKeyAndTenant).isNull();
    }

    @Test
    void findProcessDefinitionByDeploymentAndKey_returnsTheMatchingDefinition() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        String id = insert(deploymentId, key, "Some Process", 1);

        ProcessDefinitionEntity found = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionByDeploymentAndKey(deploymentId, key));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findProcessDefinitionByDeploymentAndKeyAndTenantId_delegates() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        String id = insert(deploymentId, key, "Some Process", 1);

        ProcessDefinitionEntity found = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionByDeploymentAndKeyAndTenantId(deploymentId, key, "ignored"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void parentDeploymentFinders_areStubbedNull_thisAppHasNoParentDeploymentConcept() {
        ProcessDefinitionEntity byKey = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionByParentDeploymentAndKey("some-parent", "some-key"));
        ProcessDefinitionEntity byKeyAndTenant = managementService.executeCommand(cc -> dataManager
                .findProcessDefinitionByParentDeploymentAndKeyAndTenantId("some-parent", "some-key", "some-tenant"));

        assertThat(byKey).isNull();
        assertThat(byKeyAndTenant).isNull();
    }

    @Test
    void findProcessDefinitionByKeyAndVersion_returnsTheMatchingDefinition() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        String v2Id = insert(UUID.randomUUID().toString(), key, "V2", 2);

        ProcessDefinitionEntity found = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionByKeyAndVersion(key, 2));

        assertThat(found.getId()).isEqualTo(v2Id);
    }

    @Test
    void findProcessDefinitionByKeyAndVersionAndTenantId_delegates() {
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, "Some Process", 1);

        ProcessDefinitionEntity found = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionByKeyAndVersionAndTenantId(key, 1, "ignored"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void nativeQuerySurfaces_areStubbedEmpty_sinceNothingInThisAppUsesThem() {
        List<ProcessDefinition> results = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionsByNativeQuery(java.util.Map.of()));
        Long count = managementService.executeCommand(
                cc -> dataManager.findProcessDefinitionCountByNativeQuery(java.util.Map.of()));

        assertThat(results).isEmpty();
        assertThat(count).isZero();
    }

    @Test
    void updateProcessDefinitionTenantIdForDeployment_isANoOpAndDoesNotThrow_tenantsArentModeledInThisApp() {
        managementService.executeCommand(cc -> {
            dataManager.updateProcessDefinitionTenantIdForDeployment(UUID.randomUUID().toString(), "some-tenant");
            return null;
        });
    }

    @Test
    void updateProcessDefinitionVersionForProcessDefinitionId_persistsTheNewVersion() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Process", 1);

        managementService.executeCommand(cc -> {
            dataManager.updateProcessDefinitionVersionForProcessDefinitionId(id, 7);
            return null;
        });

        ProcessDefinitionEntity reloaded = managementService.executeCommand(cc -> dataManager.findById(id));
        assertThat(reloaded.getVersion()).isEqualTo(7);
    }

    // --- findProcessDefinitionsByQueryCriteria / findProcessDefinitionCountByQueryCriteria, via
    //     the real RepositoryService query DSL (see class comment for why) ---

    @Test
    void queryById_returnsOnlyThatDefinition() {
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, "Some Process", 1);
        insert(UUID.randomUUID().toString(), key, "A Different Process", 1);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery().processDefinitionId(id).list();

        assertThat(results).extracting(ProcessDefinition::getId).containsExactly(id);
    }

    @Test
    void queryByIds_returnsExactlyTheGivenSet() {
        String key = "key-" + UUID.randomUUID();
        String id1 = insert(UUID.randomUUID().toString(), key, "V1", 1);
        String id2 = insert(UUID.randomUUID().toString(), key, "V2", 2);
        insert(UUID.randomUUID().toString(), key, "V3", 3);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery()
                .processDefinitionIds(Set.of(id1, id2))
                .list();

        assertThat(results).extracting(ProcessDefinition::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void queryByKeyLike_matchesAPrefix() {
        String prefix = "prefix-" + UUID.randomUUID() + "-";
        insert(UUID.randomUUID().toString(), prefix + "a", "A", 1);
        insert(UUID.randomUUID().toString(), prefix + "b", "B", 1);
        insert(UUID.randomUUID().toString(), "unrelated-" + UUID.randomUUID(), "C", 1);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKeyLike(prefix + "%")
                .list();

        assertThat(results).hasSize(2);
    }

    @Test
    void queryByName_returnsOnlyExactMatches() {
        String name = "name-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, name, 1);
        insert(UUID.randomUUID().toString(), key, "a different name", 1);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery().processDefinitionName(name).list();

        assertThat(results).extracting(ProcessDefinition::getId).containsExactly(id);
    }

    @Test
    void queryByNameLike_matchesAPrefix() {
        String prefix = "name-prefix-" + UUID.randomUUID() + "-";
        insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), prefix + "a", 1);
        insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), prefix + "b", 1);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery()
                .processDefinitionNameLike(prefix + "%")
                .list();

        assertThat(results).hasSize(2);
    }

    @Test
    void queryByVersion_returnsOnlyThatExactVersion() {
        String key = "key-" + UUID.randomUUID();
        String v1Id = insert(UUID.randomUUID().toString(), key, "V1", 1);
        insert(UUID.randomUUID().toString(), key, "V2", 2);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key)
                .processDefinitionVersion(1)
                .list();

        assertThat(results).extracting(ProcessDefinition::getId).containsExactly(v1Id);
    }

    @Test
    void queryByVersionGreaterThanAndGreaterThanOrEquals() {
        String key = "key-" + UUID.randomUUID();
        String v1Id = insert(UUID.randomUUID().toString(), key, "V1", 1);
        String v2Id = insert(UUID.randomUUID().toString(), key, "V2", 2);
        String v3Id = insert(UUID.randomUUID().toString(), key, "V3", 3);

        List<ProcessDefinition> greaterThan = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionVersionGreaterThan(1).list();
        List<ProcessDefinition> greaterThanOrEquals = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionVersionGreaterThanOrEquals(2).list();

        assertThat(greaterThan).extracting(ProcessDefinition::getId).containsExactlyInAnyOrder(v2Id, v3Id);
        assertThat(greaterThanOrEquals).extracting(ProcessDefinition::getId).containsExactlyInAnyOrder(v2Id, v3Id);
    }

    @Test
    void queryByVersionLowerThanAndLowerThanOrEquals() {
        String key = "key-" + UUID.randomUUID();
        String v1Id = insert(UUID.randomUUID().toString(), key, "V1", 1);
        String v2Id = insert(UUID.randomUUID().toString(), key, "V2", 2);
        insert(UUID.randomUUID().toString(), key, "V3", 3);

        List<ProcessDefinition> lowerThan = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionVersionLowerThan(3).list();
        List<ProcessDefinition> lowerThanOrEquals = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key).processDefinitionVersionLowerThanOrEquals(2).list();

        assertThat(lowerThan).extracting(ProcessDefinition::getId).containsExactlyInAnyOrder(v1Id, v2Id);
        assertThat(lowerThanOrEquals).extracting(ProcessDefinition::getId).containsExactlyInAnyOrder(v1Id, v2Id);
    }

    @Test
    void queryByKeyWithLatestVersion_returnsOnlyTheHighestVersionOfThatKey() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        String latestId = insert(UUID.randomUUID().toString(), key, "V2", 2);

        List<ProcessDefinition> results = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey(key)
                .latestVersion()
                .list();

        assertThat(results).extracting(ProcessDefinition::getId).containsExactly(latestId);
    }

    @Test
    void countByQueryCriteria_matchesTheListSize() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        insert(UUID.randomUUID().toString(), key, "V2", 2);

        long count = repositoryService.createProcessDefinitionQuery().processDefinitionKey(key).count();

        assertThat(count).isEqualTo(2);
    }
}
