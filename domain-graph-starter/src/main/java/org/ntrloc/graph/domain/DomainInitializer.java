package org.ntrloc.graph.domain;

import org.flowable.dmn.api.DmnRepositoryService;
import org.flowable.engine.RepositoryService;
import org.ntrloc.graph.db.partition.binary.BinaryPartitionManager;
import org.ntrloc.graph.db.partition.schema.ControlledListManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateTransitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// The one and only thing that seeds a domain -- schema, data, processes, everything. Nothing else
// in the system (SchemaManager, RegisterPartitionManager, ProcessEngineConfig, DmnEngineConfig) has any
// knowledge that a DomainInitializer exists or that the system is being "initialized": it starts
// up empty, and a DomainInitializer implementation seeds it afterward through the same public APIs
// any other caller would use (SchemaManager.applyMutations() publishes SchemaChangeEvent, which
// RegisterPartitionManager already reacts to by creating per-item-type/per-link-type register
// tables -- no eager scan needed).
//
// Each of the three init* methods is a default no-op -- implement only what a given domain
// actually needs. Nothing calls these for you: a concrete implementation is a normal @Component
// that also implements Spring's ApplicationRunner (not @PostConstruct -- see the class-level note
// on any implementation for why: @EventListener methods elsewhere aren't wired up until *all*
// singletons finish construction, so a @PostConstruct that publishes SchemaChangeEvent during its
// own construction can fire before RegisterPartitionManager is even listening), constructor-
// injecting whatever it needs (SchemaManager, ControlledListManager, JdbcClient,
// BinaryPartitionManager, RepositoryService, DmnRepositoryService) and calling its own overridden
// method(s) from run(), in the order initSchema -> initData -> initProcesses.
public interface DomainInitializer {

    /**
     * Implementations create item types, traits, links, properties, and controlled lists via
     * schemaManager.applyMutations(...) -- the same API the admin UI's schema editor uses.
     */
    default void initSchema(SchemaManager schemaManager, ControlledListManager controlledListManager) {
    }

    /**
     * Implementations seed domain data via jdbcClient and may store binary content.
     */
    default void initData(JdbcClient jdbcClient, BinaryPartitionManager binaryPartitionManager) {
    }

    /**
     * Implementations deploy domain-specific BPMN processes and/or DMN decision tables, e.g. via
     * deployProcesses/deployDecisions below.
     */
    default void initProcesses(RepositoryService repositoryService, DmnRepositoryService dmnRepositoryService) {
    }

    default void deployProcesses(RepositoryService repositoryService, String deploymentName, String... classpathResources) {
        var builder = repositoryService.createDeployment().name(deploymentName);
        for (String resource : classpathResources) {
            builder.addClasspathResource(resource);
        }
        builder.deploy();
    }

    default void deployDecisions(DmnRepositoryService dmnRepositoryService, String deploymentName, String... classpathResources) {
        var builder = dmnRepositoryService.createDeployment().name(deploymentName);
        for (String resource : classpathResources) {
            builder.addClasspathResource(resource);
        }
        builder.deploy();
    }

    /**
     * Creates a full, named state machine for an existing item type in one call: the machine
     * itself, then every state, then every transition, resolving each transition's from/to state
     * by name against what the states batch just persisted. Three applyMutations batches are
     * unavoidable -- the machine id doesn't exist until CreateStateMachineMutation runs (states
     * need it), and state ids don't exist until CreateStateMutation runs (transitions need real
     * ids, not names). See StateDefinition/TransitionDefinition below.
     */
    default void initStateMachine(SchemaManager schemaManager, UUID itemDefinitionId, String machineName,
                                   List<StateDefinition> states, List<TransitionDefinition> transitions) {
        schemaManager.applyMutations(List.of(new CreateStateMachineMutation(itemDefinitionId, machineName, null)));

        UUID stateMachineId = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemDefinitionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No item type with id: " + itemDefinitionId))
                .stateMachines().stream()
                .filter(m -> m.name().equals(machineName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("State machine '" + machineName + "' was not created"))
                .id();

        schemaManager.applyMutations(states.stream()
                .<DefinitionMutation>map(s -> new CreateStateMutation(
                        stateMachineId, s.name(), s.description(), s.entryProcessId(), s.exitProcessId()))
                .toList());

        List<AdminStateView> createdStates = schemaManager.getAdminSchema().items().stream()
                .filter(item -> item.id().equals(itemDefinitionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No item type with id: " + itemDefinitionId))
                .stateMachines().stream()
                .filter(m -> m.id().equals(stateMachineId))
                .findFirst()
                .map(AdminStateMachineView::states)
                .orElse(null);
        Map<String, UUID> resolved = Optional.ofNullable(createdStates)
                .map(List::stream)
                .orElseGet(Stream::empty)
                .collect(Collectors.toMap(AdminStateView::name, AdminStateView::id));

        schemaManager.applyMutations(transitions.stream()
                .<DefinitionMutation>map(t -> new CreateTransitionMutation(
                        requireStateId(resolved, t.fromStateName()),
                        requireStateId(resolved, t.toStateName()),
                        t.name(), t.description(), t.processId(), t.guardCondition()))
                .toList());
    }

    private static UUID requireStateId(Map<String, UUID> stateIdsByName, String name) {
        UUID id = stateIdsByName.get(name);
        if (id == null) {
            throw new IllegalArgumentException(
                    "No state named '" + name + "' -- check it's included in the states passed to initStateMachine");
        }
        return id;
    }

    /**
     * A state to create via initStateMachine -- always a NORMAL state (the START/END pseudostates
     * come with the machine). Mirrors CreateStateMutation's fields, minus itemDefinitionId
     * (initStateMachine already takes that once for the whole batch).
     */
    record StateDefinition(String name, @Nullable String description,
                            @Nullable String entryProcessId, @Nullable String exitProcessId) {
        public StateDefinition(String name) {
            this(name, null, null, null);
        }
    }

    /** Sentinel state names for wiring transitions to/from the pseudostates in a TransitionDefinition. */
    String START_STATE = "__start__";
    String END_STATE = "__end__";

    /**
     * A transition to create via initStateMachine. fromStateName/toStateName are plain names, not
     * ids -- see initStateMachine's own comment on why the ids can't exist yet when this record is
     * built. Use START_STATE / END_STATE to wire to the pseudostates.
     */
    record TransitionDefinition(String fromStateName, String toStateName, String name,
                                 @Nullable String description, @Nullable String processId,
                                 @Nullable JsonNode guardCondition) {
        public TransitionDefinition(String fromStateName, String toStateName, String name) {
            this(fromStateName, toStateName, name, null, null, null);
        }
    }
}
