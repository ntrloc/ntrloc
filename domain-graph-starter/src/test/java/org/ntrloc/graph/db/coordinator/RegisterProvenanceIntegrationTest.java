package org.ntrloc.graph.db.coordinator;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.authorization.RequestPermissionContext;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.projection.LinkProjectionSpec;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.ProjectedLink;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves the register-side provenance semantics Part E of docs/ntrloc-principal-enforcement-plan.md
// decided on, for both items and links: createdDate/createdBy are set once, from the entry that
// first creates the row, and never change again no matter how many updates follow; updatedDate/
// updatedBy are null until the first real update -- an item/link that's only ever been created
// has no "updated" at all, not an updated equal to its own creation -- then always reflect the
// most recent one. This is the one behavior the user explicitly required test coverage for --
// not optional, called out by name in that doc's Part E section.
//
// Goes through LedgerRegisterCoordinator.prepare/commit directly (not through MutationRequestProcessor)
// and reads back via RegisterPartitionManager.projectOne -- the register layer itself, one level
// below EntityManagerImpl. Asserts against createdByUserId/updatedByUserId (the raw UUID
// RegisterPartitionManager actually populates), not createdBy/updatedBy (the resolved UserInfo
// ProjectedUserInfoEnricher fills in afterward, at the EntityManagerImpl layer this test
// deliberately bypasses) -- see ProjectedItem's own comment for why those are two different
// fields. createdDate/updatedDate are read straight off the register row, never recomputed, so
// asserting against them is asserting against the actual persisted columns.
class RegisterProvenanceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private SecurityRepository securityRepo;

    // Truncated to microseconds -- Postgres TIMESTAMPTZ's own precision -- and pinned to UTC, so
    // the round trip through the DB can be compared by instant (isEqual), not exact string/offset
    // representation, without losing any precision the DB would have kept anyway.
    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private UUID createUser(String prefix) {
        return securityRepo.createUser(prefix + " display name", prefix + "-" + UUID.randomUUID() + "@example.com", true).id();
    }

    private ProjectedItem projectProduct(UUID itemId, Map<String, LinkProjectionSpec> requestedLinks) {
        return registerPartitionManager
                .projectOne(fixture.productTypeId(), itemId, "", requestedLinks, RequestPermissionContext.forSuperuser())
                .orElseThrow();
    }

    @Test
    void itemProvenance_createdFieldsNeverChange_updatedFieldsAlwaysReflectTheMostRecentChange() {
        UUID creator = createUser("item-creator");
        UUID firstEditor = createUser("item-first-editor");
        UUID secondEditor = createUser("item-second-editor");

        UUID itemId = UUID.randomUUID();
        OffsetDateTime createdAt = now();
        UUID createTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), createTxn, creator, createdAt);
        coordinator.commit(createTxn, UUID.randomUUID());

        ProjectedItem afterCreate = projectProduct(itemId, null);
        assertThat(afterCreate.createdByUserId()).isEqualTo(creator);
        assertThat(afterCreate.createdDate().isEqual(createdAt)).isTrue();
        // An item that's only ever been created has no "updated" at all -- not an updated equal
        // to its own creation.
        assertThat(afterCreate.updatedByUserId()).isNull();
        assertThat(afterCreate.updatedDate()).isNull();

        OffsetDateTime firstUpdateAt = now().plusSeconds(1);
        UUID firstUpdateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(fixture.namePropertyId(), "Widget Pro"),
                Map.of(), Set.of(), Set.of(), Set.of())), firstUpdateTxn, firstEditor, firstUpdateAt);
        coordinator.commit(firstUpdateTxn, UUID.randomUUID());

        ProjectedItem afterFirstUpdate = projectProduct(itemId, null);
        // created* must survive the update completely unchanged, even though the underlying
        // register_item row was wholesale-replaced (see RegisterPartitionManager.stageItemChange).
        assertThat(afterFirstUpdate.createdByUserId()).isEqualTo(creator);
        assertThat(afterFirstUpdate.createdDate().isEqual(createdAt)).isTrue();
        assertThat(afterFirstUpdate.updatedByUserId()).isEqualTo(firstEditor);
        assertThat(afterFirstUpdate.updatedDate().isEqual(firstUpdateAt)).isTrue();

        // A second update, by a third, different actor -- proves this isn't a one-shot carry
        // forward that happens to work the first time: created* must still be pinned to the
        // original creator/moment, and updated* must move again, not get stuck on the first edit.
        OffsetDateTime secondUpdateAt = now().plusSeconds(2);
        UUID secondUpdateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(fixture.namePropertyId(), "Widget Pro Max"),
                Map.of(), Set.of(), Set.of(), Set.of())), secondUpdateTxn, secondEditor, secondUpdateAt);
        coordinator.commit(secondUpdateTxn, UUID.randomUUID());

        ProjectedItem afterSecondUpdate = projectProduct(itemId, null);
        assertThat(afterSecondUpdate.createdByUserId()).isEqualTo(creator);
        assertThat(afterSecondUpdate.createdDate().isEqual(createdAt)).isTrue();
        assertThat(afterSecondUpdate.updatedByUserId()).isEqualTo(secondEditor);
        assertThat(afterSecondUpdate.updatedDate().isEqual(secondUpdateAt)).isTrue();
    }

    @Test
    void linkProvenance_createdFieldsNeverChange_updatedFieldsAlwaysReflectTheMostRecentChange() {
        UUID creator = createUser("link-creator");
        UUID editor = createUser("link-editor");

        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID setupTxn = UUID.randomUUID();
        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Book"), Map.of(), Set.of()),
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Author"), Map.of(), Set.of())
        ), setupTxn, creator, now());
        coordinator.commit(setupTxn, UUID.randomUUID());

        UUID linkId = UUID.randomUUID();
        OffsetDateTime createdAt = now().plusSeconds(1);
        UUID linkCreateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), "Author"))), linkCreateTxn, creator, createdAt);
        coordinator.commit(linkCreateTxn, UUID.randomUUID());

        ProjectedLink afterCreate = soleLink(productId);
        assertThat(afterCreate.createdByUserId()).isEqualTo(creator);
        assertThat(afterCreate.createdDate().isEqual(createdAt)).isTrue();
        // Same reasoning as the item case: no "updated" at all until a real update happens.
        assertThat(afterCreate.updatedByUserId()).isNull();
        assertThat(afterCreate.updatedDate()).isNull();

        OffsetDateTime updatedAt = now().plusSeconds(2);
        UUID linkUpdateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkUpdateEntry(linkId, Map.of(fixture.rolePropertyId(), "Editor"))),
                linkUpdateTxn, editor, updatedAt);
        coordinator.commit(linkUpdateTxn, UUID.randomUUID());

        ProjectedLink afterUpdate = soleLink(productId);
        // Same carry-forward requirement as items: register_link rows are wholesale-replaced on
        // update too (RegisterPartitionManager.stageLinkUpdate), so this is a real assertion, not
        // a given.
        assertThat(afterUpdate.createdByUserId()).isEqualTo(creator);
        assertThat(afterUpdate.createdDate().isEqual(createdAt)).isTrue();
        assertThat(afterUpdate.updatedByUserId()).isEqualTo(editor);
        assertThat(afterUpdate.updatedDate().isEqual(updatedAt)).isTrue();
    }

    private ProjectedLink soleLink(UUID productId) {
        ProjectedItem product = projectProduct(productId, Map.of("products", new LinkProjectionSpec(null)));
        List<ProjectedLink> links = product.links().get("products");
        assertThat(links).hasSize(1);
        return links.get(0);
    }
}
