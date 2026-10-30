package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// Shared validation helpers for the per-family mutation appliers (ItemMutationApplier,
// TraitMutationApplier, etc.) -- split out alongside them so each applier stays focused on its
// own mutation family without duplicating these checks.
final class SchemaMutationValidation {

    private static final String THIS_ITEM_TYPE = "this item type";
    private static final String SUPERTYPE = "supertype";

    private SchemaMutationValidation() {
    }

    // --- Names ---
    //
    // A property or group's name only needs to be unique among its siblings -- the other properties
    // and groups directly under the same parent. Properties and groups live in separate tables, so
    // that can't be a DB constraint; it is enforced here, in the application.
    //
    // An item type's top level is the exception to "just look at the parent". Its own properties and
    // groups, those of every trait it implements, and those of its whole supertype chain (in both
    // directions -- a subtype inherits whatever is added to a supertype) share one flat namespace: a
    // trait's members appear on the item type under their own names, so how a type is composed never
    // shows in a path. Its link perspectives form a second namespace of the same shape -- a
    // perspective name appears once per item type. Inside a link type or a group there is nothing
    // else in the namespace, so the sibling check is all it takes; a trait's top level is checked
    // against every item type that implements it.

    // Names supplied together in one create payload (an item/trait/link's initial contents, or a new
    // group's) -- properties and groups share the one namespace, so callers pass both kinds through
    // the same set.
    static void requireUniqueName(Set<String> namesSeenSoFar, String name, String context) {
        if (!namesSeenSoFar.add(name)) {
            throw new IllegalArgumentException("'" + name + "' is defined more than once for " + context);
        }
    }

    // One name in a namespace, with what it belongs to: the item type or trait declaring a property
    // or group, or the link a perspective is one end of. The same name twice is a collision only
    // between different owners -- a trait reached at two levels of a supertype chain, or both ends of
    // one symmetric self-link ("siblings" both ways), are one owner.
    private record OwnedName(String name, UUID owner) {}

    // One of the two namespaces an item type has, as declared by an item type or by a trait.
    private record Namespace(String noun, Function<UUID, List<OwnedName>> declaredByItem,
                             Function<UUID, List<OwnedName>> declaredByTrait) {}

    private static Namespace propertyNames(SchemaRepository repo) {
        return new Namespace("a property or group",
                itemId -> owned(repo.findChildNames(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.ITEM, itemId)), itemId),
                traitId -> owned(repo.findChildNames(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.TRAIT, traitId)), traitId));
    }

    private static Namespace perspectiveNames(SchemaRepository repo) {
        Map<UUID, List<SchemaRepository.PerspectiveRow>> byEntity = repo.getPerspectivesByEntity();
        Function<UUID, List<OwnedName>> declared = entityId -> byEntity.getOrDefault(entityId, List.of()).stream()
                .map(p -> new OwnedName(p.name(), p.linkId()))
                .toList();
        return new Namespace("a link perspective", declared, declared);
    }

    private static List<OwnedName> owned(Set<String> names, UUID owner) {
        return names.stream().map(name -> new OwnedName(name, owner)).toList();
    }

    // What one item type puts into a namespace at its own level: what it declares, and what each trait
    // it implements directly declares.
    private static List<OwnedName> levelOf(Namespace namespace, Map<UUID, List<UUID>> traitIdsByItem, UUID itemId) {
        List<OwnedName> names = new ArrayList<>(namespace.declaredByItem().apply(itemId));
        for (UUID traitId : traitIdsByItem.getOrDefault(itemId, List.of())) {
            names.addAll(namespace.declaredByTrait().apply(traitId));
        }
        return names;
    }

    private static void requireNoCollision(List<OwnedName> added, List<OwnedName> existing, Namespace namespace, String where) {
        for (OwnedName a : added) {
            for (OwnedName e : existing) {
                if (a.name().equals(e.name()) && !a.owner().equals(e.owner())) {
                    throw new IllegalArgumentException("'" + a.name() + "' is already " + namespace.noun() + " on " + where);
                }
            }
        }
    }

    // `added` joining item type itemId's namespace: checked against its own level and every level of
    // its supertype chain in both directions.
    private static void requireAvailableAcrossChain(SchemaRepository repo, Namespace namespace, UUID itemId, List<OwnedName> added) {
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        requireNoCollision(added, levelOf(namespace, traitIdsByItem, itemId), namespace, named("item type", repo, itemId));
        for (UUID ancestor : ancestorsOf(supertypeById, itemId)) {
            requireNoCollision(added, levelOf(namespace, traitIdsByItem, ancestor), namespace, named(SUPERTYPE, repo, ancestor));
        }
        for (UUID descendant : descendantsOf(supertypeById, itemId)) {
            requireNoCollision(added, levelOf(namespace, traitIdsByItem, descendant), namespace, named("subtype", repo, descendant));
        }
    }

    private static List<UUID> implementersOf(SchemaRepository repo, UUID traitId) {
        return repo.getTraitIdsByItem().entrySet().stream()
                .filter(entry -> entry.getValue().contains(traitId))
                .map(Map.Entry::getKey)
                .toList();
    }

    private static Map<UUID, UUID> supertypeById(SchemaRepository repo) {
        // Collectors.toMap throws on a null value, so items with no supertype (the common case) are
        // filtered out rather than mapped to null -- an absent key and a null-mapped key behave
        // identically for these walks.
        return repo.getAllItems().stream()
                .filter(item -> item.supertypeId() != null)
                .collect(Collectors.toMap(SchemaRepository.ItemRow::id, SchemaRepository.ItemRow::supertypeId));
    }

    private static List<UUID> ancestorsOf(Map<UUID, UUID> supertypeById, UUID itemId) {
        List<UUID> ancestors = new ArrayList<>();
        UUID current = supertypeById.get(itemId);
        while (current != null) {
            ancestors.add(current);
            current = supertypeById.get(current);
        }
        return ancestors;
    }

    private static Set<UUID> descendantsOf(Map<UUID, UUID> supertypeById, UUID itemId) {
        Map<UUID, List<UUID>> childrenBySupertype = new HashMap<>();
        supertypeById.forEach((child, parent) -> childrenBySupertype.computeIfAbsent(parent, k -> new ArrayList<>()).add(child));
        Set<UUID> descendants = new LinkedHashSet<>();
        List<UUID> frontier = new ArrayList<>(childrenBySupertype.getOrDefault(itemId, List.of()));
        while (!frontier.isEmpty()) {
            UUID next = frontier.remove(frontier.size() - 1);
            if (descendants.add(next)) {
                frontier.addAll(childrenBySupertype.getOrDefault(next, List.of()));
            }
        }
        return descendants;
    }

    // e.g. "supertype 'Vehicle'", for naming where a collision is.
    private static String named(String role, SchemaRepository repo, UUID itemId) {
        return role + " '" + itemName(repo, itemId) + "'";
    }

    private static String itemName(SchemaRepository repo, UUID itemId) {
        return repo.getAllItems().stream()
                .filter(i -> i.id().equals(itemId))
                .findFirst()
                .map(SchemaRepository.ItemRow::name)
                .orElse(itemId.toString());
    }

    static void requireKnownParent(SchemaRepository repo, SchemaRepository.PropertyOwnerRef parent) {
        boolean known = switch (parent.kind()) {
            case ITEM -> repo.getAllItems().stream().anyMatch(i -> i.id().equals(parent.ownerId()));
            case TRAIT -> repo.getAllTraits().stream().anyMatch(t -> t.id().equals(parent.ownerId()));
            case LINK -> repo.getAllLinkIds().contains(parent.ownerId());
            case GROUP -> repo.findGroup(parent.ownerId()).isPresent();
        };
        if (!known) {
            throw new IllegalArgumentException("Unknown " + parent.kind().name().toLowerCase() + ": " + parent.ownerId());
        }
    }

    // A new property or group named `name` under `parent` (also used for a rename, and a move's
    // target). At an item type's or a trait's top level, it joins every implementing item type's
    // namespace as well.
    static void requireNameAvailable(SchemaRepository repo, SchemaRepository.PropertyOwnerRef parent, String name, String context) {
        if (repo.findChildNames(parent).contains(name)) {
            throw new IllegalArgumentException("'" + name + "' already exists on " + context);
        }
        Namespace namespace = propertyNames(repo);
        List<OwnedName> added = List.of(new OwnedName(name, parent.ownerId()));
        switch (parent.kind()) {
            case ITEM -> requireAvailableAcrossChain(repo, namespace, parent.ownerId(), added);
            case TRAIT -> implementersOf(repo, parent.ownerId())
                    .forEach(itemId -> requireAvailableAcrossChain(repo, namespace, itemId, added));
            default -> { /* only siblings share a group's or a link's namespace */ }
        }
    }

    // Implementing a trait puts its properties, groups and perspectives into the item type's
    // namespaces, so none of their names can already be taken there or anywhere in its supertype
    // chain in either direction.
    static void requireTraitMembersAvailable(SchemaRepository repo, UUID itemId, UUID traitId) {
        for (Namespace namespace : List.of(propertyNames(repo), perspectiveNames(repo))) {
            requireAvailableAcrossChain(repo, namespace, itemId, namespace.declaredByTrait().apply(traitId));
        }
    }

    // A brand-new item type's whole initial namespaces -- the property/group names it declares and
    // everything the traits it implements bring -- checked among themselves, then against everything
    // its supertype chain already owns. Inheritance is strictly additive -- no override, ever -- so a
    // name already owned anywhere up the chain can't be re-declared lower down.
    static void requireNamesAvailableForNewItemType(SchemaRepository repo, UUID supertypeId, Set<String> ownNames, List<UUID> traitIds) {
        requireNewLevelAvailable(repo, propertyNames(repo), owned(ownNames, new UUID(0, 0)), traitIds, supertypeId);
        requireNewLevelAvailable(repo, perspectiveNames(repo), List.of(), traitIds, supertypeId);
    }

    private static void requireNewLevelAvailable(SchemaRepository repo, Namespace namespace, List<OwnedName> own,
                                                 List<UUID> traitIds, UUID supertypeId) {
        List<OwnedName> level = new ArrayList<>(own);
        for (UUID traitId : traitIds) {
            List<OwnedName> traitNames = namespace.declaredByTrait().apply(traitId);
            requireNoCollision(traitNames, level, namespace, THIS_ITEM_TYPE);
            level.addAll(traitNames);
        }
        if (supertypeId == null) return;
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        for (UUID ancestor : chainFrom(supertypeById(repo), supertypeId)) {
            requireNoCollision(level, levelOf(namespace, traitIdsByItem, ancestor), namespace, named(SUPERTYPE, repo, ancestor));
        }
    }

    // Re-parenting: the item type's own namespaces, plus every descendant's (they all inherit the new
    // ancestors too), against the proposed supertype's whole chain.
    static void requireNoTopLevelCollisionsForSupertype(SchemaRepository repo, UUID itemId, UUID proposedSupertypeId) {
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        List<UUID> moving = new ArrayList<>();
        moving.add(itemId);
        moving.addAll(descendantsOf(supertypeById, itemId));
        for (Namespace namespace : List.of(propertyNames(repo), perspectiveNames(repo))) {
            for (UUID member : moving) {
                List<OwnedName> names = levelOf(namespace, traitIdsByItem, member);
                for (UUID ancestor : chainFrom(supertypeById, proposedSupertypeId)) {
                    requireNoCollision(names, levelOf(namespace, traitIdsByItem, ancestor), namespace,
                            named(SUPERTYPE, repo, ancestor));
                }
            }
        }
    }

    // A perspective named `name`, one end of link linkId, on an item type or trait. On an item type
    // it joins that type's perspective namespace; on a trait, the trait's own and every implementing
    // item type's.
    static void requirePerspectiveNameAvailable(SchemaRepository repo, UUID entityId, String name, UUID linkId) {
        Namespace namespace = perspectiveNames(repo);
        List<OwnedName> added = List.of(new OwnedName(name, linkId));
        boolean isItemType = repo.getAllItems().stream().anyMatch(item -> item.id().equals(entityId));
        if (isItemType) {
            requireAvailableAcrossChain(repo, namespace, entityId, added);
            return;
        }
        requireNoCollision(added, namespace.declaredByTrait().apply(entityId), namespace, "this trait");
        implementersOf(repo, entityId).forEach(itemId -> requireAvailableAcrossChain(repo, namespace, itemId, added));
    }

    // startId and every supertype above it.
    private static List<UUID> chainFrom(Map<UUID, UUID> supertypeById, UUID startId) {
        List<UUID> chain = new ArrayList<>();
        chain.add(startId);
        chain.addAll(ancestorsOf(supertypeById, startId));
        return chain;
    }

    // Despite the parameter name (CreatePerspectiveDefinitionMutation.itemId(), inherited here),
    // a perspective can target either an item type or a trait -- schema_item.id and
    // schema_trait.id are both valid values for schema_entity_link_perspective.entity_id (see
    // that column's own comment in V1_0_0_1__baseline.sql -- it's deliberately polymorphic and has no
    // FK constraint of its own, so this is the only place a bad id gets caught at all). Checking
    // only getAllItems() meant any perspective whose target was actually a trait (e.g. Pack ->
    // PackComponent, where PackComponent is a trait implemented by several item types, not an
    // item type itself) threw "Unknown item" here even though the id was perfectly valid -- just
    // valid for a trait.
    static void requireKnownItemOrTrait(SchemaRepository repo, UUID id) {
        boolean known = repo.getAllItems().stream().anyMatch(item -> item.id().equals(id))
                || repo.getAllTraits().stream().anyMatch(trait -> trait.id().equals(id));
        if (!known) {
            throw new IllegalArgumentException("Unknown item or trait: " + id);
        }
    }

    // Deletion must never be allowed to touch already-persisted instance data, even behind a
    // confirmation -- ntrloc's data is append-only/immutable by design. "Not in use" is therefore
    // a hard, symmetric gate for both traits and item types, not a warn-and-proceed check: if
    // either is in use, the fix is for the admin to remove the in-use references first (unassign
    // the trait, delete the items), never for the deletion itself to cascade into that data.
    static void requireTraitNotInUse(SchemaRepository repo, UUID traitId) {
        if (repo.isTraitInUse(traitId)) {
            String name = repo.getAllTraits().stream()
                    .filter(t -> t.id().equals(traitId))
                    .findFirst()
                    .map(SchemaRepository.TraitRow::name)
                    .orElse(traitId.toString());
            throw new IllegalArgumentException(
                    "Cannot delete trait '" + name + "' because it is still implemented by an item type");
        }
    }

    static void requireItemTypeNotInUse(SchemaRepository repo, UUID itemTypeId) {
        if (repo.isItemTypeInUse(itemTypeId)) {
            String name = repo.getAllItems().stream()
                    .filter(i -> i.id().equals(itemTypeId))
                    .findFirst()
                    .map(SchemaRepository.ItemRow::name)
                    .orElse(itemTypeId.toString());
            throw new IllegalArgumentException(
                    "Cannot delete item type '" + name + "' because items of this type still exist");
        }
    }

    // A subtype's shape includes everything it inherits, so its parent can't go out from under it.
    static void requireNoSubtypes(SchemaRepository repo, UUID itemTypeId) {
        var items = repo.getAllItems();
        List<String> subtypes = items.stream()
                .filter(i -> itemTypeId.equals(i.supertypeId()))
                .map(SchemaRepository.ItemRow::name)
                .sorted()
                .toList();
        if (!subtypes.isEmpty()) {
            String name = items.stream().filter(i -> i.id().equals(itemTypeId)).findFirst()
                    .map(SchemaRepository.ItemRow::name).orElse(itemTypeId.toString());
            throw new IllegalArgumentException("Cannot delete item type '" + name + "' because "
                    + String.join(", ", subtypes) + (subtypes.size() == 1 ? " inherits" : " inherit") + " from it");
        }
    }

    // A supertype must be a concrete item type -- unlike a link perspective's polymorphic
    // target (see requireKnownItemOrTrait), "is-a" identity doesn't make sense against a trait,
    // which is a horizontal capability with no identity claim of its own.
    static void requireKnownItem(SchemaRepository repo, UUID id) {
        boolean known = repo.getAllItems().stream().anyMatch(item -> item.id().equals(id));
        if (!known) {
            throw new IllegalArgumentException("Unknown item type: " + id);
        }
    }

    // Item types form a single-parent tree, not a DAG -- walk proposedSupertypeId's own ancestor
    // chain and reject if itemId would appear in it (including the trivial one-node cycle where
    // proposedSupertypeId equals itemId itself, an item can't be its own supertype).
    static void requireNoSupertypeCycle(SchemaRepository repo, UUID itemId, UUID proposedSupertypeId) {
        // Collectors.toMap uses Map.merge internally and throws on a null value, so items with no
        // supertype (the common case) are filtered out rather than mapped to null -- an absent key
        // and a key mapped to null behave identically for this walk (get() returns null either way).
        Map<UUID, UUID> supertypeById = repo.getAllItems().stream()
                .filter(item -> item.supertypeId() != null)
                .collect(Collectors.toMap(SchemaRepository.ItemRow::id, SchemaRepository.ItemRow::supertypeId));
        UUID current = proposedSupertypeId;
        while (current != null) {
            if (current.equals(itemId)) {
                throw new IllegalArgumentException("Cannot set supertype: would create a cycle");
            }
            current = supertypeById.get(current);
        }
    }

    // Property groups nest in a single-parent tree, the same shape as the supertype chain above --
    // same cycle risk, same walk. Only relevant when the *new* parent of a moved group is itself a
    // group (nesting into an item/trait/link can never cycle, and a property is always a leaf).
    static void requireNoGroupContainmentCycle(SchemaRepository repo, UUID groupId, UUID proposedParentGroupId) {
        Map<UUID, UUID> parentByGroup = repo.getParentGroupIdByGroup();
        UUID current = proposedParentGroupId;
        while (current != null) {
            if (current.equals(groupId)) {
                throw new IllegalArgumentException("Cannot move property group: would create a containment cycle");
            }
            current = parentByGroup.get(current);
        }
    }

    // A group that still contains anything is never deleted, and never cascades into what it holds.
    static void requireGroupEmpty(SchemaRepository repo, UUID groupId) {
        if (!repo.isGroupEmpty(groupId)) {
            String name = repo.findGroup(groupId).map(SchemaRepository.GroupRow::name).orElse(groupId.toString());
            throw new IllegalArgumentException("Cannot delete property group '" + name + "' because it is not empty");
        }
    }

    // Facetable is an admin-controlled opt-in (RegisterPartitionManager.isTermsFacetable), but
    // only ever meaningful on top of real structural eligibility -- SINGLE cardinality, and
    // either BOOLEAN or a type that can carry a controlled list (ControlledListValues.SUPPORTED_TYPES).
    // Deliberately does NOT also require a controlled list already be
    // attached: an admin drafting a brand-new property has no property id yet to attach one to
    // (that's a separate call, after this one returns), so "facetable checked, list not attached
    // yet" is a normal, transient step in the real workflow, not an invalid state -- it just means
    // isTermsFacetable won't actually treat it as facetable until the list catches up. What this
    // rejects is only the combinations that could never be valid regardless of sequencing:
    // LIST/SET cardinality, or a type that can never carry a controlled list or be BOOLEAN (BINARY).
    private static boolean facetableType(PropertyType type) {
        return type == PropertyType.BOOLEAN || ControlledListValues.SUPPORTED_TYPES.contains(type);
    }

    static void requireFacetableEligible(PropertyType type, PropertyCardinality cardinality, boolean facetable) {
        if (!facetable) return;
        if (cardinality != PropertyCardinality.SINGLE || !facetableType(type)) {
            throw new IllegalArgumentException(
                    "Only a SINGLE-cardinality property backed by a controlled list, or a BOOLEAN, can be marked facetable");
        }
    }

    // --- State machines ---
    // START/END are pseudostates: one of each per machine, born with the machine, undeletable, and
    // not creatable or renameable through the normal state mutations. The sentinel names below are
    // reserved so a NORMAL state can never collide with a pseudostate row.

    static void requireNotReservedStateName(String name) {
        if (SchemaRepository.START_STATE_NAME.equals(name) || SchemaRepository.END_STATE_NAME.equals(name)) {
            throw new IllegalArgumentException("State name '" + name + "' is reserved for the START/END pseudostates");
        }
    }

    static void requireDeletableState(SchemaRepository repo, UUID stateId) {
        SchemaRepository.StateRow state = repo.findState(stateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown state: " + stateId));
        if (!SchemaRepository.STATE_KIND_NORMAL.equals(state.kind())) {
            throw new IllegalArgumentException("The START and END pseudostates cannot be deleted");
        }
    }

    // Structural rules for a transition between fromStateId and toStateId, with the given
    // (already-serialized) guard: one being created, or `moving` (an existing one's id) being moved
    // there. Enforced here rather than the DB so the message is useful.
    static void requireValidTransitionEndpoints(SchemaRepository repo, UUID fromStateId, UUID toStateId, String serializedGuard,
                                                UUID moving) {
        SchemaRepository.StateRow from = repo.findState(fromStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown from-state: " + fromStateId));
        SchemaRepository.StateRow to = repo.findState(toStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown to-state: " + toStateId));
        if (!from.stateMachineId().equals(to.stateMachineId())) {
            throw new IllegalArgumentException("A transition's states must belong to the same state machine");
        }
        if (fromStateId.equals(toStateId)) {
            throw new IllegalArgumentException("A transition can't lead from a state back to itself");
        }
        List<SchemaRepository.TransitionRow> others = repo.getTransitionsByFromState().getOrDefault(fromStateId, List.of()).stream()
                .filter(t -> !t.id().equals(moving))
                .toList();
        if (others.stream().anyMatch(t -> t.toStateId().equals(toStateId))) {
            throw new IllegalArgumentException("There's already a transition from '" + from.name() + "' to '" + to.name() + "'");
        }
        if (SchemaRepository.STATE_KIND_END.equals(from.kind())) {
            throw new IllegalArgumentException("The END pseudostate cannot have outgoing transitions");
        }
        if (SchemaRepository.STATE_KIND_START.equals(to.kind())) {
            throw new IllegalArgumentException("The START pseudostate cannot have incoming transitions");
        }
        if (SchemaRepository.STATE_KIND_START.equals(from.kind())) {
            if (serializedGuard != null) {
                throw new IllegalArgumentException("The transition out of START cannot have a guard condition");
            }
            if (!others.isEmpty()) {
                throw new IllegalArgumentException("START already has an outgoing transition");
            }
        }
    }

    // A guard may not be added to the START -> first-state transition on update either.
    static void requireGuardAllowedOnTransition(SchemaRepository repo, UUID transitionId, String serializedGuard) {
        if (serializedGuard == null) return;
        SchemaRepository.TransitionRow transition = repo.findTransition(transitionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown transition: " + transitionId));
        boolean fromStart = repo.findState(transition.fromStateId())
                .map(s -> SchemaRepository.STATE_KIND_START.equals(s.kind()))
                .orElse(false);
        if (fromStart) {
            throw new IllegalArgumentException("The transition out of START cannot have a guard condition");
        }
    }
}
