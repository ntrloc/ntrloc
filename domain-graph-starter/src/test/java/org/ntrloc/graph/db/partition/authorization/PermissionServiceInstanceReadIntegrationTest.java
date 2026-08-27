package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.ResolvedPrincipal;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

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
    private MarkerAssignmentService markerAssignmentService;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createItem() {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, fixture.productTypeId(), Map.of(fixture.namePropertyId(), "Widget"), Map.of(), Set.of())), txn, null);
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    private UUID createMarker() {
        return authRepo.createMarker("pisr-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    private void grantItemRead(UUID markerId, String principalType, UUID principalId) {
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(markerId, principalType, principalId), true, false);
    }

    private NtrlocPrincipal principal(UUID id, String externalId, Set<UUID> groupIds, boolean isSuperuser) {
        return new ResolvedPrincipal(id, externalId, externalId, null, groupIds, isSuperuser);
    }

    @Test
    void itemWithNoMarker_isUnreadableForNonSuperuser() {
        UUID itemId = createItem();
        var user = securityRepo.createUser("pisr-" + UUID.randomUUID(), "User", null, false);

        assertThat(permissionService.canReadItem(principal(user.id(), user.externalId(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithNoMarker_isReadableForSuperuser() {
        UUID itemId = createItem();

        assertThat(permissionService.canReadItem(principal(UUID.randomUUID(), "root", Set.of(), true), itemId)).isTrue();
    }

    @Test
    void itemWithGroupGrantedMarker_isReadableForGroupMemberOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, "test-actor", "test reason");
        var group = securityRepo.createGroup("pisr-" + UUID.randomUUID());
        grantItemRead(marker, "GROUP", group.id());

        var member = securityRepo.createUser("pisr-" + UUID.randomUUID(), "Member", null, false);
        var nonMember = securityRepo.createUser("pisr-" + UUID.randomUUID(), "NonMember", null, false);

        assertThat(permissionService.canReadItem(principal(member.id(), member.externalId(), Set.of(group.id()), false), itemId)).isTrue();
        assertThat(permissionService.canReadItem(principal(nonMember.id(), nonMember.externalId(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void itemWithUserDirectGrantedMarker_isReadableForThatUserOnly() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, "test-actor", "test reason");
        var grantedUser = securityRepo.createUser("pisr-" + UUID.randomUUID(), "Granted", null, false);
        var otherUser = securityRepo.createUser("pisr-" + UUID.randomUUID(), "Other", null, false);
        grantItemRead(marker, "USER", grantedUser.id());

        assertThat(permissionService.canReadItem(principal(grantedUser.id(), grantedUser.externalId(), Set.of(), false), itemId)).isTrue();
        assertThat(permissionService.canReadItem(principal(otherUser.id(), otherUser.externalId(), Set.of(), false), itemId)).isFalse();
    }

    @Test
    void revokingGrant_removesReadAccess() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, "test-actor", "test reason");
        var user = securityRepo.createUser("pisr-" + UUID.randomUUID(), "User", null, false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());
        authRepo.setItemPermissions(grantId, true, false);
        var userPrincipal = principal(user.id(), user.externalId(), Set.of(), false);
        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isTrue();

        authRepo.deleteMarkerGrant(grantId);

        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isFalse();
    }

    @Test
    void removingMarkerFromItem_removesReadAccessEvenIfGrantStillExists() {
        UUID itemId = createItem();
        UUID marker = createMarker();
        markerAssignmentService.addItemMarker(itemId, marker, "test-actor", "test reason");
        var user = securityRepo.createUser("pisr-" + UUID.randomUUID(), "User", null, false);
        grantItemRead(marker, "USER", user.id());
        var userPrincipal = principal(user.id(), user.externalId(), Set.of(), false);
        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isTrue();

        markerAssignmentService.removeItemMarker(itemId, marker, "test-actor", "test reason");

        assertThat(permissionService.canReadItem(userPrincipal, itemId)).isFalse();
    }
}
