package org.ntrloc.ui;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateControlledListMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateLinkDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePerspectiveDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreatePropertyGroupDefinitionMutation;
import org.ntrloc.ui.UiTestData.Transition;
import org.openqa.selenium.WebElement;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The Schema view, end to end: a real user signs in and browses item types, traits, and controlled
 * lists -- each item type's Structure (overview, property tree, links) and state machine diagrams --
 * for a schema created through the same SchemaManager the application itself uses. Nothing here
 * changes the schema, so the fixture is shared by every test; SchemaEditingUiTest covers editing.
 */
class SchemaViewUiTest extends AbstractUiIntegrationTest {

    private static final String PUBLICATION_TYPE = "UiSchemaPublication";
    private static final String BOOK_TYPE = "UiSchemaBook";
    private static final String AUTHOR_TYPE = "UiSchemaAuthor";
    private static final String TRACKED_TRAIT = "UiSchemaTracked";
    private static final String GENRE_LIST = "UiSchemaGenre";

    private static UUID bookTypeId;

    @Autowired
    private SchemaManager schemaManager;

    private SchemaPage schema;

    @BeforeEach
    void signInAndOpenSchemaView() {
        createFixtureOnce();
        openAdminUiAs(admin(), "/schema");
        schema = SchemaPage.of(driver);
    }

    @Test
    void showsAPlaceholderUntilSomethingIsSelected() {
        assertThat(schema.contentText()).isEqualTo("Select an item type, trait, or controlled list.");
        assertThat(schema.itemTypeNames()).contains(PUBLICATION_TYPE, BOOK_TYPE, AUTHOR_TYPE);
    }

    @Test
    void selectingAnItemTypeShowsItsStructure() {
        schema.selectItemType(BOOK_TYPE);

        assertThat(driver.getCurrentUrl()).endsWith("/schema/item-types/" + bookTypeId);
        assertThat(schema.selectedListEntry()).isEqualTo(BOOK_TYPE);
        assertThat(schema.tabs()).containsExactly("Structure", "State Machines", "Access Markers");
        assertThat(schema.structureCards()).containsExactly("Overview", "Properties", "Links");
        assertThat(schema.itemTypeOverview()).containsExactly(
                entry("Item type name", BOOK_TYPE),
                entry("Description", "UI test fixture"),
                entry("Display label", "title"),
                entry("Abstract type", "No"),
                entry("Parent type", PUBLICATION_TYPE),
                entry("Traits", TRACKED_TRAIT));
        assertThat(schema.overviewEditable()).isFalse();
        assertThat(schema.cardActions("Properties")).isEmpty();
    }

    @Test
    void anItemTypeWithNothingSetShowsDefaults() {
        schema.selectItemType(PUBLICATION_TYPE);

        assertThat(schema.itemTypeOverview()).contains(
                entry("Display label", "Default"),
                entry("Abstract type", "Yes"),
                entry("Parent type", "None"),
                entry("Traits", "None"));
    }

    @Test
    void traitLinksLeadBetweenAnItemTypeAndItsTraits() {
        schema.selectItemType(BOOK_TYPE).followTraitLink(TRACKED_TRAIT);

        assertThat(schema.structureCards()).containsExactly("Overview", "Properties", "Links");
        assertThat(schema.traitImplementers()).containsExactly(BOOK_TYPE);

        // Each selection is its own history entry.
        driver.navigate().back();
        schema.awaitHeading(BOOK_TYPE);
        driver.navigate().forward();
        schema.awaitHeading(TRACKED_TRAIT).followOverviewLink(BOOK_TYPE);
        assertThat(schema.heading()).isEqualTo(BOOK_TYPE);
    }

    @Test
    void anUnknownItemTypeIsReportedAsNotFound() {
        writeCoverage("SchemaViewUiTest-anUnknownItemTypeIsReportedAsNotFound-before");
        driver.get(serverUrl(ADMIN_UI_PATH + "/schema/item-types/" + UUID.randomUUID()));

        assertThat(SchemaPage.of(driver).contentText()).isEqualTo("Item type not found.");
    }

    @Test
    void propertiesCardShowsPropertiesBeforeGroupsAtEveryLevel() {
        schema.selectItemType(BOOK_TYPE);

        assertThat(schema.propertyRows()).containsExactly(
                List.of("keywords", "Search keywords", "String Set (optional)"),
                List.of("pages", "Number of pages", "Integer (optional)"),
                List.of("title", "The book's title", "String (required)"),
                List.of("trackingCode (trait)", "Internal tracking code", "String (optional)"),
                List.of("Group publication"),
                List.of("year", "Year first published", "Integer (optional)"),
                List.of("Group publication > publisher"),
                List.of("name", "Publisher's name", "String (optional)"));
    }

    @Test
    void collapsingAGroupHidesEverythingInsideIt() {
        schema.selectItemType(BOOK_TYPE);

        schema.toggleGroup("publication");
        assertThat(schema.propertyNames()).containsExactly("keywords", "pages", "title", "trackingCode");

        schema.toggleGroup("publication");
        assertThat(schema.propertyNames()).containsExactly("keywords", "pages", "title", "trackingCode", "year", "name");
    }

    @Test
    void filteringKeepsOnlyMatchingPropertiesAndTheGroupsAroundThem() {
        schema.selectItemType(BOOK_TYPE);

        schema.filterProperties("NAME");
        assertThat(schema.propertyRows()).containsExactly(
                List.of("Group publication"),
                List.of("Group publication > publisher"),
                List.of("name", "Publisher's name", "String (optional)"));

        // Descriptions match too.
        schema.filterProperties("first published");
        assertThat(schema.propertyRows()).containsExactly(
                List.of("Group publication"),
                List.of("year", "Year first published", "Integer (optional)"));

        schema.filterProperties("no such property");
        assertThat(schema.propertyRows()).containsExactly(List.of("No matching properties."));

        schema.clearPropertyFilter();
        assertThat(schema.propertyNames()).hasSize(6);
    }

    @Test
    void thePropertyFilterIsInTheCardsTitleBarWhileTheCardIsOpen() {
        schema.selectItemType(BOOK_TYPE);
        assertThat(schema.propertyFilterShown()).isTrue();

        schema.toggleCard("Properties");
        assertThat(schema.propertyFilterShown()).isFalse();

        schema.toggleCard("Properties");
        assertThat(schema.propertyFilterShown()).isTrue();
    }

    @Test
    void aTraitShowsItsOwnPropertiesAndLinks() {
        schema.openSection("Traits").selectTrait(TRACKED_TRAIT);

        assertThat(schema.propertyRows()).containsExactly(List.of("trackingCode", "Internal tracking code", "String (optional)"));
        assertThat(schema.cardText("Links")).isEqualTo("No links yet.");
    }

    @Test
    void linksCardShowsBothDirectionsOfEachLinkAndItsProperties() {
        schema.selectItemType(BOOK_TYPE);

        assertThat(schema.linkDiagrams()).containsExactly(List.of("authors (0-any)", "books (1)", AUTHOR_TYPE));
        assertThat(schema.linkDescriptions("authors")).containsExactly("Who wrote it", "What they wrote");
        WebElement card = schema.expandLink("authors").linkCard("authors");
        assertThat(SchemaPage.propertyRows(card)).containsExactly(List.of("role", "The author's role", "String (optional)"));

        SchemaPage.toggleLinkProperties(card);
        assertThat(SchemaPage.propertyRows(card)).isEmpty();

        schema.selectItemType(AUTHOR_TYPE);
        assertThat(schema.linkDiagrams()).containsExactly(List.of("books (1)", "authors (0-any)", BOOK_TYPE));

        schema.selectItemType(PUBLICATION_TYPE);
        assertThat(schema.cardText("Links")).isEqualTo("No links yet.");
    }

    @Test
    void linksStartFoldedAndStayOpenOnceOpened() {
        schema.selectItemType(BOOK_TYPE);
        assertThat(schema.linkExpanded("authors")).isFalse();
        assertThat(SchemaPage.propertyRows(schema.linkCard("authors"))).isEmpty();

        schema.expandLink("authors").selectItemType(AUTHOR_TYPE).selectItemType(BOOK_TYPE);

        assertThat(schema.linkExpanded("authors")).isTrue();
    }

    @Test
    void stateMachinesTabDrawsEachMachinesStatesAndTransitions() {
        schema.selectItemType(BOOK_TYPE).openTab("State Machines");

        assertThat(schema.stateMachineNames()).containsExactly("Lifecycle");
        assertThat(schema.stateNames("Lifecycle")).containsExactlyInAnyOrder("Draft", "In review", "Published");
        assertThat(schema.pseudoStateCount("Lifecycle")).isEqualTo(2);
        assertThat(schema.transitionNames("Lifecycle"))
                .containsExactlyInAnyOrder("Create", "Submit", "Revise", "Approve", "Reject", "Retract", "Retire");

        schema.selectItemType(PUBLICATION_TYPE);
        assertThat(schema.tabText()).isEqualTo("No state machines yet.");
    }

    @Test
    void theOpenTabAndCollapsedCardsStayThatWayWhileBrowsing() {
        schema.selectItemType(BOOK_TYPE).toggleCard("Links");
        assertThat(schema.cardExpanded("Links")).isFalse();

        schema.selectItemType(AUTHOR_TYPE);
        assertThat(schema.cardExpanded("Links")).isFalse();
        assertThat(schema.propertyNames()).containsExactly("name");
        schema.toggleCard("Links");

        schema.openTab("State Machines");
        schema.openSection("Traits");
        assertThat(driver.getCurrentUrl()).endsWith("/schema/traits");
        schema.openSection("Item Types");
        schema.selectItemType(BOOK_TYPE);
        assertThat(schema.activeTab()).isEqualTo("State Machines");
        schema.openTab("Structure");
        assertThat(schema.cardExpanded("Links")).isTrue();
    }

    @Test
    void sidebarListsTraitsAndControlledLists() {
        schema.openSection("Traits");
        assertThat(schema.traitNames()).contains(TRACKED_TRAIT);
        schema.selectTrait(TRACKED_TRAIT);
        assertThat(schema.selectedListEntry()).isEqualTo(TRACKED_TRAIT);

        schema.openSection("Controlled Lists");
        assertThat(driver.getCurrentUrl()).endsWith("/schema/controlled-lists");
        assertThat(schema.controlledListNames()).contains(GENRE_LIST);
        assertThat(schema.contentText()).isEqualTo("Select an item type, trait, or controlled list.");
    }

    private static CreatePropertyDefinitionMutation described(String name, PropertyType type, String description) {
        return described(name, type, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL, description);
    }

    private static CreatePropertyDefinitionMutation described(String name, PropertyType type, PropertyCardinality cardinality,
                                                              PropertyUsage usage, String description) {
        return new CreatePropertyDefinitionMutation(name, description, type, cardinality, usage, false);
    }

    // The shared context lives for the whole test run, so the schema is created once, by whichever
    // test runs first.
    private void createFixtureOnce() {
        if (bookTypeId != null) {
            return;
        }
        UUID trackedTraitId = testData.createTrait(TRACKED_TRAIT,
                List.of(described("trackingCode", PropertyType.STRING, "Internal tracking code"))).id();
        UUID publicationTypeId = testData.createItemType(PUBLICATION_TYPE, null, List.of(), List.of(), null, true, List.of()).id();
        UUID bookId = testData.createItemType(BOOK_TYPE, "title",
                List.of(
                        described("title", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.REQUIRED, "The book's title"),
                        described("pages", PropertyType.INT, "Number of pages"),
                        described("keywords", PropertyType.STRING, PropertyCardinality.SET, PropertyUsage.OPTIONAL, "Search keywords")),
                List.of(new CreatePropertyGroupDefinitionMutation("publication", "UI test fixture",
                        List.of(described("year", PropertyType.INT, "Year first published")),
                        List.of(new CreatePropertyGroupDefinitionMutation("publisher", "UI test fixture",
                                List.of(described("name", PropertyType.STRING, "Publisher's name")), List.of())))),
                publicationTypeId, false, List.of(trackedTraitId)).id();
        UUID authorTypeId = testData.createItemType(AUTHOR_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of()).id();

        schemaManager.applyMutations(List.of(
                new CreateLinkDefinitionMutation(
                        List.of(described("role", PropertyType.STRING, "The author's role")),
                        List.of(
                                new CreatePerspectiveDefinitionMutation(bookId, "authors", "Who wrote it", 0, null),
                                new CreatePerspectiveDefinitionMutation(authorTypeId, "books", "What they wrote", 1, 1)),
                        List.of()),
                new CreateControlledListMutation("genre", GENRE_LIST, PropertyType.STRING, List.of(
                        new CreateControlledListMutation.Entry("FICTION", "Fiction"),
                        new CreateControlledListMutation.Entry("REFERENCE", "Reference")))));

        testData.createStateMachine(BOOK_TYPE, "Lifecycle", List.of("Draft", "In review", "Published"), List.of(
                new Transition("__start__", "Draft", "Create"),
                new Transition("Draft", "In review", "Submit"),
                new Transition("Draft", "Draft", "Revise"),
                new Transition("In review", "Published", "Approve"),
                new Transition("In review", "Draft", "Reject"),
                new Transition("Published", "Draft", "Retract"),
                new Transition("Published", "__end__", "Retire")));

        bookTypeId = bookId;
    }
}
