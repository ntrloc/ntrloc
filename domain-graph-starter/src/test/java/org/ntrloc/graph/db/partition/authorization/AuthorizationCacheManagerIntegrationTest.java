package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Slice D: AuthorizationRepository's write methods trigger AuthorizationCacheManager.refreshCache()
// synchronously (see that repository's own comment on the @Lazy-injected cache manager), so every
// test here writes a grant through the repository and reads it back through the cache in the same
// method -- proving the cache is never stale relative to a write that just happened, which is the
// property every other test in this session's marker/permission suite silently depends on.
class AuthorizationCacheManagerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private AuthorizationRepository authRepo;

    @Autowired
    private AuthorizationCacheManager cache;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    private UUID createMarker() {
        return authRepo.createMarker("acm-" + UUID.randomUUID(), "d", "ITEM_TYPE", fixture.productTypeId()).id();
    }

    @Test
    void getGrantedItemReadMarkerIds_returnsMarkersGrantedDirectlyAndViaGroup() {
        UUID directMarker = createMarker();
        UUID groupMarker = createMarker();
        UUID ungrantedMarker = createMarker();
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        var group = securityRepo.createGroup("acm-" + UUID.randomUUID());

        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(directMarker, "USER", user.id()), true, false);
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(groupMarker, "GROUP", group.id()), true, false);

        Set<UUID> granted = cache.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()));

        assertThat(granted).contains(directMarker, groupMarker);
        assertThat(granted).doesNotContain(ungrantedMarker);
    }

    @Test
    void getPropertyReadGrantsByMarker_scopesToSpecificProperties() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());

        authRepo.grantPropertyAccess(grantId, fixture.namePropertyId(), true, false);
        authRepo.grantPropertyAccess(grantId, fixture.colorPropertyId(), true, false);

        Map<UUID, Set<UUID>> byMarker = cache.getPropertyReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactlyInAnyOrder(fixture.namePropertyId(), fixture.colorPropertyId());
    }

    @Test
    void getLinkPerspectiveReadGrantsByMarker_scopesToSpecificPerspectives() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());

        authRepo.grantLinkPerspectiveAccess(grantId, fixture.productPerspectiveId(), false, true, false);

        Map<UUID, Set<UUID>> byMarker = cache.getLinkPerspectiveReadGrantsByMarker(user.id(), Set.of());

        assertThat(byMarker.get(marker)).containsExactly(fixture.productPerspectiveId());
    }

    @Test
    void grantingAMarker_isVisibleInTheCacheImmediately_noRestartNeeded() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        assertThat(cache.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);

        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(marker, "USER", user.id()), true, false);

        assertThat(cache.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);
    }

    @Test
    void revokingAGrant_removesItFromTheCacheImmediately() {
        UUID marker = createMarker();
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        UUID grantId = authRepo.ensureMarkerGrant(marker, "USER", user.id());
        authRepo.setItemPermissions(grantId, true, false);
        assertThat(cache.getGrantedItemReadMarkerIds(user.id(), Set.of())).contains(marker);

        authRepo.deleteMarkerGrant(grantId);

        assertThat(cache.getGrantedItemReadMarkerIds(user.id(), Set.of())).doesNotContain(marker);
    }

    @Test
    void itemTypeGrants_areAlsoCached() {
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        assertThat(cache.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isFalse();

        authRepo.grantItemType(fixture.productTypeId(), "USER", user.id(), PermissionService.ITEM_TYPE_READ);

        assertThat(cache.hasItemTypeGrant(user.id(), Set.of(), fixture.productTypeId(), PermissionService.ITEM_TYPE_READ)).isTrue();
        assertThat(cache.getGrantedItemTypeIds(user.id(), Set.of(), PermissionService.ITEM_TYPE_READ)).contains(fixture.productTypeId());
    }

    @Test
    void groupMembershipChangeAlone_needsNoCacheRebuild_membershipResolvedAtReadTime() {
        UUID marker = createMarker();
        var group = securityRepo.createGroup("acm-" + UUID.randomUUID());
        authRepo.setItemPermissions(authRepo.ensureMarkerGrant(marker, "GROUP", group.id()), true, false);

        // Joining the group happens strictly after the grant/cache-refresh above -- no grant or
        // revoke fires afterward, so nothing re-triggers refreshCache(). If this still resolves
        // correctly, it's proof group membership is consulted at union-time (read time), not baked
        // into what's cached -- exactly the design point this table shape was chosen for.
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        securityRepo.addUserToGroup(user.id(), group.id());

        assertThat(cache.getGrantedItemReadMarkerIds(user.id(), Set.of(group.id()))).contains(marker);
    }
}
