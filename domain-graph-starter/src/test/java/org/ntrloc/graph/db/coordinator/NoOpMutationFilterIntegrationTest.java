package org.ntrloc.graph.db.coordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers NoOpMutationFilter: a mutation describes a desired state, and a request that asks for
// exactly the state the system is already in must produce zero ledger rows, not a pointless one.
// Every test counts ledger_entry rows directly (not just checking the end state looks right) --
// the point being tested is specifically "nothing was recorded", which the end state alone can't
// distinguish from "recorded redundantly but harmlessly".
class NoOpMutationFilterIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private SecurityRepository securityRepo;

    // ledger_entry.created_by/register_item.created_by (Part E) are NOT NULL FKs to
    // security_user.id -- every coordinator.prepare call below needs a real, backed actor.
    private SecurityRepository.UserRow actor;

    @BeforeEach
    void setUpActor() {
        actor = securityRepo.createUser("nomf-actor-" + UUID.randomUUID(), "NoOp Test Actor", null, true);
    }

    private long itemLedgerRowCount(UUID itemId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM ledger_entry WHERE target_type = 'ITEM' AND target_id = :itemId")
                .param("itemId", itemId).query(Long.class).single();
    }

    private long linkLedgerRowCount(UUID linkId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM ledger_entry WHERE target_type = 'LINK' AND target_id = :linkId")
                .param("linkId", linkId).query(Long.class).single();
    }

    private long committedLinkRowCount(UUID linkId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM register_link WHERE link_id = :linkId AND state = 'COMMITTED'")
                .param("linkId", linkId).query(Long.class).single();
    }

    private UUID createProduct(Map<UUID, Object> properties) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), properties, Map.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    @Test
    void settingAPropertyToItsCurrentValue_producesNoLedgerEntry() {
        UUID itemId = createProduct(Map.of(fixture.colorPropertyId(), "red"));
        long before = itemLedgerRowCount(itemId);

        Map<UUID, Object> diff = new HashMap<>();
        diff.put(fixture.colorPropertyId(), "red");
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(itemLedgerRowCount(itemId)).isEqualTo(before);
    }

    @Test
    void settingAPropertyToADifferentValue_stillProducesALedgerEntry() {
        UUID itemId = createProduct(Map.of(fixture.colorPropertyId(), "red"));
        long before = itemLedgerRowCount(itemId);

        Map<UUID, Object> diff = new HashMap<>();
        diff.put(fixture.colorPropertyId(), "blue");
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(itemLedgerRowCount(itemId)).isGreaterThan(before);
    }

    @Test
    void aRequestMixingARealChangeWithANoOpProperty_stillAppliesTheRealChangeAlone() {
        UUID itemId = createProduct(Map.of(fixture.colorPropertyId(), "red", fixture.namePropertyId(), "Widget"));

        Map<UUID, Object> diff = new HashMap<>();
        diff.put(fixture.colorPropertyId(), "red"); // no-op
        diff.put(fixture.namePropertyId(), "Widget Pro"); // real change
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        var projected = registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(projected.properties()).containsEntry("name", "Widget Pro").containsEntry("color", "red");
    }

    @Test
    void settingAStateMachineToItsCurrentState_producesNoLedgerEntry() {
        UUID itemId = createProduct(Map.of());
        UUID smId = newThrowawayMachineStarted(itemId);
        long before = itemLedgerRowCount(itemId);

        UUID currentStateId = registerPartitionManager.currentStateIds(itemId).get(smId);
        Map<UUID, UUID> stateChanges = new HashMap<>();
        stateChanges.put(smId, currentStateId);
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(), stateChanges, Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(itemLedgerRowCount(itemId)).isEqualTo(before);
    }

    @Test
    void endingAStateMachineTheItemNeverStarted_producesNoLedgerEntry() {
        UUID itemId = createProduct(Map.of());
        UUID smId = defineThrowawayMachine(); // defined in the schema, but never started on this item
        long before = itemLedgerRowCount(itemId);

        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(), Map.of(), Set.of(smId), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(itemLedgerRowCount(itemId)).isEqualTo(before);
    }

    @Test
    void creatingALinkOnAToOneCardinalityPerspective_isANoOpWhenThatExactPairAlreadyExists() {
        ToOneLinkFixture linkFixture = defineThrowawayToOneLinkFixture();
        UUID itemA = createThrowawayItem(linkFixture.typeA());
        UUID itemB = createThrowawayItem(linkFixture.typeB());

        UUID linkId = UUID.randomUUID();
        UUID createTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, linkFixture.linkTypeId(),
                new LinkEndpoint(linkFixture.perspectiveA(), itemA), new LinkEndpoint(linkFixture.perspectiveB(), itemB),
                Map.of())), createTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(createTxn, UUID.randomUUID());
        assertThat(committedLinkRowCount(linkId)).isEqualTo(1);

        UUID duplicateLinkId = UUID.randomUUID();
        UUID duplicateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(duplicateLinkId, linkFixture.linkTypeId(),
                new LinkEndpoint(linkFixture.perspectiveA(), itemA), new LinkEndpoint(linkFixture.perspectiveB(), itemB),
                Map.of())), duplicateTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(duplicateTxn, UUID.randomUUID());

        assertThat(linkLedgerRowCount(duplicateLinkId)).isZero();
        assertThat(committedLinkRowCount(duplicateLinkId)).isZero();
    }

    @Test
    void creatingASecondLinkOnAToManyCardinalityPerspective_isNotANoOp() {
        // fixture's "products"/"contributors" perspectives are to-many (maxCardinality null) --
        // see CoordinatorTestDomainInitializer's own CreatePerspectiveDefinitionMutation calls.
        UUID productId = createProduct(Map.of());
        UUID contributorId = createContributor();

        UUID firstLinkId = UUID.randomUUID();
        UUID firstTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(firstLinkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId), new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), firstTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(firstTxn, UUID.randomUUID());

        UUID secondLinkId = UUID.randomUUID();
        UUID secondTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(secondLinkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId), new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), secondTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(secondTxn, UUID.randomUUID());

        assertThat(committedLinkRowCount(firstLinkId)).isEqualTo(1);
        assertThat(committedLinkRowCount(secondLinkId)).isEqualTo(1);
    }

    @Test
    void deletingALinkThatDoesNotExist_producesNoLedgerEntryAndDoesNotThrow() {
        UUID neverExistedLinkId = UUID.randomUUID();
        long before = linkLedgerRowCount(neverExistedLinkId);

        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkDeleteEntry(neverExistedLinkId)), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(linkLedgerRowCount(neverExistedLinkId)).isEqualTo(before);
    }

    @Test
    void updatingALinkPropertyToItsCurrentValue_producesNoLedgerEntry() {
        UUID productId = createProduct(Map.of());
        UUID contributorId = createContributor();
        UUID linkId = UUID.randomUUID();
        Map<UUID, Object> initial = new HashMap<>();
        initial.put(fixture.rolePropertyId(), "editor");
        UUID createTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId), new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                initial)), createTxn, actor.id(), OffsetDateTime.now());
        coordinator.commit(createTxn, UUID.randomUUID());
        long before = linkLedgerRowCount(linkId);

        Map<UUID, Object> diff = new HashMap<>();
        diff.put(fixture.rolePropertyId(), "editor");
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkUpdateEntry(linkId, diff)), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        assertThat(linkLedgerRowCount(linkId)).isEqualTo(before);
    }

    @Test
    void anEntirelyNoOpRequest_stillPreparesAndCommitsWithoutError() {
        UUID itemId = createProduct(Map.of(fixture.colorPropertyId(), "red"));
        UUID txn = UUID.randomUUID();

        Map<UUID, Object> diff = new HashMap<>();
        diff.put(fixture.colorPropertyId(), "red");
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, diff, Map.of(), Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        var projected = registerPartitionManager.projectOne(fixture.productTypeId(), itemId, "http://binary").orElseThrow();
        assertThat(projected.properties()).containsEntry("color", "red");
    }

    // --- fixtures local to this test class ---

    private UUID createContributor() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.contributorTypeId(),
                Map.of(fixture.contributorNamePropertyId(), "Contributor"), Map.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private record ToOneLinkFixture(UUID typeA, UUID typeB, UUID linkTypeId, UUID perspectiveA, UUID perspectiveB) {}

    // Two brand-new throwaway item types, not the shared CoordinatorTestProduct/Contributor --
    // adding a perspective to the shared fixture types would permanently grow their own
    // createLinks() enumeration for every other test sharing this suite's singleton schema (see
    // AbstractIntegrationTest's own comment on why it's shared, not per-class).
    private ToOneLinkFixture defineThrowawayToOneLinkFixture() {
        String suffix = UUID.randomUUID().toString();
        String typeNameA = "NoOpTestToOneA-" + suffix;
        String typeNameB = "NoOpTestToOneB-" + suffix;
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeNameA, "no-op filter test fixture", List.of(), null, false, null)));
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(typeNameB, "no-op filter test fixture", List.of(), null, false, null)));
        UUID typeA = findItemTypeId(typeNameA);
        UUID typeB = findItemTypeId(typeNameB);

        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(
                List.of(),
                List.of(
                        new CreatePerspectiveDefinitionMutation(typeA, "toOne", "desc", 0, 1),
                        new CreatePerspectiveDefinitionMutation(typeB, "toOneInverse", "desc", 0, null)))));

        AdminItemDefinitionView itemA = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(typeA)).findFirst().orElseThrow();
        var perspectiveA = itemA.links().get("toOne").get(0);
        AdminItemDefinitionView itemB = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(typeB)).findFirst().orElseThrow();
        var perspectiveB = itemB.links().get("toOneInverse").get(0);
        return new ToOneLinkFixture(typeA, typeB, perspectiveA.linkId(), perspectiveA.id(), perspectiveB.id());
    }

    private UUID findItemTypeId(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(name))
                .findFirst().orElseThrow().id();
    }

    private UUID createThrowawayItem(UUID itemTypeId) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, itemTypeId, Map.of(), Map.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID defineThrowawayMachine() {
        String name = "NoOpTestMachine-" + UUID.randomUUID();
        schemaManager.applyMutations(List.<org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation>of(
                new org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation(fixture.productTypeId(), name, null)));
        UUID smId = registerPartitionManager.resolveStateMachineId(fixture.productTypeId(), name);
        schemaManager.applyMutations(List.<org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation>of(
                new org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation(smId, "S1", null, null, null, null)));
        return smId;
    }

    private UUID newThrowawayMachineStarted(UUID itemId) {
        UUID smId = defineThrowawayMachine();
        UUID s1 = registerPartitionManager.resolveStateId(smId, "S1");
        UUID start = registerPartitionManager.resolveStateId(smId, "__start__");
        schemaManager.applyMutations(List.<org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation>of(
                new org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation(start, s1, "Begin", null, null, null)));

        Map<UUID, UUID> stateChanges = new HashMap<>();
        stateChanges.put(smId, s1);
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(), stateChanges, Set.of(), Set.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return smId;
    }
}
