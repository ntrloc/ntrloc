package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.TestPrincipals;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// End-to-end: grant a marker permission, assign the marker to a real item, verify
// PermissionService.canReadItem actually reflects it -- and that revoking either the grant or the
// marker assignment independently removes access. Link visibility no longer has a standalone
// PermissionService method (markers only ever apply to items now -- see
// docs/ntrloc-marker-admin-ui-design-notes.md; link:read is resolved in-memory in
// RegisterPartitionManager.fetchLinksByItem, covered by InstanceReadFilteringIntegrationTest).
class PermissionServiceInstanceReadIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PermissionService permissionService;

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    // Not static/final: needs a real, backed security_user row now that ledger_entry.created_by
    // (Part E) has a NOT NULL FK to security_user.id -- created fresh per test in setUp() via the
    // already-autowired securityRepo, which a static initializer can't reach.
    private NtrlocPrincipal actor;

    @BeforeEach
    void setUpActor() {
        var user = securityRepo.createUser("Permission Test Actor", "pisr-actor-" + UUID.randomUUID() + "@example.com", true);
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
        return authRepo.createMarker("pisr-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private void grantItemRead(UUID markerId, String principalType, UUID principalId) {
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(markerId, principalType, principalId), true, false);
    }

    private NtrlocPrincipal principal(UUID id, String email, Set<UUID> groupIds, boolean isSuperuser) {
        return TestPrincipals.of(id, email, email, groupIds, isSuperuser);
    }

    @Test
    void itemWithNoMarker_isUnreadableForNonSuperuser() {
        UUID itemId = createItem();
        var user = securityRepo.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);

        assertThat(permissionService.canReadItem(principal(user.id(), user.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithNoMarker_isReadableForSuperuser() {
        UUID itemId = createItem();

        assertThat(permissionService.canReadItem(principal(UUID.randomUUID(), "root", Set.of(), true), itemId)).isTrue();
    }

    @Test
    void itemWithUserGroupGrantedMarker_isReadableForUserGroupMemberOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var group = securityRepo.createUserGroup("pisr-" + UUID.randomUUID());
        grantItemRead(marker, "USER_GROUP", group.id());

        var member = securityRepo.createUser("Member", "pisr-" + UUID.randomUUID() + "@example.com", false);
        var nonMember = securityRepo.createUser("NonMember", "pisr-" + UUID.randomUUID() + "@example.com", false);

        assertThat(permissionService.canReadItem(principal(member.id(), member.email(), Set.of(group.id()), false), itemId)).isTrue();
        assertThat(permissionService.canReadItem(principal(nonMember.id(), nonMember.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithUserDirectGrantedMarker_isReadableForThatUserOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var grantedUser = securityRepo.createUser("Granted", "pisr-" + UUID.randomUUID() + "@example.com", false);
        var otherUser = securityRepo.createUser("Other", "pisr-" + UUID.randomUUID() + "@example.com", false);
        grantItemRead(marker, "USER", grantedUser.id());

        assertThat(permissionService.canReadItem(principal(grantedUser.id(), grantedUser.email(), Set.of(), false), itemId)).isTrue();
        assertThat(permissionService.canReadItem(principal(otherUser.id(), otherUser.email(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void revokingGrant_removesReadAccess() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var user = securityRepo.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());
        authRepo.setItemPermissions(grantId, true, false);
        var userPrincipal = principal(user.id(), user.email(), Set.of(), false);
        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isTrue();

        authRepo.deleteMarkerGrant(grantId);

        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isFalse();
    }

    @Test
    void removingMarkerFromItem_removesReadAccessEvenIfGrantStillExists() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, actor, "test reason");
        var user = securityRepo.createUser("User", "pisr-" + UUID.randomUUID() + "@example.com", false);
        grantItemRead(marker, "USER", user.id());
        var userPrincipal = principal(user.id(), user.email(), Set.of(), false);
        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isTrue();

        markerAssignmentService.removeItemMarker(itemId, marker, actor, "test reason");

        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isFalse();
    }
}
