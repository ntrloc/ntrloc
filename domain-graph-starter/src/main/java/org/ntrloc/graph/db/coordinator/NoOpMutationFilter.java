package org.ntrloc.graph.db.coordinator;

import org.ntrloc.graph.db.partition.ledger.ItemUpdateEntry;
import org.ntrloc.graph.db.partition.ledger.LedgerEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkDeleteEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.ledger.LinkUpdateEntry;
import org.ntrloc.graph.db.partition.register.RegisterLinkEndpoint;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

// A mutation describes a *desired* state, not a command to blindly record -- if the system is
// already in the state being asked for, there's nothing to do and nothing worth ledgering. This
// runs first in LedgerRegisterCoordinatorImpl.prepare(), before cascade expansion and before
// pipeline marker evaluation: a no-op property "change" must never register as a changed fact for
// PipelineMarkerDecisionService's watch-scoped lookup either, or a repeated no-op mutation would
// still pay to re-evaluate every pipeline watching that property.
//
// An entry that turns out to have nothing left to do is dropped entirely -- not ledgered as an
// empty facet. A request that's entirely no-op still prepares and commits normally (see
// LedgerRegisterCoordinatorImpl's own class comment); it just produces zero ledger rows.
//
// ItemCreateEntry/ItemDeleteEntry are never filtered here -- a freshly minted id can't already
// equal anything, and "delete an item that's already gone" is a different question this doesn't
// attempt to answer.
@Component
public class NoOpMutationFilter {

    private final RegisterPartitionManager registerPartitionManager;
    private final SchemaRepository schemaRepository;

    public NoOpMutationFilter(RegisterPartitionManager registerPartitionManager, SchemaRepository schemaRepository) {
        this.registerPartitionManager = registerPartitionManager;
        this.schemaRepository = schemaRepository;
    }

    public List<LedgerEntry> filter(List<LedgerEntry> entries) {
        return entries.stream()
                .map(this::filterEntry)
                .filter(Objects::nonNull)
                .toList();
    }

    // Returns null for an entry that turned out to have nothing left to do.
    private LedgerEntry filterEntry(LedgerEntry entry) {
        if (entry instanceof ItemUpdateEntry e) return filterItemUpdate(e);
        if (entry instanceof LinkCreateEntry e) return filterLinkCreate(e);
        if (entry instanceof LinkUpdateEntry e) return filterLinkUpdate(e);
        if (entry instanceof LinkDeleteEntry e) return filterLinkDelete(e);
        return entry;
    }

    private ItemUpdateEntry filterItemUpdate(ItemUpdateEntry e) {
        UUID itemTypeId = registerPartitionManager.findItemTypeId(e.itemId()).orElse(null);
        if (itemTypeId == null) return e; // defensive only -- an update always targets an existing item

        Map<UUID, Object> properties = e.properties().isEmpty()
                ? Map.of()
                : dropNoOpProperties(e.properties(), registerPartitionManager.currentPropertiesById(e.itemId(), itemTypeId),
                        registerPartitionManager.binaryPropertyIdsForItemType(itemTypeId));

        Map<UUID, UUID> stateChanges = Map.of();
        Set<UUID> stateMachinesEnded = Set.of();
        if (!e.stateChanges().isEmpty() || !e.stateMachinesEnded().isEmpty()) {
            Map<UUID, UUID> currentStateIds = registerPartitionManager.currentStateIds(e.itemId());
            stateChanges = dropNoOpStateChanges(e.stateChanges(), currentStateIds);
            stateMachinesEnded = dropAlreadyEndedMachines(e.stateMachinesEnded(), currentStateIds);
        }

        if (properties.isEmpty() && stateChanges.isEmpty() && stateMachinesEnded.isEmpty()
                && e.markersAdded().isEmpty() && e.markersRemoved().isEmpty()) {
            return null;
        }
        return new ItemUpdateEntry(e.itemId(), properties, stateChanges, stateMachinesEnded, e.markersAdded(), e.markersRemoved());
    }

    private static final Set<UUID> NO_BINARY_PROPERTIES = Set.of();

    private Map<UUID, Object> dropNoOpProperties(Map<UUID, Object> diff, Map<UUID, Object> current) {
        return dropNoOpProperties(diff, current, NO_BINARY_PROPERTIES);
    }

    // BINARY-typed properties are never stored in the properties JSONB `current` is read from
    // (see binaryPropertyIdsForItemType's own comment) -- "current" would always look like null
    // for one regardless of whether a binary is actually attached, which would make clearing an
    // already-set attachment look identical to a no-op. Always pass those through unfiltered
    // rather than risk silently swallowing a real change.
    private Map<UUID, Object> dropNoOpProperties(Map<UUID, Object> diff, Map<UUID, Object> current, Set<UUID> binaryPropertyIds) {
        Map<UUID, Object> result = new HashMap<>();
        diff.forEach((propertyId, newValue) -> {
            if (binaryPropertyIds.contains(propertyId) || !Objects.equals(newValue, current.get(propertyId))) {
                result.put(propertyId, newValue);
            }
        });
        return result;
    }

    private Map<UUID, UUID> dropNoOpStateChanges(Map<UUID, UUID> stateChanges, Map<UUID, UUID> currentStateIds) {
        Map<UUID, UUID> result = new HashMap<>();
        stateChanges.forEach((machineId, targetStateId) -> {
            if (!targetStateId.equals(currentStateIds.get(machineId))) result.put(machineId, targetStateId);
        });
        return result;
    }

    // "Ending" a machine the item isn't even currently participating in is already the desired
    // end state -- nothing to do.
    private Set<UUID> dropAlreadyEndedMachines(Set<UUID> stateMachinesEnded, Map<UUID, UUID> currentStateIds) {
        Set<UUID> result = new HashSet<>();
        for (UUID machineId : stateMachinesEnded) {
            if (currentStateIds.containsKey(machineId)) result.add(machineId);
        }
        return result;
    }

    // to-1 (maxCardinality == 1): a link already present between this exact pair is the desired
    // state already, a no-op. to-many (null/unbounded, or > 1): never a no-op -- a second link to
    // the same target is a legitimate, distinct link, not a duplicate of the first.
    private LinkCreateEntry filterLinkCreate(LinkCreateEntry e) {
        Integer maxCardinality = schemaRepository.findPerspectiveById(e.endpointA().perspectiveId()).maxCardinality();
        if (maxCardinality == null || maxCardinality != 1) return e;
        boolean alreadyLinked = registerPartitionManager.findExistingLinkId(
                toRegisterEndpoint(e.endpointA()), toRegisterEndpoint(e.endpointB())).isPresent();
        return alreadyLinked ? null : e;
    }

    private LinkUpdateEntry filterLinkUpdate(LinkUpdateEntry e) {
        if (e.properties().isEmpty()) return null;
        UUID linkTypeId = registerPartitionManager.findLinkTypeId(e.linkId()).orElse(null);
        if (linkTypeId == null) return e; // defensive only -- an update always targets an existing link

        Map<UUID, Object> properties = dropNoOpProperties(e.properties(),
                registerPartitionManager.currentLinkPropertiesById(e.linkId(), linkTypeId));
        return properties.isEmpty() ? null : new LinkUpdateEntry(e.linkId(), properties);
    }

    private LinkDeleteEntry filterLinkDelete(LinkDeleteEntry e) {
        return registerPartitionManager.linkExists(e.linkId()) ? e : null;
    }

    private RegisterLinkEndpoint toRegisterEndpoint(LinkEndpoint endpoint) {
        return new RegisterLinkEndpoint(endpoint.perspectiveId(), endpoint.itemId());
    }
}
