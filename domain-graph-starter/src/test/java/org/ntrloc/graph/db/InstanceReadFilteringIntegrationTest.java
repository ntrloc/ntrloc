package org.ntrloc.graph.db;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.DefaultGroupInitializer;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.db.projection.CollectionProjectionSpec;
import org.ntrloc.graph.db.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Slice B: the item/link existence semi-joins wired into RegisterPartitionManager's actual query
// paths (project/projectAcrossTypes, projectOne, and fetchLinksByItem), driven through
// EntityManager exactly the way the real HTTP endpoints do. Every non-superuser fixture user is
// added to the default "everyone" group, which already holds type-level item-type:read on these
// fixture types (DefaultGroupInitializer's default-open-until-narrowed grant) -- so every test
// here is isolating the *instance*-level marker gate specifically, not type-level visibility.
class InstanceReadFilteringIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private MarkerAssignmentService markerAssignmentService;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private DefaultGroupInitializer defaultGroupInitializer;

    private UUID createItem(UUID itemTypeId, UUID propertyId, Object value) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, itemTypeId, Map.of(propertyId, value), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createProduct() {
        return createItem(fixture.productTypeId(), fixture.namePropertyId(), "Widget");
    }

    private UUID createContributor() {
        return createItem(fixture.contributorTypeId(), fixture.contributorNamePropertyId(), "Ada");
    }

    private UUID createLink(UUID productId, UUID contributorId) {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return linkId;
    }

    private NtrlocPrincipal newUserInEveryoneGroup() {
        var user = securityRepo.createUser("irf-" + UUID.randomUUID(), "Restricted", null, false);
        UUID everyoneGroupId = defaultGroupInitializer.getDefaultGroupId();
        securityRepo.addUserToGroup(user.id(), everyoneGroupId);
        // groupIds is carried directly on the principal, not re-resolved from the DB per check --
        // must match the membership just added above or every type-level (and instance-level
        // group-granted) check below fails as if the user were in no groups at all.
        return new ResolvedPrincipal(user.id(), user.externalId(), user.externalId(), null, Set.of(everyoneGroupId), false);
    }

    private static final NtrlocPrincipal SUPERUSER =
            new ResolvedPrincipal(UUID.randomUUID(), "irf-root", "Root", null, Set.of(), true);

    private void grantItemRead(UUID itemId, NtrlocPrincipal principal) {
        var marker = authRepo.createMarker("irf-" + UUID.randomUUID(), "test fixture", "ITEM_TYPE", fixture.productTypeId());
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test-actor", "test reason");
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(marker.id(), "USER", principal.id()), true, false);
    }

    // link:read is anchored to the *source* item's own marker via a specific perspective now
    // (markers only ever apply to items, never links -- see
    // docs/ntrloc-marker-admin-ui-design-notes.md), so this grants a marker on sourceItemId itself,
    // with a link-perspective grant for the perspective the link is being traversed through.
    private void grantLinkRead(UUID sourceItemId, UUID perspectiveId, NtrlocPrincipal principal) {
        var marker = authRepo.createMarker("irf-" + UUID.randomUUID(), "test fixture", "ITEM_TYPE", fixture.productTypeId());
        markerAssignmentService.addItemMarker(sourceItemId, marker.id(), "test-actor", "test reason");
        UUID grantId = authRepo.ensureMarkerGrant(marker.id(), "USER", principal.id());
        authRepo.grantLinkPerspectiveAccess(grantId, perspectiveId, false, true, false);
    }

    // --- Collection projection ---

    @Test
    void collectionProjection_itemWithNoMarker_isExcludedForNonSuperuser() {
        createProduct(); // no marker assigned
        var principal = newUserInEveryoneGroup();

        var result = entityManager.project(new CollectionProjectionSpec("CoordinatorTestProduct", null, null), "http://binary", principal);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalCount()).isZero();
    }

    @Test
    void collectionProjection_itemWithGrantedMarker_isIncluded() {
        UUID productId = createProduct();
        var principal = newUserInEveryoneGroup();
        grantItemRead(productId, principal);

        var result = entityManager.project(new CollectionProjectionSpec("CoordinatorTestProduct", null, null), "http://binary", principal);

        assertThat(result.items()).extracting(i -> i.itemId()).contains(productId);
    }

    @Test
    void collectionProjection_mixedVisibility_onlyGrantedItemsReturned_andTotalCountReflectsTheFilter() {
        UUID visible = createProduct();
        createProduct(); // stays ungranted
        var principal = newUserInEveryoneGroup();
        grantItemRead(visible, principal);

        var result = entityManager.project(new CollectionProjectionSpec("CoordinatorTestProduct", null, null), "http://binary", principal);

        assertThat(result.items()).extracting(i -> i.itemId()).containsExactly(visible);
        assertThat(result.totalCount()).isEqualTo(1L);
    }

    @Test
    void collectionProjection_superuserSeesUnmarkedItems() {
        UUID productId = createProduct();

        var result = entityManager.project(new CollectionProjectionSpec("CoordinatorTestProduct", null, null), "http://binary", SUPERUSER);

        assertThat(result.items()).extracting(i -> i.itemId()).contains(productId);
    }

    // --- Single item projection ---

    @Test
    void singleItemProjection_itemWithNoMarker_throwsNotFound() {
        UUID productId = createProduct();
        var principal = newUserInEveryoneGroup();

        // project() throws rather than returning Optional.empty() on a permission denial (matching
        // requireReadAccess's existing type-level behavior) -- Optional.empty() is reserved for a
        // genuinely nonexistent item, a distinct case EntityManagerImpl already handles earlier.
        assertThatThrownBy(() ->
                        entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void singleItemProjection_itemWithGrantedMarker_returnsIt() {
        UUID productId = createProduct();
        var principal = newUserInEveryoneGroup();
        grantItemRead(productId, principal);

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result).isPresent();
        assertThat(result.get().itemId()).isEqualTo(productId);
    }

    // --- Link filtering (fetchLinksByItem) ---

    @Test
    void linkFiltering_targetWithoutItemRead_linkIsExcluded() {
        UUID productId = createProduct();
        UUID contributorId = createContributor(); // never marked
        createLink(productId, contributorId);
        var principal = newUserInEveryoneGroup();
        grantItemRead(productId, principal);

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        assertThat(result).isPresent();
        assertThat(result.get().links().values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void linkFiltering_linkWithoutLinkRead_isExcludedEvenWhenBothEndpointsAreVisible() {
        UUID productId = createProduct();
        UUID contributorId = createContributor();
        createLink(productId, contributorId);
        var principal = newUserInEveryoneGroup();
        grantItemRead(productId, principal);
        grantItemRead(contributorId, principal);
        // Deliberately no grantLinkRead(...) -- link:read is a separate, still-required condition.

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        assertThat(result.get().links().values().stream().flatMap(List::stream)).isEmpty();
    }

    @Test
    void linkFiltering_allThreeConditionsSatisfied_linkIsIncluded() {
        UUID productId = createProduct();
        UUID contributorId = createContributor();
        createLink(productId, contributorId);
        var principal = newUserInEveryoneGroup();
        grantItemRead(productId, principal);
        grantItemRead(contributorId, principal);
        grantLinkRead(productId, fixture.productPerspectiveId(), principal);

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        assertThat(result.get().links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId));
    }

    @Test
    void linkFiltering_superuserSeesUnmarkedLinksAndTargets() {
        UUID productId = createProduct();
        UUID contributorId = createContributor();
        createLink(productId, contributorId);

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", SUPERUSER);

        assertThat(result.get().links().values().stream().flatMap(List::stream))
                .anyMatch(link -> link.item().itemId().equals(contributorId));
    }
}
