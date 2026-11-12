package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.GrantStore;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerStore;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.register.RegisterStoreReader;
import org.ntrloc.graph.principal.NtrlocPrincipal;
import org.ntrloc.graph.principal.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end: grant a marker permission, assign the marker to a real item, verify a user's grants
// (UserGrantsView.mayReadItem) reflect it -- and that revoking either the grant or the marker assignment
// independently removes access. Link visibility is applied in RegisterStore's link query, covered by
// InstanceReadFilteringIntegrationTest.
class UserGrantsViewItemReadIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccessService accessService;

    @Autowired
    private RegisterStoreReader registerStore;

    @Autowired
    private GrantStore grantStore;

    @Autowired
    private MarkerStore markerStore;

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    // Not static/final: needs a real, backed security_user row now that ledger_entry.created_by
    // (Part E) has a NOT NULL FK to security_user.id -- created fresh per test in setUp() via the
    // already-autowired userStore, which a static initializer can't reach.
    private NtrlocPrincipal actor;

    @BeforeEach
    void setUpActor() {
        var user = userStore.createUser("Permission Test Actor", "pisr-actor-" + UUID.randomUUID() + "@example.com", true);
        actor = TestPrincipals.of(user.id(), user.displayName(), user.email(), Set.of(), true);
    }

    private UUID createItem() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), txn, actor.id(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createMarker() {
        return markerStore.createMarker("pisr-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private void grantItemRead(UUID markerId, String principalType, UUID principalId) {
        grantStore.setItemPermissions(grantStore.ensureMarkerGrant(markerId, principalType, principalId), true, false);
    }

    private boolean canRead(NtrlocPrincipal user, UUID itemId) {
        return accessService.getUserGrants(user).mayReadItem(() -> registerStore.getMarkerIdsForItem(itemId));
    }

    private NtrlocPrincipal principal(UUID id, String email, Set<UUID> groupIds, boolean isAdmin) {
        return TestPrincipals.of(id, email, email, groupIds, isAdmin);
    }

    @Test
    void itemWithNoMarker_isUnreadableForNonAdmin() {
        UUID itemId = createItem();
        var user = userStore.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);

        assertThat(canRead(principal(user.id(), user.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithNoMarker_isReadableForAdmin() {
        UUID itemId = createItem();

        assertThat(canRead(principal(UUID.randomUUID(), "root", Set.of(), true), itemId)).isTrue();
    }

    @Test
    void itemWithUserGroupGrantedMarker_isReadableForUserGroupMemberOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var group = userGroupStore.createUserGroup("pisr-" + UUID.randomUUID());
        grantItemRead(marker, "USER_GROUP", group.id());

        var member = userStore.createUser("Member", "pisr-" + UUID.randomUUID() + "@example.com", false);
        var nonMember = userStore.createUser("NonMember", "pisr-" + UUID.randomUUID() + "@example.com", false);

        assertThat(canRead(principal(member.id(), member.email(), Set.of(group.id()), false), itemId)).isTrue();
        assertThat(canRead(principal(nonMember.id(), nonMember.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithUserDirectGrantedMarker_isReadableForThatUserOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var grantedUser = userStore.createUser("Granted", "pisr-" + UUID.randomUUID() + "@example.com", false);
        var otherUser = userStore.createUser("Other", "pisr-" + UUID.randomUUID() + "@example.com", false);
        grantItemRead(marker, "USER", grantedUser.id());

        assertThat(canRead(principal(grantedUser.id(), grantedUser.email(), Set.of(), false), itemId)).isTrue();
        assertThat(canRead(principal(otherUser.id(), otherUser.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void revokingGrant_removesReadAccess() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var user = userStore.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = grantStore.ensureMarkerGrant(marker, "USER", user.id());
        grantStore.setItemPermissions(grantId, true, false);
        var userPrincipal = principal(user.id(), user.email(), Set.of(), false);
        assertThat(canRead(userPrincipal, itemId)).isTrue();

        grantStore.deleteMarkerGrant(grantId);

        assertThat(canRead(userPrincipal, itemId)).isFalse();
    }

    @Test
    void removingMarkerFromItem_removesReadAccessEvenIfGrantStillExists() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var user = userStore.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);
        grantItemRead(marker, "USER", user.id());
        var userPrincipal = principal(user.id(), user.email(), Set.of(), false);
        assertThat(canRead(userPrincipal, itemId)).isTrue();

        markerAssignmentService.removeItemMarker(itemId, marker, actor, "test reason");

        assertThat(canRead(userPrincipal, itemId)).isFalse();
    }
}
