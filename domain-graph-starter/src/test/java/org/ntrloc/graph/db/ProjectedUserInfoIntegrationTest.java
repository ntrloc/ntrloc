package org.ntrloc.graph.db;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.projection.CollectionProjectionSpec;
import org.ntrloc.graph.projection.ItemProjectionSpec;
import org.ntrloc.graph.projection.LinkProjectionSpec;
import org.ntrloc.graph.projection.Operator;
import org.ntrloc.graph.projection.ProjectedItem;
import org.ntrloc.graph.projection.PropertyValuePredicate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Proves UserInfo actually shows up where the user asked for it: createdBy/updatedBy within a
// real projection response (both single-item and collection), resolved by ProjectedUserInfoEnricher
// at the EntityManagerImpl layer -- going through EntityManager (not RegisterPartitionManager
// directly, unlike RegisterProvenanceIntegrationTest) is the whole point here, since enrichment
// only runs at that layer precisely because RegisterPartitionManager must never import the
// security partition.
class ProjectedUserInfoIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private SecurityRepository securityRepo;

    private NtrlocPrincipal SUPERUSER;

    @BeforeEach
    void setUpSuperuser() {
        var user = securityRepo.createUser("puiit-root-" + UUID.randomUUID(), "Root", null, true);
        SUPERUSER = TestPrincipals.of(user.id(), user.externalId(), user.displayName(), null, Set.of(), true);
    }

    @Test
    void itemProjection_resolvesCreatedByToRealUserInfo_andLeavesUpdatedByNullUntilActuallyUpdated() {
        var creator = securityRepo.createUser("puiit-creator-" + UUID.randomUUID(), "Creator Person", "creator@example.com", false);
        var editor = securityRepo.createUser("puiit-editor-" + UUID.randomUUID(), "Editor Person", "editor@example.com", false);

        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), txn, creator.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        ProjectedItem afterCreate = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", itemId), "http://binary", SUPERUSER)
                .orElseThrow();

        assertThat(afterCreate.createdBy().username()).isEqualTo(creator.externalId());
        assertThat(afterCreate.createdBy().email()).isEqualTo("creator@example.com");
        // Only ever created, never updated -- updatedBy must be absent, not a UserInfo for the
        // creator (see ProjectedItem's own comment on why create doesn't also count as "updated").
        assertThat(afterCreate.updatedBy()).isNull();

        UUID updateTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemUpdateEntry(itemId, Map.of(fixture.namePropertyId(), "Widget Pro"),
                Map.of(), Set.of(), Set.of(), Set.of())), updateTxn, editor.id(), OffsetDateTime.now());
        coordinator.commit(updateTxn, UUID.randomUUID());

        ProjectedItem afterUpdate = entityManager.project(new ItemProjectionSpec("CoordinatorTestProduct", itemId), "http://binary", SUPERUSER)
                .orElseThrow();

        assertThat(afterUpdate.updatedBy().username()).isEqualTo(editor.externalId());
        assertThat(afterUpdate.updatedBy().email()).isEqualTo("editor@example.com");
    }

    @Test
    void collectionProjection_resolvesCreatedByForEveryItem() {
        var creator = securityRepo.createUser("puiit-collection-creator-" + UUID.randomUUID(), "Collection Creator", "collection@example.com", false);

        UUID itemId = UUID.randomUUID();
        String name = "Gadget-" + itemId;
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), name), Map.of(), Set.of())), txn, creator.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());

        // Filtered to this item: other test classes share the database and add their own products,
        // so an unfiltered query's default first page may not include it.
        var result = entityManager.project(new CollectionProjectionSpec("CoordinatorTestProduct", null, null,
                new PropertyValuePredicate("name", Operator.EQUALS, name)), "http://binary", SUPERUSER);

        var projected = result.items().stream().filter(i -> i.itemId().equals(itemId)).findFirst().orElseThrow();
        assertThat(projected.createdBy().username()).isEqualTo(creator.externalId());
        assertThat(projected.createdBy().email()).isEqualTo("collection@example.com");
    }

    // The link's own createdBy/updatedBy are distinct from the linked item's -- different actors,
    // different moments -- so both have to resolve independently and correctly, not just "some
    // UserInfo shows up somewhere in the tree."
    @Test
    void linkProjection_resolvesCreatedByForBothTheLinkItselfAndTheLinkedItem() {
        var productCreator = securityRepo.createUser("puiit-product-creator-" + UUID.randomUUID(), "Product Creator", "product@example.com", false);
        var contributorCreator = securityRepo.createUser("puiit-contributor-creator-" + UUID.randomUUID(), "Contributor Creator", "contributor@example.com", false);
        var linkCreator = securityRepo.createUser("puiit-link-creator-" + UUID.randomUUID(), "Link Creator", "link@example.com", false);

        UUID productId = UUID.randomUUID();
        UUID contributorId = UUID.randomUUID();
        UUID setupTxn = UUID.randomUUID();
        coordinator.prepare(List.of(
                new ItemCreateEntry(productId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Book"), Map.of(), Set.of())
        ), setupTxn, productCreator.id(), OffsetDateTime.now());
        coordinator.commit(setupTxn, UUID.randomUUID());

        UUID contributorTxn = UUID.randomUUID();
        coordinator.prepare(List.of(
                new ItemCreateEntry(contributorId, fixture.contributorTypeId(), Map.of(fixture.contributorNamePropertyId(), "Author"), Map.of(), Set.of())
        ), contributorTxn, contributorCreator.id(), OffsetDateTime.now());
        coordinator.commit(contributorTxn, UUID.randomUUID());

        UUID linkId = UUID.randomUUID();
        UUID linkTxn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), "Author"))), linkTxn, linkCreator.id(), OffsetDateTime.now());
        coordinator.commit(linkTxn, UUID.randomUUID());

        ProjectedItem product = entityManager.project(
                new ItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", SUPERUSER).orElseThrow();

        var link = product.links().get("products").get(0);
        assertThat(link.createdBy().username()).isEqualTo(linkCreator.externalId());
        assertThat(link.item().createdBy().username()).isEqualTo(contributorCreator.externalId());
    }
}
