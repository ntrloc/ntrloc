package org.ntrloc.graph.db.partition.process.dmn.persistence;

import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.dmn.engine.impl.persistence.entity.DecisionEntity;
import org.flowable.dmn.engine.impl.persistence.entity.DecisionEntityImpl;
import org.flowable.engine.ManagementService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers DecisionDataManagerImpl's CRUD/finder logic directly (same style as
// JobDataManagerImplIntegrationTest -- run inside a Flowable command via
// ManagementService.executeCommand(), which ProcessSession resolves correctly regardless of
// whether the process or DMN engine started the command, see AbstractDecisionDataManager's own
// comment), plus findDecisionsByQueryCriteria/findDecisionCountByQueryCriteria via the real
// DmnRepositoryService query DSL rather than constructing DecisionQueryImpl by hand -- that's the
// actual production entry point for those two methods (DmnDecisionQuery.list()/.count()), and it
// runs its own command internally so no executeCommand() wrapping is needed for those tests.
//
// decision_definition is a shared table with no per-row tenant/marker column to scope by (unlike
// the register partition's item-property framework), so every test below gives its own rows a
// randomly-generated key/name/deploymentId and filters on that -- never a shared literal value --
// to stay isolated from whatever other test methods have left in the same singleton Postgres
// container (see AbstractIntegrationTest's own comment on why the container isn't per-class).
class DecisionDataManagerImplIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ManagementService managementService;

    @Autowired
    private DmnRepositoryService dmnRepositoryService;

    private final DecisionDataManagerImpl decisionDataManager = new DecisionDataManagerImpl();

    private DecisionEntity newDecision(String deploymentId, String key, String name, int version) {
        DecisionEntity decision = decisionDataManager.create();
        decision.setDeploymentId(deploymentId);
        decision.setKey(key);
        decision.setName(name);
        decision.setVersion(version);
        decision.setResourceName(key + ".dmn");
        return decision;
    }

    private String insert(String deploymentId, String key, String name, int version) {
        return managementService.executeCommand(cc -> {
            DecisionEntity decision = newDecision(deploymentId, key, name, version);
            decisionDataManager.insert(decision);
            return decision.getId();
        });
    }

    // --- Basic CRUD ---

    @Test
    void create_returnsAFreshEntityWithNoIdYet() {
        DecisionEntity decision = decisionDataManager.create();
        assertThat(decision.getId()).isNull();
    }

    @Test
    void insert_assignsAnIdWhenNoneWasSet() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Decision", 1);
        assertThat(id).isNotNull();
    }

    @Test
    void insertThenFindById_returnsAMatchingEntity() {
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, "Some Decision", 1);

        DecisionEntity found = managementService.executeCommand(cc -> decisionDataManager.findById(id));

        assertThat(found.getId()).isEqualTo(id);
        assertThat(found.getKey()).isEqualTo(key);
        assertThat(found.getName()).isEqualTo("Some Decision");
        assertThat(found.getVersion()).isEqualTo(1);
        assertThat(found.getResourceName()).isEqualTo(key + ".dmn");
    }

    @Test
    void findById_forUnknownId_returnsNull() {
        DecisionEntity found = managementService.executeCommand(
                cc -> decisionDataManager.findById(UUID.randomUUID().toString()));
        assertThat(found).isNull();
    }

    @Test
    void findByIdTwiceInTheSameCommand_returnsTheSameCachedObject() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Decision", 1);

        Boolean sameInstance = managementService.executeCommand(cc -> {
            DecisionEntity first = decisionDataManager.findById(id);
            DecisionEntity second = decisionDataManager.findById(id);
            return first == second;
        });

        assertThat(sameInstance).isTrue();
    }

    @Test
    void update_persistsChanges() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Original Name", 1);

        managementService.executeCommand(cc -> {
            DecisionEntity decision = decisionDataManager.findById(id);
            decision.setName("Renamed");
            return decisionDataManager.update(decision);
        });

        DecisionEntity reloaded = managementService.executeCommand(cc -> decisionDataManager.findById(id));
        assertThat(reloaded.getName()).isEqualTo("Renamed");
    }

    @Test
    void delete_removesTheRow() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Decision", 1);

        managementService.executeCommand(cc -> {
            decisionDataManager.delete(id);
            return null;
        });

        DecisionEntity afterDelete = managementService.executeCommand(cc -> decisionDataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    @Test
    void deleteByEntity_delegatesToDeleteById() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Decision", 1);

        managementService.executeCommand(cc -> {
            DecisionEntity decision = decisionDataManager.findById(id);
            decisionDataManager.delete(decision);
            return null;
        });

        DecisionEntity afterDelete = managementService.executeCommand(cc -> decisionDataManager.findById(id));
        assertThat(afterDelete).isNull();
    }

    // --- Key/version-based finders ---

    @Test
    void findLatestDecisionByKey_returnsTheHighestVersion() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        String latestId = insert(UUID.randomUUID().toString(), key, "V2", 2);

        DecisionEntity latest = managementService.executeCommand(
                cc -> decisionDataManager.findLatestDecisionByKey(key));

        assertThat(latest.getId()).isEqualTo(latestId);
        assertThat(latest.getVersion()).isEqualTo(2);
    }

    @Test
    void findLatestDecisionByKeyAndTenantIdAndParentDeploymentIdVariants_allDelegateToFindLatestDecisionByKey() {
        String key = "key-" + UUID.randomUUID();
        String latestId = insert(UUID.randomUUID().toString(), key, "Only Version", 1);

        DecisionEntity byTenant = managementService.executeCommand(
                cc -> decisionDataManager.findLatestDecisionByKeyAndTenantId(key, "ignored-tenant"));
        DecisionEntity byParentDeployment = managementService.executeCommand(
                cc -> decisionDataManager.findLatestDecisionByKeyAndParentDeploymentId(key, "ignored-parent"));
        DecisionEntity byParentDeploymentAndTenant = managementService.executeCommand(
                cc -> decisionDataManager.findLatestDecisionByKeyParentDeploymentIdAndTenantId(
                        key, "ignored-parent", "ignored-tenant"));

        assertThat(byTenant.getId()).isEqualTo(latestId);
        assertThat(byParentDeployment.getId()).isEqualTo(latestId);
        assertThat(byParentDeploymentAndTenant.getId()).isEqualTo(latestId);
    }

    @Test
    void findDecisionByDeploymentAndKey_returnsTheMatchingDecision() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        String id = insert(deploymentId, key, "Some Decision", 1);

        DecisionEntity found = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionByDeploymentAndKey(deploymentId, key));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findDecisionByDeploymentAndKeyAndTenantId_delegatesToFindDecisionByDeploymentAndKey() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        String id = insert(deploymentId, key, "Some Decision", 1);

        DecisionEntity found = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionByDeploymentAndKeyAndTenantId(deploymentId, key, "ignored"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void findDecisionByKeyAndVersion_returnsTheMatchingDecision() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        String v2Id = insert(UUID.randomUUID().toString(), key, "V2", 2);

        DecisionEntity found = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionByKeyAndVersion(key, 2));

        assertThat(found.getId()).isEqualTo(v2Id);
    }

    @Test
    void findDecisionByKeyAndVersionAndTenantId_delegatesToFindDecisionByKeyAndVersion() {
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, "Some Decision", 1);

        DecisionEntity found = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionByKeyAndVersionAndTenantId(key, 1, "ignored"));

        assertThat(found.getId()).isEqualTo(id);
    }

    @Test
    void deleteDecisionsByDeploymentId_removesEveryDecisionInThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        insert(deploymentId, key, "V1", 1);
        insert(deploymentId, key, "V2", 2);

        managementService.executeCommand(cc -> {
            decisionDataManager.deleteDecisionsByDeploymentId(deploymentId);
            return null;
        });

        List<DmnDecision> remaining = dmnRepositoryService.createDecisionQuery().deploymentId(deploymentId).list();
        assertThat(remaining).isEmpty();
    }

    @Test
    void updateDecisionTenantIdForDeployment_isANoOpAndDoesNotThrow_tenantsArentModeledInThisApp() {
        managementService.executeCommand(cc -> {
            decisionDataManager.updateDecisionTenantIdForDeployment(UUID.randomUUID().toString(), "some-tenant");
            return null;
        });
    }

    @Test
    void nativeQuerySurfaces_areStubbedEmpty_sinceNothingInThisAppUsesThem() {
        List<DmnDecision> results = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionsByNativeQuery(java.util.Map.of()));
        Long count = managementService.executeCommand(
                cc -> decisionDataManager.findDecisionCountByNativeQuery(java.util.Map.of()));

        assertThat(results).isEmpty();
        assertThat(count).isZero();
    }

    // --- findDecisionsByQueryCriteria / findDecisionCountByQueryCriteria, via the real
    //     DmnRepositoryService query DSL (see class comment for why) ---

    @Test
    void queryById_returnsOnlyThatDecision() {
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, "Some Decision", 1);
        insert(UUID.randomUUID().toString(), key, "A Different Decision", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().decisionId(id).list();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(id);
    }

    @Test
    void queryByIds_returnsExactlyTheGivenSet() {
        String key = "key-" + UUID.randomUUID();
        String id1 = insert(UUID.randomUUID().toString(), key, "V1", 1);
        String id2 = insert(UUID.randomUUID().toString(), key, "V2", 2);
        insert(UUID.randomUUID().toString(), key, "V3", 3);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery()
                .decisionIds(Set.of(id1, id2))
                .list();

        assertThat(results).extracting(DmnDecision::getId).containsExactlyInAnyOrder(id1, id2);
    }

    @Test
    void queryByKey_returnsEveryVersionOfThatKey() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        insert(UUID.randomUUID().toString(), key, "V2", 2);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().decisionKey(key).list();

        assertThat(results).hasSize(2);
    }

    @Test
    void queryByKeyLike_matchesAPrefix() {
        String prefix = "prefix-" + UUID.randomUUID() + "-";
        insert(UUID.randomUUID().toString(), prefix + "a", "A", 1);
        insert(UUID.randomUUID().toString(), prefix + "b", "B", 1);
        insert(UUID.randomUUID().toString(), "unrelated-" + UUID.randomUUID(), "C", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().decisionKeyLike(prefix + "%").list();

        assertThat(results).hasSize(2);
    }

    @Test
    void queryByName_returnsOnlyExactMatches() {
        String name = "name-" + UUID.randomUUID();
        String key = "key-" + UUID.randomUUID();
        String id = insert(UUID.randomUUID().toString(), key, name, 1);
        insert(UUID.randomUUID().toString(), key, "a different name", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().decisionName(name).list();

        assertThat(results).extracting(DmnDecision::getId).containsExactly(id);
    }

    @Test
    void queryByNameLike_matchesAPrefix() {
        String prefix = "name-prefix-" + UUID.randomUUID() + "-";
        insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), prefix + "a", 1);
        insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), prefix + "b", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().decisionNameLike(prefix + "%").list();

        assertThat(results).hasSize(2);
    }

    @Test
    void queryByDeploymentId_returnsOnlyThatDeployment() {
        String deploymentId = UUID.randomUUID().toString();
        String key = "key-" + UUID.randomUUID();
        String id = insert(deploymentId, key, "Some Decision", 1);
        insert(UUID.randomUUID().toString(), key, "Some Decision", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().deploymentId(deploymentId).list();

        assertThat(results).extracting(DmnDecision::getId).containsExactly(id);
    }

    @Test
    void queryByKeyAndVersion_combinesBothConditions() {
        String key = "key-" + UUID.randomUUID();
        String v1Id = insert(UUID.randomUUID().toString(), key, "V1", 1);
        insert(UUID.randomUUID().toString(), key, "V2", 2);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery()
                .decisionKey(key)
                .decisionVersion(1)
                .list();

        assertThat(results).extracting(DmnDecision::getId).containsExactly(v1Id);
    }

    @Test
    void queryByKeyWithLatestVersion_returnsOnlyTheHighestVersionOfThatKey() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        String latestId = insert(UUID.randomUUID().toString(), key, "V2", 2);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery()
                .decisionKey(key)
                .latestVersion()
                .list();

        assertThat(results).extracting(DmnDecision::getId).containsExactly(latestId);
    }

    @Test
    void queryWithNoConditions_returnsEveryKnownDecisionAmongOthers() {
        String id = insert(UUID.randomUUID().toString(), "key-" + UUID.randomUUID(), "Some Decision", 1);

        List<DmnDecision> results = dmnRepositoryService.createDecisionQuery().list();

        assertThat(results).extracting(DmnDecision::getId).contains(id);
    }

    @Test
    void countByQueryCriteria_matchesTheListSize() {
        String key = "key-" + UUID.randomUUID();
        insert(UUID.randomUUID().toString(), key, "V1", 1);
        insert(UUID.randomUUID().toString(), key, "V2", 2);

        long count = dmnRepositoryService.createDecisionQuery().decisionKey(key).count();

        assertThat(count).isEqualTo(2);
    }
}
