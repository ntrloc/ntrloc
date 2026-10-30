package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminTransitionView;
import org.ntrloc.ui.UiTestData.Transition;

/**
 * Editing a state machine in its editor, end to end: everything is held until Save, then saved
 * together; Cancel drops it all; and each change can be undone. Every test edits its own machine --
 * Draft, Review and Done, from start to end.
 */
class StateMachineEditingUiTest extends AbstractUiIntegrationTest {

    private static final String MACHINE = "Flow";

    private String itemType;
    private SchemaPage schema;

    @BeforeEach
    void createAMachineAndSignIn() {
        itemType = "UiStateMachine-" + UUID.randomUUID().toString().substring(0, 8);
        testData.createItemType(itemType, null, List.of(property("title", PropertyType.STRING)), List.of());
        testData.createStateMachine(itemType, MACHINE, List.of("Draft", "Review", "Done"), List.of(
                new Transition("__start__", "Draft", "Create"),
                new Transition("Draft", "Review", "Submit"),
                new Transition("Review", "Done", "Approve"),
                new Transition("Done", "__end__", "Finish")));

        openAdminUiAs(admin(), "/schema");
        schema = SchemaPage.of(driver).selectItemType(itemType).openTab("State Machines");
    }

    @Test
    void editsAreHeldUntilSavedAndThenSavedTogether() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE)
                .typeMachineName("Workflow")
                .selectState("Draft").typeInDetails("Name", "Drafting")
                .addState().typeInDetails("Name", "Archived");
        assertThat(saved().name()).isEqualTo(MACHINE);

        editor.save();

        assertThat(saved("Workflow").states()).extracting(AdminStateView::name).contains("Drafting", "Archived").doesNotContain("Draft");
        assertThat(schema.stateNames("Workflow")).containsExactlyInAnyOrder("Drafting", "Review", "Done", "Archived");
    }

    @Test
    void cancelLeavesTheMachineAsItWas() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE).selectState("Review").deleteSelected().addState();
        assertThat(editor.stateNames()).containsExactlyInAnyOrder("Draft", "Done", "New state");

        editor.cancel();

        assertThat(normalStates()).containsExactlyInAnyOrder("Draft", "Review", "Done");
        assertThat(schema.stateNames(MACHINE)).containsExactlyInAnyOrder("Draft", "Review", "Done");
    }

    @Test
    void deletingAStateTakesItsTransitionsWithIt() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE).selectState("Review").deleteSelected();
        assertThat(editor.transitionNames()).containsExactlyInAnyOrder("Create", "Finish");

        editor.save();

        assertThat(normalStates()).containsExactlyInAnyOrder("Draft", "Done");
        assertThat(transitions().keySet()).containsExactlyInAnyOrder("Create", "Finish");
    }

    @Test
    void aSelectedTransitionCanBeDeleted() {
        schema.editStateMachine(MACHINE).selectTransition("Approve").deleteSelected().save();

        assertThat(transitions().keySet()).containsExactlyInAnyOrder("Create", "Submit", "Finish");
    }

    @Test
    void undoAndRedoStepThroughTheEdits() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE).selectState("Review").deleteSelected();

        editor.undo();
        assertThat(editor.stateNames()).containsExactlyInAnyOrder("Draft", "Review", "Done");
        editor.redo();
        assertThat(editor.stateNames()).containsExactlyInAnyOrder("Draft", "Done");
        editor.undo().save();

        assertThat(normalStates()).containsExactlyInAnyOrder("Draft", "Review", "Done");
    }

    @Test
    void aTransitionIsDrawnFromAStatesAddButton() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE).selectState("Review").drawTransitionTo("Draft");
        assertThat(editor.transitionNames()).contains("New transition");

        editor.typeInDetails("Name", "Rework").save();

        assertThat(transitions().get("Rework")).isEqualTo(List.of("Review", "Draft"));
    }

    @Test
    void aTransitionsEndCanBeMovedToAnotherState() {
        schema.editStateMachine(MACHINE).selectTransition("Approve").moveSelectedTransitionEnd("to", "Draft").save();

        assertThat(transitions().get("Approve")).isEqualTo(List.of("Review", "Draft"));
    }

    @Test
    void aStateNeedsANameNoOtherStateHas() {
        StateMachineEditorPage editor = schema.editStateMachine(MACHINE).selectState("Draft").typeInDetails("Name", "Review");

        assertThat(editor.detailsProblem()).isEqualTo("Another state has this name.");
        assertThat(editor.canSave()).isFalse();
    }

    @Test
    void whereTheStatesAreIsSaved() {
        UUID done = saved().states().stream().filter(s -> s.name().equals("Done")).findFirst().orElseThrow().id();

        schema.editStateMachine(MACHINE).moveState("Done", 0, 160).save();

        var position = saved().layout().get("positions").get(done.toString());
        assertThat(position.get("y").asDouble()).isPositive();
    }

    private AdminStateMachineView saved() {
        return saved(MACHINE);
    }

    private AdminStateMachineView saved(String name) {
        return testData.stateMachine(itemType, name);
    }

    private List<String> normalStates() {
        return saved().states().stream().filter(s -> s.kind().equals("NORMAL")).map(AdminStateView::name).toList();
    }

    // Each transition's name, to its from and to states' names.
    private Map<String, List<String>> transitions() {
        Map<UUID, String> names = new HashMap<>();
        saved().states().forEach(s -> names.put(s.id(), s.name()));
        Map<String, List<String>> transitions = new HashMap<>();
        for (AdminStateView state : saved().states()) {
            for (AdminTransitionView t : state.transitions()) transitions.put(t.name(), List.of(state.name(), names.get(t.toStateId())));
        }
        return transitions;
    }
}
