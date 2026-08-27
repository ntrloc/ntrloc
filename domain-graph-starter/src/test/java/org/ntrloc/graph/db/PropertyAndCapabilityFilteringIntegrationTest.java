package org.ntrloc.graph.db;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.DefaultGroupInitializer;
import org.ntrloc.graph.db.partition.authorization.MarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository.MarkerRow;
import org.ntrloc.graph.db.projection.LinkProjectionSpec;
import org.ntrloc.graph.db.projection.SingleItemProjectionSpec;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Slice C: mode-2 (field/capability-affecting) permission resolution -- property:read/write and
// link_property:read/write filtering, plus item:delete/link:delete capability flags. Every
// non-superuser fixture user is added to "everyone" (type-level read via DefaultGroupInitializer's
// default-open grant) and separately granted item:read/link:read on the specific instance under
// test, so each test isolates the property/capability gate specifically -- the mode-1 existence
// gate is a prerequisite, already covered by InstanceReadFilteringIntegrationTest.
class PropertyAndCapabilityFilteringIntegrationTest extends AbstractIntegrationTest {

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

    private UUID createProduct(String name, String color) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(),
                Map.of(fixture.namePropertyId(), name, fixture.colorPropertyId(), color), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createContributor() {
        UUID id = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(id, fixture.contributorTypeId(),
                Map.of(fixture.contributorNamePropertyId(), "Ada"), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return id;
    }

    private UUID createLink(UUID productId, UUID contributorId, String role) {
        UUID linkId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(linkId, fixture.linkTypeId(),
                new LinkEndpoint(fixture.productPerspectiveId(), productId),
                new LinkEndpoint(fixture.contributorPerspectiveId(), contributorId),
                Map.of(fixture.rolePropertyId(), role), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return linkId;
    }

    private NtrlocPrincipal newUserInEveryoneGroup() {
        var user = securityRepo.createUser("pcf-" + UUID.randomUUID(), "Restricted", null, false);
        UUID everyoneGroupId = defaultGroupInitializer.getDefaultGroupId();
        securityRepo.addUserToGroup(user.id(), everyoneGroupId);
        return new ResolvedPrincipal(user.id(), user.externalId(), user.externalId(), null, Set.of(everyoneGroupId), false);
    }

    private static final NtrlocPrincipal SUPERUSER =
            new ResolvedPrincipal(UUID.randomUUID(), "pcf-root", "Root", null, Set.of(), true);

    private MarkerRow markerOnItem(UUID itemId) {
        var marker = authRepo.createMarker("pcf-" + UUID.randomUUID(), "test fixture");
        markerAssignmentService.addItemMarker(itemId, marker.id(), "test-actor", "test reason");
        return marker;
    }

    private MarkerRow markerOnLink(UUID linkId) {
        var marker = authRepo.createMarker("pcf-" + UUID.randomUUID(), "test fixture");
        markerAssignmentService.addLinkMarker(linkId, marker.id(), "test-actor", "test reason");
        return marker;
    }

    private void grant(UUID markerId, NtrlocPrincipal principal, String operation, UUID propertyId) {
        authRepo.grantMarker(markerId, "USER", principal.id(), operation, propertyId);
    }

    // --- Item property read filtering ---

    @Test
    void propertyWithNoReadGrant_isAbsentFromResponse() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneGroup();
        var marker = markerOnItem(productId);
        grant(marker.id(), principal, PermissionService.ITEM_READ, null);
        grant(marker.id(), principal, PermissionService.PROPERTY_READ, fixture.namePropertyId());
        // color deliberately not granted

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result.get().properties()).containsKey("name");
        assertThat(result.get().properties()).doesNotContainKey("color");
    }

    @Test
    void propertyGrantedViaOneOfSeveralMarkers_isPresent_unionSemantics() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneGroup();
        var readMarker = markerOnItem(productId);
        grant(readMarker.id(), principal, PermissionService.ITEM_READ, null);
        var nameMarker = markerOnItem(productId);
        grant(nameMarker.id(), principal, PermissionService.PROPERTY_READ, fixture.namePropertyId());
        var colorMarker = markerOnItem(productId);
        grant(colorMarker.id(), principal, PermissionService.PROPERTY_READ, fixture.colorPropertyId());

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result.get().properties()).containsKeys("name", "color");
    }

    @Test
    void propertyWriteOnly_withoutReadGrant_isStillAbsentFromReadResponse() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneGroup();
        var marker = markerOnItem(productId);
        grant(marker.id(), principal, PermissionService.ITEM_READ, null);
        grant(marker.id(), principal, PermissionService.PROPERTY_WRITE, fixture.namePropertyId());
        // No property:read grant for "name" -- write alone must not leak the value into a read.

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result.get().properties()).doesNotContainKey("name");
    }

    @Test
    void superuserSeesAllPropertiesRegardlessOfGrants() {
        UUID productId = createProduct("Widget", "red");

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", SUPERUSER);

        assertThat(result.get().properties()).containsKeys("name", "color");
    }

    // --- Item capability flags (edit / delete) ---

    @Test
    void editListReflectsPropertyWriteGrants() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneGroup();
        var marker = markerOnItem(productId);
        grant(marker.id(), principal, PermissionService.ITEM_READ, null);
        grant(marker.id(), principal, PermissionService.PROPERTY_WRITE, fixture.namePropertyId());

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result.get().permissions().edit()).containsExactly("name");
        assertThat(result.get().permissions().delete()).isFalse();
    }

    @Test
    void deleteCapabilityReflectsItemDeleteGrant() {
        UUID productId = createProduct("Widget", "red");
        var principal = newUserInEveryoneGroup();
        var marker = markerOnItem(productId);
        grant(marker.id(), principal, PermissionService.ITEM_READ, null);
        grant(marker.id(), principal, PermissionService.ITEM_DELETE, null);

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", principal);

        assertThat(result.get().permissions().delete()).isTrue();
    }

    @Test
    void superuserPermissions_wildcardEditAndDeleteTrue() {
        UUID productId = createProduct("Widget", "red");

        var result = entityManager.project(new SingleItemProjectionSpec("CoordinatorTestProduct", productId), "http://binary", SUPERUSER);

        assertThat(result.get().permissions().edit()).containsExactly("*");
        assertThat(result.get().permissions().delete()).isTrue();
    }

    // --- Link property filtering and link capability, distinguished from item-level grants ---

    @Test
    void linkPropertyWithNoReadGrant_isAbsentFromResponse() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        UUID linkId = createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneGroup();
        var itemMarker = markerOnItem(productId);
        grant(itemMarker.id(), principal, PermissionService.ITEM_READ, null);
        var targetMarker = markerOnItem(contributorId);
        grant(targetMarker.id(), principal, PermissionService.ITEM_READ, null);
        var linkMarker = markerOnLink(linkId);
        grant(linkMarker.id(), principal, PermissionService.LINK_READ, null);
        // Deliberately no link_property:read grant for "role".

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.properties()).doesNotContainKey("role");
    }

    @Test
    void linkPropertyWithReadGrant_isPresent() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        UUID linkId = createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneGroup();
        var itemMarker = markerOnItem(productId);
        grant(itemMarker.id(), principal, PermissionService.ITEM_READ, null);
        var targetMarker = markerOnItem(contributorId);
        grant(targetMarker.id(), principal, PermissionService.ITEM_READ, null);
        var linkMarker = markerOnLink(linkId);
        grant(linkMarker.id(), principal, PermissionService.LINK_READ, null);
        grant(linkMarker.id(), principal, PermissionService.LINK_PROPERTY_READ, fixture.rolePropertyId());

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.properties()).containsEntry("role", "author");
    }

    @Test
    void linkDeleteCapability_governedByLinkDeleteNotItemDelete() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        UUID linkId = createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneGroup();
        var itemMarker = markerOnItem(productId);
        grant(itemMarker.id(), principal, PermissionService.ITEM_READ, null);
        grant(itemMarker.id(), principal, PermissionService.ITEM_DELETE, null); // item:delete, not link:delete
        var targetMarker = markerOnItem(contributorId);
        grant(targetMarker.id(), principal, PermissionService.ITEM_READ, null);
        var linkMarker = markerOnLink(linkId);
        grant(linkMarker.id(), principal, PermissionService.LINK_READ, null);
        grant(linkMarker.id(), principal, PermissionService.LINK_DELETE, null);

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.permissions().delete()).isTrue();
        // The linked (contributor) item's own delete capability is separate -- no item:delete
        // grant was given on the contributor, so it must stay false even though the link is
        // deletable and the outer product is.
        assertThat(link.item().permissions().delete()).isFalse();
    }

    @Test
    void linkPropertyWriteGrant_doesNotLeakIntoLinkedItemsEditList() {
        UUID productId = createProduct("Widget", "red");
        UUID contributorId = createContributor();
        UUID linkId = createLink(productId, contributorId, "author");
        var principal = newUserInEveryoneGroup();
        var itemMarker = markerOnItem(productId);
        grant(itemMarker.id(), principal, PermissionService.ITEM_READ, null);
        var targetMarker = markerOnItem(contributorId);
        grant(targetMarker.id(), principal, PermissionService.ITEM_READ, null);
        var linkMarker = markerOnLink(linkId);
        grant(linkMarker.id(), principal, PermissionService.LINK_READ, null);
        grant(linkMarker.id(), principal, PermissionService.LINK_PROPERTY_WRITE, fixture.rolePropertyId());

        var result = entityManager.project(
                new SingleItemProjectionSpec("CoordinatorTestProduct", productId, Map.of("products", new LinkProjectionSpec(null))),
                "http://binary", principal);

        var link = result.get().links().values().stream().flatMap(List::stream).findFirst().orElseThrow();
        assertThat(link.permissions().edit()).containsExactly("role");
        assertThat(link.item().permissions().edit()).isEmpty();
    }
}
