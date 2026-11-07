package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.AccessStore;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// AccessStore answers grant lookups from an in-memory snapshot it rebuilds after every grant write. Each
// test writes a grant and reads it back in the same method, proving a lookup is never stale relative to a
// write that just happened -- the property the rest of the marker/permission suite depends on.
class AccessStoreGrantLookupIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AccessStore authRepo;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createMarker() {
        return authRepo.createMarker("acm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    @Test
    void getGrantedItemReadMarkerIds_returnsMarkersGrantedDirectlyAndViaUserGroup() {
        UUID directMarker = createMarker();
        UUID groupMarker = createMarker();
        UUID ungrantedMarker = createMarker();
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        var group = securityRepo.createUserGroup("acm-" + UUID.randomUUID());

        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(directMarker, "USER", user.id()), true, false);
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(groupMarker, "USER_GROUP", group.id()), true, false);

        Set<UUID> granted = authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()));

        assertThat(granted).contains(directMarker, groupMarker);
        assertThat(granted).doesNotContain(ungrantedMarker);
    }

    @Test
    void getPropertyReadGrantsByMarker_scopesToSpecificProperties() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());

        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        authRepo.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        Map<UUID, Set<UUID>> byMarker = authRepo.getPropertyReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactlyInAnyOrder(fixture.namePropertyId(), fixture.colorPropertyId());
    }

    @Test
    void getLinkPerspectiveReadGrantsByMarker_scopesToSpecificPerspectives() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());

        authRepo.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);

        Map<UUID, Set<UUID>> byMarker = authRepo.getLinkPerspectiveReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactly(fixture.productPerspectiveId());
    }

    @Test
    void grantingAMarker_isVisibleImmediately_noRestartNeeded() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);

        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(marker, "USER", user.id()), true, false);

        assertThat(authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);
    }

    @Test
    void revokingAGrant_takesEffectImmediately() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());
        authRepo.setItemPermissions(grantId, true, false);
        assertThat(authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);

        authRepo.deleteMarkerGrant(grantId);

        assertThat(authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);
    }

    @Test
    void itemTypeGrants_areVisibleImmediately() {
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        assertThat(authRepo.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isFalse();

        authRepo.grantItemType(fixture.productTypeId(), "USER", user.id(), PermissionService.ITEM_TYPE_READ);

        assertThat(authRepo.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isTrue();
        assertThat(authRepo.getGrantedItemTypeIds(user.id(), Set.of(), PermissionService.ITEM_TYPE_READ)).contains(fixture.productTypeId());
    }

    @Test
    void groupMembershipChangeAlone_needsNoRebuild_membershipResolvedAtReadTime() {
        UUID marker = createMarker();
        var group = securityRepo.createUserGroup("acm-" + UUID.randomUUID());
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(marker, "USER_GROUP", group.id()), true, false);

        // Joining the group happens strictly after the grant above -- no grant or revoke fires
        // afterward, so nothing rebuilds the snapshot. If this still resolves
        // correctly, it's proof group membership is consulted at union-time (read time), not baked
        // into what's cached -- exactly the design point this table shape was chosen for.
        var user = securityRepo.createUser("User", "acm-" + UUID.randomUUID() + "@example.com", false);
        securityRepo.addUserToUserGroup(user.id(), group.id());

        assertThat(authRepo.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()))).contains(marker);
    }
}
