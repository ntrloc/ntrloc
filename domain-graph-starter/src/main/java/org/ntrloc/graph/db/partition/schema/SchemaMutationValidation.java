package org.ntrloc.graph.db.partition.schema;

import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyContainerKind;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.repository.SchemaRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Shared validation helpers for the per-family mutation appliers (ItemMutationApplier,
// TraitMutationApplier, etc.) -- split out alongside them so each applier stays focused on its
// own mutation family without duplicating these checks.
final class SchemaMutationValidation {

    private SchemaMutationValidation() {
    }

    // --- Names ---
    //
    // A property or group's name only needs to be unique among its siblings -- the other properties
    // and groups directly under the same parent. Properties and groups live in separate tables, so
    // that can't be a DB constraint; it is enforced here, in the application.
    //
    // An item type's top-level names are the exception to "just look at the parent": they share one
    // flat namespace with its whole supertype chain (in both directions -- adding a name to a
    // supertype has to be checked against every subtype too) and with the names of the traits it
    // implements, since a trait's contributions project under the trait's own name. Inside a trait,
    // a link type, or a group there is nothing else in the namespace, so the sibling check is all it
    // takes.

    // Names supplied together in one create payload (an item/trait/link's initial contents, or a new
    // group's) -- properties and groups share the one namespace, so callers pass both kinds through
    // the same set.
    static void requireUniqueName(Set<String> namesSeenSoFar, String name, String context) {
        if (!namesSeenSoFar.add(name)) {
            throw new IllegalArgumentException("'" + name + "' is defined more than once for " + context);
        }
    }

    // What names a single item type puts into its own namespace: its own property/group names, and
    // the names of the traits it implements.
    private record NameSets(Set<String> children, Set<String> traits) {}

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

    private static NameSets nameSetsOf(SchemaRepository repo, Map<UUID, String> traitNameById, Map<UUID, List<UUID>> traitIdsByItem, UUID itemId) {
        Set<String> children = repo.findChildNames(new SchemaRepository.PropertyOwnerRef(PropertyContainerKind.ITEM, itemId));
        Set<String> traits = traitIdsByItem.getOrDefault(itemId, List.of()).stream()
                .map(traitNameById::get)
                .collect(Collectors.toSet());
        return new NameSets(children, traits);
    }

    private static Map<UUID, String> traitNameById(SchemaRepository repo) {
        return repo.getAllTraits().stream()
                .collect(Collectors.toMap(SchemaRepository.TraitRow::id, SchemaRepository.TraitRow::name));
    }

    private static String itemName(SchemaRepository repo, UUID itemId) {
        return repo.getAllItems().stream()
                .filter(i -> i.id().equals(itemId))
                .findFirst()
                .map(SchemaRepository.ItemRow::name)
                .orElse(itemId.toString());
    }

    // A collision is a property/group name meeting another property/group name or a trait name, or a
    // trait name meeting a property/group name. Trait-vs-trait is deliberately not a collision: trait
    // names are globally unique, so the same name on both sides is the same trait, implemented at
    // two levels of the chain, which the effective view simply dedupes.
    private static void requireNoCollision(NameSets a, NameSets b, String aLabel, String bLabel) {
        Set<String> collisions = new LinkedHashSet<>();
        for (String name : a.children()) {
            if (b.children().contains(name) || b.traits().contains(name)) collisions.add(name);
        }
        for (String name : a.traits()) {
            if (b.children().contains(name)) collisions.add(name);
        }
        if (!collisions.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + collisions.iterator().next() + "' on " + aLabel + " collides with a property, group, or trait of that name on " + bLabel);
        }
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

    // A new property or group named `name` under `parent` (also used for a move's target).
    static void requireNameAvailable(SchemaRepository repo, SchemaRepository.PropertyOwnerRef parent, String name, String context) {
        if (repo.findChildNames(parent).contains(name)) {
            throw new IllegalArgumentException("'" + name + "' already exists on " + context);
        }
        if (parent.kind() != PropertyContainerKind.ITEM) return;

        UUID itemId = parent.ownerId();
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, String> traitNames = traitNameById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        NameSets added = new NameSets(Set.of(name), Set.of());

        requireNoCollision(added, new NameSets(Set.of(), nameSetsOf(repo, traitNames, traitIdsByItem, itemId).traits()),
                "this item type", "a trait this item type implements");
        for (UUID ancestor : ancestorsOf(supertypeById, itemId)) {
            requireNoCollision(added, nameSetsOf(repo, traitNames, traitIdsByItem, ancestor),
                    "this item type", "supertype '" + itemName(repo, ancestor) + "'");
        }
        for (UUID descendant : descendantsOf(supertypeById, itemId)) {
            requireNoCollision(added, nameSetsOf(repo, traitNames, traitIdsByItem, descendant),
                    "this item type", "subtype '" + itemName(repo, descendant) + "'");
        }
    }

    // Implementing a trait puts its name into the item type's namespace (its contributions project
    // under it), so the trait's name can't match any property or group already there, on the item
    // type itself or anywhere in its supertype chain in either direction.
    static void requireTraitNameAvailable(SchemaRepository repo, UUID itemId, String traitName) {
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, String> traitNames = traitNameById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        NameSets added = new NameSets(Set.of(), Set.of(traitName));

        Set<UUID> relatives = new LinkedHashSet<>();
        relatives.add(itemId);
        relatives.addAll(ancestorsOf(supertypeById, itemId));
        relatives.addAll(descendantsOf(supertypeById, itemId));
        for (UUID relative : relatives) {
            requireNoCollision(added, nameSetsOf(repo, traitNames, traitIdsByItem, relative),
                    "the trait", "item type '" + itemName(repo, relative) + "'");
        }
    }

    // A brand-new item type's whole initial namespace (property/group names it declares, trait names
    // it implements) against everything its supertype chain already owns. Inheritance is strictly
    // additive -- no override, ever -- so a name already owned anywhere up the chain can't be
    // re-declared lower down; it would just be a second, distinct row silently coexisting with the
    // inherited one.
    static void requireNamesAvailableUnderSupertype(SchemaRepository repo, UUID supertypeId, Set<String> ownNames, Set<String> ownTraitNames) {
        Set<String> own = new HashSet<>(ownNames);
        own.retainAll(ownTraitNames);
        if (!own.isEmpty()) {
            throw new IllegalArgumentException("'" + own.iterator().next() + "' is used both as a property or group and as a trait name on this item type");
        }
        if (supertypeId == null) return;
        checkAgainstAncestors(repo, supertypeId, new NameSets(ownNames, ownTraitNames), "this item type");
    }

    // Re-parenting: the item type's own namespace, plus every descendant's (they all inherit the new
    // ancestors too), against the proposed supertype's whole chain.
    static void requireNoTopLevelCollisionsForSupertype(SchemaRepository repo, UUID itemId, UUID proposedSupertypeId) {
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, String> traitNames = traitNameById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();

        List<UUID> moving = new ArrayList<>();
        moving.add(itemId);
        moving.addAll(descendantsOf(supertypeById, itemId));
        for (UUID member : moving) {
            String label = member.equals(itemId) ? "this item type" : "subtype '" + itemName(repo, member) + "'";
            checkAgainstAncestors(repo, proposedSupertypeId, nameSetsOf(repo, traitNames, traitIdsByItem, member), label);
        }
    }

    private static void checkAgainstAncestors(SchemaRepository repo, UUID startSupertypeId, NameSets names, String label) {
        Map<UUID, UUID> supertypeById = supertypeById(repo);
        Map<UUID, String> traitNames = traitNameById(repo);
        Map<UUID, List<UUID>> traitIdsByItem = repo.getTraitIdsByItem();
        UUID current = startSupertypeId;
        while (current != null) {
            requireNoCollision(names, nameSetsOf(repo, traitNames, traitIdsByItem, current),
                    label, "supertype '" + itemName(repo, current) + "'");
            current = supertypeById.get(current);
        }
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

    // The one guardrail schema_entity_link_perspective's now-removed UNIQUE(entity_id,
    // link_definition_id) used to provide as an accidental side effect: a perspective name is
    // only meaningful if it names a single, consistent target across every link definition that
    // uses it for a given entity -- e.g. Person "worksFor" must always mean the same thing,
    // whether it resolves to Company via one link or Department via another, it can't be both.
    // Same-entity, same-name, same-target is fine (that's how self-links and the deliberately
    // ambiguous-link-type test fixtures both work), so this compares target *sets*, not row
    // counts.
    static void requireConsistentPerspectiveTarget(SchemaRepository repo, UUID newLinkId, UUID entityId, String name, Set<UUID> newTargets) {
        for (var existing : repo.findPerspectivesByEntityAndName(entityId, name, newLinkId)) {
            Set<UUID> existingTargets = repo.findInversePerspectives(existing.linkId(), existing.id()).stream()
                    .map(SchemaRepository.PerspectiveRow::entityId)
                    .collect(Collectors.toSet());
            if (!existingTargets.equals(newTargets)) {
                throw new IllegalArgumentException(
                        "Perspective '" + name + "' already targets a different type via another link definition");
            }
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
                    "Cannot delete trait '" + name + "' because it is still implemented by an item type or referenced by a link perspective");
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
    // either BOOLEAN or a type that can carry a controlled list (STRING/INT/LONG -- the same set
    // the admin UI itself offers a controlled list for, see ntrloc-property-table.js's own
    // CONTROLLED_LIST_TYPES). Deliberately does NOT also require a controlled list already be
    // attached: an admin drafting a brand-new property has no property id yet to attach one to
    // (that's a separate call, after this one returns), so "facetable checked, list not attached
    // yet" is a normal, transient step in the real workflow, not an invalid state -- it just means
    // isTermsFacetable won't actually treat it as facetable until the list catches up. What this
    // rejects is only the combinations that could never be valid regardless of sequencing:
    // LIST/SET cardinality, or a type that can never carry a controlled list or be BOOLEAN
    // (DATE/DATETIME/DOUBLE/BINARY).
    private static final Set<PropertyType> FACETABLE_ELIGIBLE_TYPES =
            Set.of(PropertyType.BOOLEAN, PropertyType.STRING, PropertyType.INT, PropertyType.LONG);

    static void requireFacetableEligible(PropertyType type, PropertyCardinality cardinality, boolean facetable) {
        if (!facetable) return;
        if (cardinality != PropertyCardinality.SINGLE || !FACETABLE_ELIGIBLE_TYPES.contains(type)) {
            throw new IllegalArgumentException(
                    "Only a SINGLE-cardinality property backed by a controlled list (STRING/INT/LONG), or a BOOLEAN, can be marked facetable");
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

    // Structural rules for a transition being created between fromStateId and toStateId, with the
    // given (already-serialized) guard. Enforced here rather than the DB so the message is useful.
    static void requireValidTransitionEndpoints(SchemaRepository repo, UUID fromStateId, UUID toStateId, String serializedGuard) {
        SchemaRepository.StateRow from = repo.findState(fromStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown from-state: " + fromStateId));
        SchemaRepository.StateRow to = repo.findState(toStateId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown to-state: " + toStateId));
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
            boolean alreadyWired = !repo.getTransitionsByFromState().getOrDefault(fromStateId, List.of()).isEmpty();
            if (alreadyWired) {
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
