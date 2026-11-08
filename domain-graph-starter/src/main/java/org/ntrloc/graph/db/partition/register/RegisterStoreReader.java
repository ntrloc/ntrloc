package org.ntrloc.graph.db.partition.register;

import org.ntrloc.graph.access.FactResolver;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reading the register: projections (as an {@link ExplicitProjection}), and the committed current values the
 * write side and marker pipelines check against. Anything may read it. See RegisterStore for each method.
 */
public interface RegisterStoreReader extends FactResolver {

    RegisterRows query(ExplicitProjection projection);

    Optional<UUID> findItemTypeId(UUID itemId);

    Map<UUID, Object> currentPropertiesById(UUID itemId, UUID itemTypeId);

    Map<UUID, Object> resolveMergedPropertiesById(UUID itemId, UUID itemTypeId, Map<UUID, Object> propertiesDiff);

    Map<UUID, UUID> currentStateIds(UUID itemId);

    Optional<UUID> findLinkTypeId(UUID linkId);

    boolean linkExists(UUID linkId);

    Optional<UUID> findExistingLinkId(RegisterLinkEndpoint endpointA, RegisterLinkEndpoint endpointB);

    List<RegisterLinkEndpoint> findLinkEndpoints(UUID linkId);

    Map<UUID, Object> currentLinkPropertiesById(UUID linkId, UUID linkTypeId);

    Map<UUID, Object> resolveMergedLinkPropertiesById(UUID linkId, UUID linkTypeId, Map<UUID, Object> propertiesDiff);

    int countLinksForPerspective(UUID itemId, UUID perspectiveId);

    List<RegisterLinkedItem> findLinksForItem(UUID itemId);

    /** A committed link from an item: the link, and the item at its other end. */
    record RegisterLinkedItem(UUID linkId, UUID connectedItemId) {}
}
