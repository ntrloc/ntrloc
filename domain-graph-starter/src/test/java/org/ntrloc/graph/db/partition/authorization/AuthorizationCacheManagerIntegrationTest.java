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

    @Test
    void getGrantedMarkerIds_returnsMarkersGrantedDirectlyAndViaGroup() {
        var directMarker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var groupMarker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var ungrantedMarker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        var group = securityRepo.createGroup("acm-" + UUID.randomUUID());

        authRepo.grantMarker(directMarker.id(), "USER", user.id(), PermissionService.ITEM_READ, null);
        authRepo.grantMarker(groupMarker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);

        Set<UUID> granted = cache.getGrantedMarkerIds(user.id(), Set.of(group.id()), PermissionService.ITEM_READ);

        assertThat(granted).contains(directMarker.id(), groupMarker.id());
        assertThat(granted).doesNotContain(ungrantedMarker.id());
    }

    @Test
    void getGrantedPropertyIdsByMarker_scopesToSpecificProperties() {
        var marker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);

        authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.PROPERTY_READ, fixture.namePropertyId());
        authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.PROPERTY_READ, fixture.colorPropertyId());

        Map<UUID, Set<UUID>> byMarker = cache.getGrantedPropertyIdsByMarker(user.id(), Set.of(), PermissionService.PROPERTY_READ);

        assertThat(byMarker.get(marker.id())).containsExactlyInAnyOrder(fixture.namePropertyId(), fixture.colorPropertyId());
    }

    @Test
    void grantingAMarker_isVisibleInTheCacheImmediately_noRestartNeeded() {
        var marker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        assertThat(cache.getGrantedMarkerIds(user.id(), Set.of(), PermissionService.ITEM_READ)).doesNotContain(marker.id());

        authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, null);

        assertThat(cache.getGrantedMarkerIds(user.id(), Set.of(), PermissionService.ITEM_READ)).contains(marker.id());
    }

    @Test
    void revokingAGrant_removesItFromTheCacheImmediately() {
        var marker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        authRepo.grantMarker(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, null);
        assertThat(cache.getGrantedMarkerIds(user.id(), Set.of(), PermissionService.ITEM_READ)).contains(marker.id());
        UUID grantId = authRepo.findMarkerGrant(marker.id(), "USER", user.id(), PermissionService.ITEM_READ, null).orElseThrow();

        authRepo.deleteMarkerGrant(grantId);

        assertThat(cache.getGrantedMarkerIds(user.id(), Set.of(), PermissionService.ITEM_READ)).doesNotContain(marker.id());
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
        var marker = authRepo.createMarker("acm-" + UUID.randomUUID(), "d");
        var group = securityRepo.createGroup("acm-" + UUID.randomUUID());
        authRepo.grantMarker(marker.id(), "GROUP", group.id(), PermissionService.ITEM_READ, null);

        // Joining the group happens strictly after the grant/cache-refresh above -- no grant or
        // revoke fires afterward, so nothing re-triggers refreshCache(). If this still resolves
        // correctly, it's proof group membership is consulted at union-time (read time), not baked
        // into what's cached -- exactly the design point this table shape was chosen for.
        var user = securityRepo.createUser("acm-" + UUID.randomUUID(), "User", null, false);
        securityRepo.addUserToGroup(user.id(), group.id());

        assertThat(cache.getGrantedMarkerIds(user.id(), Set.of(group.id()), PermissionService.ITEM_READ)).contains(marker.id());
    }
}
