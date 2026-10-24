package org.ntrloc.ui;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.ledger.ItemCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkCreateEntry;
import org.ntrloc.graph.db.partition.ledger.LinkEndpoint;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTraitDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminPropertyDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTraitDefinitionView;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;

/**
 * Creates the schema and items a UI test needs, through the same SchemaManager and ledger paths the
 * application itself uses. A plain class rather than a Spring bean: importing a bean into the UI
 * tests would give them a second application context (see AbstractUiIntegrationTest).
 */
final class UiTestData {

    // Every fixture item needs a real, existing creator; one per test run is enough.
    private static UUID actorId;

    private final SchemaManager schemaManager;
    private final LedgerRegisterCoordinator coordinator;
    private final SecurityRepository securityRepository;

    UiTestData(SchemaManager schemaManager, LedgerRegisterCoordinator coordinator, SecurityRepository securityRepository) {
        this.schemaManager = schemaManager;
        this.coordinator = coordinator;
        this.securityRepository = securityRepository;
    }

    /** A transition between two states, by name; "__start__" and "__end__" are the pseudostates. */
    record Transition(String from, String to, String name) {
    }

    AdminItemDefinitionView createItemType(String name, String displayLabelPattern,
                                           List<CreatePropertyDefinitionMutation> properties,
                                           List<CreatePropertyGroupDefinitionMutation> groups) {
        return createItemType(name, displayLabelPattern, properties, groups, null, false, List.of());
    }

    AdminItemDefinitionView createItemType(String name, String displayLabelPattern,
                                           List<CreatePropertyDefinitionMutation> properties,
                                           List<CreatePropertyGroupDefinitionMutation> groups,
                                           UUID supertypeId, boolean abstractType, List<UUID> traitIds) {
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(
                name, "UI test fixture", properties, supertypeId, abstractType, displayLabelPattern, traitIds, groups)));
        return itemType(name);
    }

    AdminTraitDefinitionView createTrait(String name, List<CreatePropertyDefinitionMutation> properties) {
        schemaManager.applyMutations(List.of(new CreateTraitDefinitionMutation(name, "UI test fixture", properties, List.of())));
        return schemaManager.getAdminSchema().traits().stream()
                .filter(trait -> trait.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /** Creates a state machine with the given NORMAL states, then the given transitions between them. */
    AdminStateMachineView createStateMachine(String itemTypeName, String name, List<String> states, List<Transition> transitions) {
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemType(itemTypeName).id(), name, null)));
        UUID machineId = stateMachine(itemTypeName, name).id();
        schemaManager.applyMutations(states.stream()
                .<DefinitionMutation>map(state -> new CreateStateMutation(machineId, state, null, null, null, null))
                .toList());
        Map<String, UUID> stateIds = stateIds(stateMachine(itemTypeName, name));
        schemaManager.applyMutations(transitions.stream()
                .<DefinitionMutation>map(t -> new CreateTransitionMutation(stateIds.get(t.from()), stateIds.get(t.to()), t.name(), null, null, null))
                .toList());
        return stateMachine(itemTypeName, name);
    }

    AdminStateMachineView stateMachine(String itemTypeName, String name) {
        return itemType(itemTypeName).stateMachines().stream()
                .filter(machine -> machine.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    static Map<String, UUID> stateIds(AdminStateMachineView machine) {
        return machine.states().stream().collect(Collectors.toMap(AdminStateView::name, AdminStateView::id));
    }

    AdminItemDefinitionView itemType(String name) {
        return schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    static CreatePropertyDefinitionMutation property(String name, PropertyType type) {
        return property(name, type, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL);
    }

    static CreatePropertyDefinitionMutation property(String name, PropertyType type, PropertyCardinality cardinality, PropertyUsage usage) {
        return new CreatePropertyDefinitionMutation(name, "UI test fixture", type, cardinality, usage, false);
    }

    static CreatePropertyGroupDefinitionMutation group(String name, CreatePropertyDefinitionMutation... properties) {
        return new CreatePropertyGroupDefinitionMutation(name, "UI test fixture", List.of(properties), List.of());
    }

    /** path is a property name, or "group.property" for a property one group down. */
    static UUID propertyId(AdminItemDefinitionView itemType, String path) {
        String[] parts = path.split("\\.");
        List<AdminPropertyDefinitionView> properties = parts.length == 1
                ? itemType.properties()
                : itemType.groups().stream().filter(g -> g.name().equals(parts[0])).findFirst().orElseThrow().properties();
        String name = parts[parts.length - 1];
        return properties.stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .map(AdminPropertyDefinitionView::id)
                .orElseThrow();
    }

    UUID createItem(UUID itemTypeId, Map<UUID, Object> properties) {
        return createItem(itemTypeId, properties, Map.of());
    }

    /** initialStates maps a state machine's id to the id of the state the item starts in. */
    UUID createItem(UUID itemTypeId, Map<UUID, Object> properties, Map<UUID, UUID> initialStates) {
        UUID itemId = UUID.randomUUID();
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new ItemCreateEntry(itemId, itemTypeId, properties, initialStates, Set.of())),
                txn, actorId(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
        return itemId;
    }

    void createLink(UUID linkTypeId, UUID perspectiveA, UUID itemA, UUID perspectiveB, UUID itemB) {
        UUID txn = UUID.randomUUID();
        coordinator.prepare(List.of(new LinkCreateEntry(UUID.randomUUID(), linkTypeId,
                new LinkEndpoint(perspectiveA, itemA), new LinkEndpoint(perspectiveB, itemB), Map.of())),
                txn, actorId(), OffsetDateTime.now());
        coordinator.commit(txn, UUID.randomUUID());
    }

    private synchronized UUID actorId() {
        if (actorId == null) {
            actorId = securityRepository.createUser("UI Test Fixture", "ui-fixture-" + UUID.randomUUID() + "@example.com", true).id();
        }
        return actorId;
    }
}
