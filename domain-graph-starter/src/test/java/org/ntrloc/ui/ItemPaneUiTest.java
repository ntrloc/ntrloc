package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.group;
import static org.ntrloc.ui.UiTestData.property;
import static org.ntrloc.ui.UiTestData.propertyId;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.authorization.ManualMarkerAssignmentService;
import org.ntrloc.graph.db.partition.authorization.repository.AuthorizationRepository;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.NewControlledListReference;
import org.ntrloc.graph.db.partition.schema.definition.mutation.SetPropertyControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemLinkPerspectiveView;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminStateMachineView;
import org.ntrloc.graph.db.partition.security.InternalPrincipalResolver;
import org.ntrloc.ui.UiTestData.Transition;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Search view's item pane, end to end: a real user signs in, finds an item with a search pane,
 * opens it, and reads, edits, and deletes it there. Every test creates its own uniquely-named
 * gadget, so edits and deletes never affect another test.
 */
class ItemPaneUiTest extends AbstractUiIntegrationTest {

    private static final String GADGET_TYPE = "UiItemGadget";
    private static final String PART_TYPE = "UiItemPart";
    private static final String TICKET_TYPE = "UiItemTicket";

    private static AdminItemDefinitionView gadgetType;
    private static UUID partTypeId;
    private static UUID linkTypeId;
    private static UUID gadgetPartsPerspectiveId;
    private static UUID partGadgetsPerspectiveId;
    private static AdminStateMachineView ticketWorkflow;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private ManualMarkerAssignmentService markerAssignmentService;

    @Autowired
    private InternalPrincipalResolver principalResolver;

    private LocalUser admin;

    @BeforeEach
    void signIn() {
        createFixtureOnce();
        admin = admin();
        openAdminUiAs(admin, "/search");
    }

    @Test
    void showsTheItemsPropertiesAndOnlyTheTabsThatApply() {
        String name = createGadget().name();

        ItemPanePage item = openItem(name);

        assertThat(item.title()).isEqualTo(GADGET_TYPE + ": " + name);
        // No state machine on this type, so there's no States tab at all.
        assertThat(item.tabs()).containsExactly("Properties", "Links", "Markers");
        assertThat(item.groupPaths()).containsExactly("dimensions");
        assertThat(item.value(null, "name")).isEqualTo(name);
        assertThat(item.value(null, "quantity")).isEqualTo("3");
        assertThat(item.value(null, "price")).isEqualTo("9.5");
        assertThat(item.value(null, "active")).isEqualTo("true");
        assertThat(item.value(null, "tags")).contains("red").contains("blue");
        assertThat(item.value(null, "finish")).isEqualTo("Matte finish");
        assertThat(item.value(null, "features")).isEqualTo("Wi-Fi");
        assertThat(item.value("dimensions", "width")).isEqualTo("12.5");
    }

    @Test
    void savesEditedValues() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.edit()
                .type(null, "quantity", "7")
                .type(null, "price", "4.25")
                .toggle(null, "active")
                .type(null, "tags", "green, yellow")
                .type("dimensions", "width", "30")
                .save();

        assertThat(item.value(null, "quantity")).isEqualTo("7");
        assertThat(item.value(null, "price")).isEqualTo("4.25");
        assertThat(item.value(null, "active")).isEqualTo("false");
        assertThat(item.value(null, "tags")).isEqualTo("green, yellow");
        assertThat(item.value("dimensions", "width")).isEqualTo("30");
    }

    @Test
    void savesControlledListChoices() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.edit()
                .pick("finish", "Gloss finish")
                .toggleChoices("features", "Bluetooth")
                .save();

        assertThat(item.value(null, "finish")).isEqualTo("Gloss finish");
        assertThat(item.value(null, "features")).contains("Wi-Fi").contains("Bluetooth");
    }

    @Test
    void renamingTheItemUpdatesThePaneTitle() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);
        String newName = name + " (renamed)";

        item.edit().type(null, "name", newName).save();

        assertThat(item.title()).isEqualTo(GADGET_TYPE + ": " + newName);
    }

    @Test
    void cancellingAnEditKeepsTheOriginalValues() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.edit().type(null, "quantity", "99").cancel();

        assertThat(item.value(null, "quantity")).isEqualTo("3");
    }

    @Test
    void savingWithNoChangesSimplyLeavesEditMode() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.edit().save();

        assertThat(item.isEditing()).isFalse();
        assertThat(item.value(null, "quantity")).isEqualTo("3");
    }

    @Test
    void showsTheServersValidationErrorAndStaysInEditMode() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        List<String> errors = item.edit().type(null, "name", "").saveExpectingErrors();

        assertThat(errors).anySatisfy(error -> assertThat(error).contains("'name' is required"));
        assertThat(item.isEditing()).isTrue();
    }

    @Test
    void showsLinkedItemsOnTheLinksTab() {
        Gadget gadget = createGadget();
        UUID partId = testData.createItem(partTypeId, Map.of(propertyId(testData.itemType(PART_TYPE), "name"), "Hex bolt"));
        testData.createLink(linkTypeId, gadgetPartsPerspectiveId, gadget.id(), partGadgetsPerspectiveId, partId);

        ItemPanePage item = openItem(gadget.name()).openTab("Links");

        assertThat(item.tabText()).contains("parts").contains("Hex bolt");
    }

    @Test
    void saysSoWhenThereAreNoLinksOrMarkers() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        assertThat(item.openTab("Links").tabText()).isEqualTo("No links.");
        assertThat(item.openTab("Markers").tabText()).isEqualTo("No markers.");
    }

    @Test
    void showsAssignedMarkersOnTheMarkersTab() {
        Gadget gadget = createGadget();
        String markerName = "ui-item-marker-" + UUID.randomUUID();
        var marker = authorizationRepository.createMarker(markerName, "UI test fixture", "ITEM_TYPE", gadgetType.id());
        markerAssignmentService.addItemMarker(gadget.id(), marker.id(),
                principalResolver.byId(admin.id()).orElseThrow(), "UI test");

        ItemPanePage item = openItem(gadget.name()).openTab("Markers");

        assertThat(item.tabText()).isEqualTo(markerName);
    }

    @Test
    void deletingFromThePaneRemovesTheItemAndClosesThePane() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.delete(true);

        item.awaitClosed();
        assertThat(searchFor(name).showsNoResults()).isTrue();
    }

    @Test
    void cancellingADeleteKeepsThePaneOpen() {
        String name = createGadget().name();
        ItemPanePage item = openItem(name);

        item.delete(false);

        assertThat(item.isOpen()).isTrue();
        assertThat(item.title()).isEqualTo(GADGET_TYPE + ": " + name);
    }

    @Test
    void deletingTheItemFromSearchResultsClosesItsOpenPane() {
        String name = createGadget().name();
        SearchPanePage search = searchFor(name);
        search.openResult(name);
        ItemPanePage item = ItemPanePage.titled(driver, GADGET_TYPE + ": " + name).awaitLoaded();

        search.deleteResult(name, true);

        item.awaitClosed();
    }

    @Test
    void openingAResultThatWasDeletedElsewhereSaysItWasNotFound() {
        String name = createGadget().name();
        SearchPanePage stale = searchFor(name);
        searchFor(name).deleteResult(name, true);

        stale.openResult(name);

        ItemPanePage item = ItemPanePage.titled(driver, GADGET_TYPE).awaitLoaded();
        assertThat(item.contentText()).isEqualTo("Item not found.");
    }

    // --- helpers ---

    @Test
    void statesTabShowsTheStateEachMachineIsIn() {
        String name = "Ticket " + UUID.randomUUID();
        UUID ticketTypeId = testData.itemType(TICKET_TYPE).id();
        testData.createItem(ticketTypeId, Map.of(propertyId(testData.itemType(TICKET_TYPE), "name"), name),
                Map.of(ticketWorkflow.id(), UiTestData.stateIds(ticketWorkflow).get("Open")));

        SearchPanePage.openNew(driver).selectItemType(TICKET_TYPE).filter("name", "EQUALS", name).search().openResult(name);
        ItemPanePage item = ItemPanePage.titled(driver, TICKET_TYPE + ": " + name).awaitLoaded();

        assertThat(item.tabs()).containsExactly("Properties", "Links", "States", "Markers");
        assertThat(item.openTab("States").states()).containsExactly(Map.entry("Workflow", "Open"));
    }

    @Test
    void statesTabShowsADashForAMachineTheItemHasNotEntered() {
        String name = "Ticket " + UUID.randomUUID();
        testData.createItem(testData.itemType(TICKET_TYPE).id(), Map.of(propertyId(testData.itemType(TICKET_TYPE), "name"), name));

        SearchPanePage.openNew(driver).selectItemType(TICKET_TYPE).filter("name", "EQUALS", name).search().openResult(name);
        ItemPanePage item = ItemPanePage.titled(driver, TICKET_TYPE + ": " + name).awaitLoaded();

        assertThat(item.openTab("States").states()).containsExactly(Map.entry("Workflow", "—"));
    }

    private SearchPanePage searchFor(String gadgetName) {
        return SearchPanePage.openNew(driver).selectItemType(GADGET_TYPE).filter("name", "EQUALS", gadgetName).search();
    }

    private ItemPanePage openItem(String gadgetName) {
        searchFor(gadgetName).openResult(gadgetName);
        return ItemPanePage.titled(driver, GADGET_TYPE + ": " + gadgetName).awaitLoaded();
    }

    private record Gadget(UUID id, String name) {
    }

    private Gadget createGadget() {
        String name = "Gadget " + UUID.randomUUID().toString().substring(0, 8);
        UUID id = testData.createItem(gadgetType.id(), Map.of(
                propertyId(gadgetType, "name"), name,
                propertyId(gadgetType, "quantity"), 3,
                propertyId(gadgetType, "price"), 9.5,
                propertyId(gadgetType, "active"), true,
                propertyId(gadgetType, "tags"), List.of("red", "blue"),
                propertyId(gadgetType, "finish"), "MATTE",
                propertyId(gadgetType, "features"), List.of("WIFI"),
                propertyId(gadgetType, "dimensions.width"), 12.5));
        return new Gadget(id, name);
    }

    // The shared context lives for the whole test run, so the schema is created once, by whichever
    // test runs first.
    private void createFixtureOnce() {
        if (gadgetType != null) {
            return;
        }
        AdminItemDefinitionView gadget = testData.createItemType(GADGET_TYPE, "name",
                List.of(
                        property("name", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED),
                        property("quantity", PropertyType.INTEGER),
                        property("price", PropertyType.DECIMAL),
                        property("active", PropertyType.BOOLEAN),
                        property("tags", PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL),
                        property("finish", PropertyType.STRING),
                        property("features", PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL)),
                List.of(group("dimensions", property("width", PropertyType.DECIMAL))));

        schemaManager.applyMutations(List.of(
                new CreateControlledListMutation("finish", "UiItemFinish", PropertyType.STRING, List.of(
                        new CreateControlledListMutation.Entry("MATTE", "Matte finish"),
                        new CreateControlledListMutation.Entry("GLOSS", "Gloss finish"))),
                new SetPropertyControlledListMutation(propertyId(gadget, "finish"), new NewControlledListReference("finish")),
                new CreateControlledListMutation("features", "UiItemFeatures", PropertyType.STRING, List.of(
                        new CreateControlledListMutation.Entry("WIFI", "Wi-Fi"),
                        new CreateControlledListMutation.Entry("BLUETOOTH", "Bluetooth"))),
                new SetPropertyControlledListMutation(propertyId(gadget, "features"), new NewControlledListReference("features"))));

        partTypeId = testData.createItemType(PART_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of()).id();
        schemaManager.applyMutations(List.of(new CreateLinkDefinitionMutation(List.of(), List.of(
                new CreatePerspectiveDefinitionMutation(gadget.id(), "parts", "UI test fixture", 0, null),
                new CreatePerspectiveDefinitionMutation(partTypeId, "gadgets", "UI test fixture", 0, null)))));
        AdminItemLinkPerspectiveView parts = testData.itemType(GADGET_TYPE).links().get("parts").get(0);
        gadgetPartsPerspectiveId = parts.id();
        linkTypeId = parts.linkId();
        partGadgetsPerspectiveId = testData.itemType(PART_TYPE).links().get("gadgets").get(0).id();

        testData.createItemType(TICKET_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of());
        ticketWorkflow = testData.createStateMachine(TICKET_TYPE, "Workflow", List.of("Open", "Closed"), List.of(
                new Transition("__start__", "Open", "Open"),
                new Transition("Open", "Closed", "Close")));

        gadgetType = testData.itemType(GADGET_TYPE);
    }
}
