package org.ntrloc.graph.db.partition.authorization;

import com.hazelcast.topic.ITopic;
import org.ntrloc.graph.cluster.ClusterService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

// Caches authorization_item_type_grant/authorization_grant -- schema-sized, admin-curated, rarely
// changing -- never register_item_marker/register_link_marker (data-sized; those stay real
// per-page queries in RegisterPartitionManager/AuthorizationRepository). See
// docs/ntrloc-acl-design-notes.md "Caching grant definitions, not item-marker assignments".
//
// Mirrors SchemaManager's AtomicReference + Hazelcast ITopic pattern exactly -- same
// self-publishing-member filter, same rebuild-then-publish shape in refreshCache().
//
// Cached at the grant-source level -- Map<PrincipalOpKey, ...> keyed by (principalType,
// principalId, operation), never at the resolved-effective-principal level. Resolving a specific
// principal's effective set is a request-time union over their own key plus each of their groups'
// keys -- a few hashmap lookups, no I/O. This is deliberate: it means group-membership changes
// need zero cache invalidation (membership is only consulted at union-time, not baked into what's
// cached), and a grant change on one group never has to invalidate every member's own entry.
@Service
public class AuthorizationCacheManager {

    private static final String AUTHORIZATION_CHANGED_TOPIC = "authorizationChanged";

    private record PrincipalOpKey(String principalType, UUID principalId, String operation) {}

    private record Snapshot(
            Map<PrincipalOpKey, Set<UUID>> itemTypeGrants,
            Map<PrincipalOpKey, Set<UUID>> unscopedMarkerGrants,
            Map<PrincipalOpKey, Map<UUID, Set<UUID>>> propertyScopedMarkerGrants) {
    }

    private final AuthorizationRepository authRepo;
    private final ClusterService clusterService;
    private final ITopic<String> authorizationChangedTopic;

    // AtomicReference, not a plain volatile field: rebuildCache() can run on the Hazelcast
    // message-listener thread (a remote node's change arriving) as well as on whatever thread
    // triggered the write -- same reasoning as SchemaManager's cachedAdminSchema.
    private final AtomicReference<Snapshot> cache = new AtomicReference<>();

    public AuthorizationCacheManager(AuthorizationRepository authRepo, ClusterService clusterService) {
        this.authRepo = authRepo;
        this.clusterService = clusterService;
        rebuildCache();

        this.authorizationChangedTopic = clusterService.getTopic(AUTHORIZATION_CHANGED_TOPIC);
        authorizationChangedTopic.addMessageListener(message -> {
            if (!message.getPublishingMember().equals(clusterService.getLocalMember())) {
                rebuildCache();
            }
        });
    }

    private void rebuildCache() {
        List<AuthorizationRepository.ItemTypeGrantRow> itemTypeRows = authRepo.getAllItemTypeGrants();
        Map<PrincipalOpKey, Set<UUID>> itemTypeGrants = new HashMap<>();
        for (var row : itemTypeRows) {
            itemTypeGrants.computeIfAbsent(new PrincipalOpKey(row.principalType(), row.principalId(), row.permission()), k -> new HashSet<>())
                    .add(row.itemTypeId());
        }

        List<AuthorizationRepository.MarkerGrantRow> markerRows = authRepo.getAllMarkerGrants();
        Map<PrincipalOpKey, Set<UUID>> unscoped = new HashMap<>();
        Map<PrincipalOpKey, Map<UUID, Set<UUID>>> propertyScoped = new HashMap<>();
        for (var row : markerRows) {
            var key = new PrincipalOpKey(row.principalType(), row.principalId(), row.operation());
            if (row.propertyId() == null) {
                unscoped.computeIfAbsent(key, k -> new HashSet<>()).add(row.markerId());
            } else {
                propertyScoped.computeIfAbsent(key, k -> new HashMap<>())
                        .computeIfAbsent(row.markerId(), k -> new HashSet<>())
                        .add(row.propertyId());
            }
        }

        cache.set(new Snapshot(itemTypeGrants, unscoped, propertyScoped));
    }

    // Called by AuthorizationRepository's own write methods after every grant/revoke -- rebuilds
    // this node's copy immediately (so a "write then read" in the same request/test is always
    // consistent) and publishes so every other cluster node does the same.
    public void refreshCache() {
        rebuildCache();
        authorizationChangedTopic.publish(UUID.randomUUID().toString());
    }

    public boolean hasItemTypeGrant(UUID userId, Set<UUID> groupIds, UUID itemTypeId, String permission) {
        return getGrantedItemTypeIds(userId, groupIds, permission).contains(itemTypeId);
    }

    public Set<UUID> getGrantedItemTypeIds(UUID userId, Set<UUID> groupIds, String permission) {
        return effectiveSet(cache.get().itemTypeGrants(), userId, groupIds, permission);
    }

    public Set<UUID> getGrantedMarkerIds(UUID userId, Set<UUID> groupIds, String operation) {
        return effectiveSet(cache.get().unscopedMarkerGrants(), userId, groupIds, operation);
    }

    public Map<UUID, Set<UUID>> getGrantedPropertyIdsByMarker(UUID userId, Set<UUID> groupIds, String operation) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        var scoped = cache.get().propertyScopedMarkerGrants();
        mergeInto(result, scoped.get(new PrincipalOpKey("USER", userId, operation)));
        for (UUID groupId : groupIds) {
            mergeInto(result, scoped.get(new PrincipalOpKey("GROUP", groupId, operation)));
        }
        return result;
    }

    private Set<UUID> effectiveSet(Map<PrincipalOpKey, Set<UUID>> index, UUID userId, Set<UUID> groupIds, String operation) {
        Set<UUID> result = new HashSet<>(index.getOrDefault(new PrincipalOpKey("USER", userId, operation), Set.of()));
        for (UUID groupId : groupIds) {
            result.addAll(index.getOrDefault(new PrincipalOpKey("GROUP", groupId, operation), Set.of()));
        }
        return result;
    }

    private void mergeInto(Map<UUID, Set<UUID>> target, Map<UUID, Set<UUID>> source) {
        if (source == null) return;
        source.forEach((markerId, propertyIds) -> target.computeIfAbsent(markerId, k -> new HashSet<>()).addAll(propertyIds));
    }
}
