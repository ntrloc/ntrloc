package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.ntrloc.ui.UiTestData.property;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.access.AddMarkers;
import org.ntrloc.graph.access.ComparisonOperator;
import org.ntrloc.graph.access.Condition;
import org.ntrloc.graph.access.ConditionGroup;
import org.ntrloc.graph.access.CountedPredicate;
import org.ntrloc.graph.access.End;
import org.ntrloc.graph.access.Filter;
import org.ntrloc.graph.access.Gate;
import org.ntrloc.graph.access.IsFalsePredicate;
import org.ntrloc.graph.access.IsTruePredicate;
import org.ntrloc.graph.access.NumberPredicate;
import org.ntrloc.graph.access.Pipeline;
import org.ntrloc.graph.access.PipelineLayout;
import org.ntrloc.graph.access.Point;
import org.ntrloc.graph.access.Quantity;
import org.ntrloc.graph.access.RemoveMarkers;
import org.ntrloc.graph.access.Route;
import org.ntrloc.graph.access.Select;
import org.ntrloc.graph.access.Self;
import org.ntrloc.graph.access.SetMarkers;
import org.ntrloc.graph.access.Start;
import org.ntrloc.graph.access.StateEqualsPredicate;
import org.ntrloc.graph.access.StepToItem;
import org.ntrloc.graph.access.StepToProperty;
import org.ntrloc.graph.access.StepToStateMachine;
import org.ntrloc.graph.access.StringEqualsPredicate;
import org.ntrloc.graph.access.TotalityPredicate;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.authorization.repository.MarkerDecisionPipelineRepository;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.ui.UiTestData.Transition;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * An item type's Access Markers tab, end to end: a real user signs in, opens the Schema view, and
 * creates, renames, and deletes markers, then reads and rearranges the item type's marker decision
 * pipeline diagram.
 *
 * REPORT_TYPE carries one pipeline that uses every kind of block and condition the diagram draws;
 * no test changes it. Every test that changes markers or a layout gets its own fresh item type.
 */
class AccessMarkersUiTest extends AbstractUiIntegrationTest {

    private static final String REPORT_TYPE = "UiAccessReport";
    private static final String REVIEWER_TYPE = "UiAccessReviewer";

    private static boolean fixtureCreated;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private MarkerDecisionPipelineRepository pipelineRepository;

    // Marker names are unique system-wide, so every name a test creates carries this.
    private final String tag = UUID.randomUUID().toString().substring(0, 8);

    private SchemaPage schema;

    @BeforeEach
    void signInAndOpenSchemaView() {
        createFixtureOnce();
        openAdminUiAs(createLocalAdmin(), "/schema");
        schema = SchemaPage.of(driver);
    }

    @Test
    void aNewItemTypeHasNoMarkersAndAPassThroughPipeline() {
        String type = createScratchType();

        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type).awaitDiagram();

        assertThat(page.markerStatus()).isEqualTo("No markers defined.");
        assertThat(page.diagramTexts()).containsExactly("start", "end");
        assertThat(page.carrierEdgeCount()).isEqualTo(1);
    }

    @Test
    void createsMarkers() {
        String type = createScratchType();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        page.openNewMarkerDialog();
        assertThat(page.dialogTitle()).isEqualTo("New Marker");
        assertThat(page.dialogScope()).isEqualTo("Item type: " + type);
        page.typeName("  Restricted " + tag + "  ").typeDescription("Only reviewers").submitDialog();
        page.createMarker("Confidential " + tag, "");

        // Listed alphabetically, names trimmed.
        page.awaitMarkerNames(List.of("Confidential " + tag, "Restricted " + tag));
        assertThat(markersOf(type)).extracting(AuthorizationRepository.MarkerRow::name, AuthorizationRepository.MarkerRow::description)
                .containsExactlyInAnyOrder(
                        tuple("Restricted " + tag, "Only reviewers"),
                        tuple("Confidential " + tag, null));
    }

    @Test
    void aMarkerNeedsAName() {
        String type = createScratchType();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        String error = page.openNewMarkerDialog().typeName("   ").submitDialogExpectingError();

        assertThat(error).isEqualTo("Name is required.");
        assertThat(page.isDialogOpen()).isTrue();
        assertThat(markersOf(type)).isEmpty();
    }

    @Test
    void cancellingOrEscapingTheDialogCreatesNothing() {
        String type = createScratchType();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        page.openNewMarkerDialog().typeName("Abandoned").cancelDialog();
        page.openNewMarkerDialog().typeName("Also abandoned").pressEscape();

        assertThat(page.markerStatus()).isEqualTo("No markers defined.");
        assertThat(markersOf(type)).isEmpty();
    }

    @Test
    void renamesAMarker() {
        String type = createScratchType();
        UUID markerId = authorizationRepository.createMarker("Draft " + tag, null, "ITEM_TYPE", testData.itemType(type).id()).id();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        page.openEditMarkerDialog("Draft " + tag);
        assertThat(page.dialogTitle()).isEqualTo("Edit Marker");
        assertThat(page.dialogName()).isEqualTo("Draft " + tag);
        page.typeName("Final " + tag).typeDescription("Signed off").submitDialog();

        page.awaitMarkerNames(List.of("Final " + tag));
        assertThat(markersOf(type)).singleElement().satisfies(marker -> {
            assertThat(marker.id()).isEqualTo(markerId);
            assertThat(marker.name()).isEqualTo("Final " + tag);
            assertThat(marker.description()).isEqualTo("Signed off");
        });
    }

    @Test
    void renamingAMarkerSomeoneElseDeletedShowsTheError() {
        String type = createScratchType();
        UUID markerId = authorizationRepository.createMarker("Doomed " + tag, null, "ITEM_TYPE", testData.itemType(type).id()).id();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);
        page.openEditMarkerDialog("Doomed " + tag);

        authorizationRepository.deleteMarker(markerId);
        String error = page.typeName("Renamed " + tag).submitDialogExpectingError();

        assertThat(error).isNotBlank();
        assertThat(page.isDialogOpen()).isTrue();
        assertThat(markersOf(type)).isEmpty();
    }

    @Test
    void deletesAMarkerOnlyOnceConfirmed() {
        String type = createScratchType();
        UUID itemTypeId = testData.itemType(type).id();
        authorizationRepository.createMarker("Keep " + tag, null, "ITEM_TYPE", itemTypeId);
        authorizationRepository.createMarker("Remove " + tag, null, "ITEM_TYPE", itemTypeId);
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        page.deleteMarker("Remove " + tag, false);
        assertThat(page.markerNames()).containsExactly("Keep " + tag, "Remove " + tag);

        page.deleteMarker("Remove " + tag, true);
        page.awaitMarkerNames(List.of("Keep " + tag));
        assertThat(markersOf(type)).extracting(AuthorizationRepository.MarkerRow::name).containsExactly("Keep " + tag);
    }

    @Test
    void anItemTypeWithoutAPipelineSaysSo() {
        String type = createScratchType();
        pipelineRepository.findByItemTypeId(testData.itemType(type).id()).ifPresent(p -> pipelineRepository.deletePipeline(p.id()));

        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type);

        assertThat(page.pipelineAreaText()).isEqualTo("No marker decision pipeline defined.");
    }

    @Test
    void drawsEveryBlockOfThePipelineWithItsMarkers() {
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, REPORT_TYPE).awaitDiagram();

        assertThat(page.blockTitles()).containsExactlyInAnyOrder("Select", "Filter", "Remove", "Set", "Add");
        assertThat(page.markerChips()).containsExactlyInAnyOrder("Internal", "Public", "Public", "Restricted", "Archived", "Archived");
        // The <select> shows its own condition group inline: the shared property, each row's branch,
        // and the (Otherwise) row; <filter> labels its two inputs.
        assertThat(page.diagramTexts()).contains("property: status", "= DRAFT", "= FINAL", "(Otherwise)", "input", "if", "start", "end");
        // start, select, its two rows, filter, remove, set, add -> 8 carrier edges into the next block.
        assertThat(page.carrierEdgeCount()).isEqualTo(8);
    }

    @Test
    void describesEachConditionTheGatesRead() {
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, REPORT_TYPE).awaitDiagram();

        // The status group is drawn inside the <select>; the flags group, read by two blocks, gets
        // its own node.
        assertThat(page.signalTitles()).containsExactlyInAnyOrder("Condition", "Condition", "Condition", "Condition Group");
        assertThat(page.diagramTexts()).contains(
                // score > 5
                "score", "> 5",
                // at least one linked reviewer is in the Approved state
                "count", "linked item: reviewers", "any match", "Review = Approved",
                // every one of two score bounds holds
                "totality", "all match", ">= 1", "<= 10",
                // the flags group: one shared property, a pin per member
                "property: confidential", "is true", "is false");
        // remove and add each read one flag; set reads two conditions; filter reads one.
        assertThat(page.signalEdgeCount()).isEqualTo(5);
    }

    @Test
    void rightClickingABlockOpensItsMenu() {
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, REPORT_TYPE).awaitDiagram();

        page.openContextMenu("Filter");
        assertThat(page.contextMenuItems()).containsExactly("Edit…", "Duplicate", "Delete");
        page.chooseContextMenuItem("Duplicate");

        page.openContextMenu("Set").dismissContextMenu();
        assertThat(page.isContextMenuOpen()).isFalse();
    }

    @Test
    void panningMovesTheViewAndIsRememberedPerItemType() {
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, REPORT_TYPE).awaitDiagram();
        String fitted = page.viewBox();

        page.pan(80, 40);
        String panned = page.viewBox();
        assertThat(panned).isNotEqualTo(fitted);

        page.scroll(0, 120);
        String scrolled = page.viewBox();
        assertThat(scrolled).isNotEqualTo(panned);

        schema.selectItemType(REVIEWER_TYPE);
        page = AccessMarkersPage.open(schema, driver, REPORT_TYPE).awaitDiagram();
        assertThat(page.viewBox()).isEqualTo(scrolled);
    }

    @Test
    void savesADraggedBlockPosition() {
        String type = createScratchTypeWithAddBlock();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type).awaitDiagram();
        double[] before = page.blockPosition("Add");

        page.editLayout();
        page.clickHeaderEditIcon("Add").dismissContextMenu();
        page.dragBlock("Add", 120, 0);
        double[] dragged = page.blockPosition("Add");
        assertThat(dragged[0]).isGreaterThan(before[0]);
        page.saveLayout();

        Point saved = savedLayout(type).positions().get(addBlockId(type));
        assertThat(saved.x()).isCloseTo(dragged[0], within(0.5));
        assertThat(saved.y()).isCloseTo(before[1], within(0.5));
        assertThat(page.blockPosition("Add")).containsExactly(dragged);
    }

    @Test
    void cancellingALayoutEditPutsEverythingBack() {
        String type = createScratchTypeWithAddBlock();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type).awaitDiagram();
        double[] before = page.blockPosition("Add");

        page.editLayout().dragBlock("Add", 120, 60);
        assertThat(page.blockPosition("Add")).isNotEqualTo(before);
        page.cancelLayout();

        assertThat(page.blockPosition("Add")).containsExactly(before);
        assertThat(savedLayout(type).positions()).isNullOrEmpty();
    }

    @Test
    void savesADraggedEdgeBend() {
        String type = createScratchTypeWithAddBlock();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type).awaitDiagram();

        page.editLayout();
        assertThat(page.edgeHandleCount()).isEqualTo(2);
        page.dragFirstEdgeHandle(30, 0).saveLayout();

        assertThat(savedLayout(type).edgeBends()).hasSize(1);
        assertThat(page.edgeHandleCount()).isZero();
    }

    @Test
    void savingALayoutForAPipelineSomeoneElseDeletedShowsTheError() {
        String type = createScratchTypeWithAddBlock();
        AccessMarkersPage page = AccessMarkersPage.open(schema, driver, type).awaitDiagram();
        page.editLayout().dragBlock("Add", 60, 0);

        pipelineRepository.deletePipeline(pipelineRepository.findByItemTypeId(testData.itemType(type).id()).orElseThrow().id());
        String error = page.saveLayoutExpectingError();

        assertThat(error).isNotBlank();
        assertThat(page.isEditingLayout()).isTrue();
    }

    private List<AuthorizationRepository.MarkerRow> markersOf(String itemTypeName) {
        UUID itemTypeId = testData.itemType(itemTypeName).id();
        return authorizationRepository.getAllMarkers().stream().filter(m -> itemTypeId.equals(m.scopeId())).toList();
    }

    private PipelineLayout savedLayout(String itemTypeName) {
        return pipelineRepository.findByItemTypeId(testData.itemType(itemTypeName).id()).orElseThrow().layout();
    }

    private UUID addBlockId(String itemTypeName) {
        return pipelineRepository.findByItemTypeId(testData.itemType(itemTypeName).id()).orElseThrow()
                .pipeline().carrierBlocks().get(0).id();
    }

    /** A new item type, with the pass-through pipeline every new item type gets. */
    private String createScratchType() {
        String name = "UiAccessScratch-" + tag;
        testData.createItemType(name, null, List.of(property("name", PropertyType.STRING)), List.of());
        return name;
    }

    /** A new item type whose pipeline adds one marker: start -> Add -> end. */
    private String createScratchTypeWithAddBlock() {
        String name = createScratchType();
        UUID itemTypeId = testData.itemType(name).id();
        UUID markerId = authorizationRepository.createMarker("Flagged " + tag, null, "ITEM_TYPE", itemTypeId).id();
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.UNCONDITIONAL, List.of(markerId));
        replacePipeline(itemTypeId, new Pipeline(UUID.randomUUID(), List.of(), List.of(add), start, end, List.of(
                new Route(start.id(), List.of(add.id())),
                new Route(add.id(), List.of(end.id())))));
        return name;
    }

    // Every item type is created with a pass-through pipeline, and may only have one.
    private void replacePipeline(UUID itemTypeId, Pipeline pipeline) {
        UUID id = pipelineRepository.findByItemTypeId(itemTypeId).orElseThrow().id();
        pipelineRepository.updatePipeline(id, pipeline, true, new PipelineLayout(Map.of(), Map.of()));
    }

    // The shared context lives for the whole test run, so the fixture is created once, by whichever
    // test runs first.
    private void createFixtureOnce() {
        if (fixtureCreated) {
            return;
        }
        AdminItemDefinitionView report = testData.createItemType(REPORT_TYPE, "status", List.of(
                property("status", PropertyType.STRING),
                property("score", PropertyType.INT),
                property("confidential", PropertyType.BOOLEAN)), List.of());
        UUID reviewerTypeId = testData.createItemType(REVIEWER_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of()).id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(report.id(), "reviewers", "UI test fixture", 0, null),
                new CreatePerspectiveDefinitionMutation(reviewerTypeId, "reports", "UI test fixture", 0, null)), List.of())));
        UUID reviewersPerspectiveId = testData.itemType(REPORT_TYPE).links().get("reviewers").get(0).id();
        AdminStateMachineView review = testData.createStateMachine(REVIEWER_TYPE, "Review", List.of("Pending", "Approved"), List.of(
                new Transition("__start__", "Pending", "Assign"),
                new Transition("Pending", "Approved", "Approve")));

        UUID internal = marker(report, "Internal");
        UUID publicMarker = marker(report, "Public");
        UUID restricted = marker(report, "Restricted");
        UUID archived = marker(report, "Archived");

        UUID status = UiTestData.propertyId(report, "status");
        UUID score = UiTestData.propertyId(report, "score");
        UUID confidential = UiTestData.propertyId(report, "confidential");

        Condition draft = new Condition(UUID.randomUUID(), null, new StringEqualsPredicate(null, "DRAFT"));
        Condition fin = new Condition(UUID.randomUUID(), null, new StringEqualsPredicate(null, "FINAL"));
        ConditionGroup statusGroup = new ConditionGroup(propertyStep(status), List.of(draft, fin));
        Condition yes = new Condition(UUID.randomUUID(), null, new IsTruePredicate(null));
        Condition no = new Condition(UUID.randomUUID(), null, new IsFalsePredicate(null));
        ConditionGroup flagsGroup = new ConditionGroup(propertyStep(confidential), List.of(yes, no));
        Condition highScore = new Condition(UUID.randomUUID(), propertyStep(score),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 5, null));
        Condition approved = new Condition(UUID.randomUUID(), null, new CountedPredicate(
                new StepToItem(reviewersPerspectiveId, true, new StepToStateMachine(review.id())),
                new StateEqualsPredicate(null, UiTestData.stateIds(review).get("Approved")),
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN, 0, null)));
        Condition inRange = new Condition(UUID.randomUUID(), propertyStep(score), new TotalityPredicate(null, Quantity.ALL, List.of(
                new NumberPredicate(null, ComparisonOperator.GREATER_THAN_OR_EQUAL, 1, null),
                new NumberPredicate(null, ComparisonOperator.LESS_THAN_OR_EQUAL, 10, null))));

        AddMarkers draftRow = new AddMarkers(UUID.randomUUID(), Gate.of(draft.id()), List.of(internal));
        AddMarkers finalRow = new AddMarkers(UUID.randomUUID(), Gate.of(fin.id()), List.of(publicMarker));
        Select select = new Select(UUID.randomUUID(), new Gate(List.of(draft.id(), fin.id()), Quantity.NONE), List.of(draftRow, finalRow));
        Filter filter = new Filter(UUID.randomUUID(), Gate.of(highScore.id()));
        RemoveMarkers remove = new RemoveMarkers(UUID.randomUUID(), Gate.of(yes.id()), List.of(publicMarker));
        SetMarkers set = new SetMarkers(UUID.randomUUID(), new Gate(List.of(approved.id(), inRange.id()), Quantity.ANY), List.of(restricted, archived));
        AddMarkers add = new AddMarkers(UUID.randomUUID(), Gate.of(no.id()), List.of(archived));
        Start start = new Start(UUID.randomUUID());
        End end = new End(UUID.randomUUID());

        replacePipeline(report.id(), new Pipeline(UUID.randomUUID(),
                List.of(statusGroup, flagsGroup, highScore, approved, inRange),
                List.of(select, filter, remove, set, add), start, end, List.of(
                        new Route(start.id(), List.of(select.id())),
                        new Route(select.id(), List.of(filter.id())),
                        new Route(draftRow.id(), List.of(filter.id())),
                        new Route(finalRow.id(), List.of(filter.id())),
                        new Route(filter.id(), List.of(remove.id())),
                        new Route(remove.id(), List.of(set.id())),
                        new Route(set.id(), List.of(add.id())),
                        new Route(add.id(), List.of(end.id())))));

        fixtureCreated = true;
    }

    private UUID marker(AdminItemDefinitionView itemType, String name) {
        return authorizationRepository.createMarker(name, null, "ITEM_TYPE", itemType.id()).id();
    }

    private static StepToProperty propertyStep(UUID propertyId) {
        return new StepToProperty(propertyId, false, new Self());
    }
}
