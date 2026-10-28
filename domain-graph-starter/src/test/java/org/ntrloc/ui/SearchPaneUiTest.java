package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ntrloc.ui.UiTestData.property;
import static org.ntrloc.ui.UiTestData.propertyId;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.view.admin.AdminItemDefinitionView;
import org.openqa.selenium.By;

/**
 * The Search view's search pane, end to end: a real user signs in, picks an item type, and
 * searches, sorts, filters, pages, narrows properties, and deletes, against items created through
 * the same schema and ledger paths the application itself uses.
 *
 * WIDGET_TYPE holds a fixed set of twelve items that no test changes, so result counts and orders
 * are exact. Tests that delete use SCRATCH_TYPE and create their own uniquely-named item.
 */
class SearchPaneUiTest extends AbstractUiIntegrationTest {

    private static final String WIDGET_TYPE = "UiSearchWidget";
    private static final String SCRATCH_TYPE = "UiSearchScratch";
    private static final int WIDGET_COUNT = 12;

    private static UUID scratchTypeId;
    private static UUID scratchNamePropertyId;

    private SearchPanePage pane;

    @BeforeEach
    void signInAndOpenSearchPane() {
        createFixtureOnce();
        openAdminUiAs(admin(), "/search");
        pane = SearchPanePage.openNew(driver);
    }

    @Test
    void searchShowsEveryItemOfTheSelectedType() {
        pane.selectItemType(WIDGET_TYPE).search();

        assertThat(pane.resultLabels()).containsExactlyInAnyOrderElementsOf(widgetNames(1, WIDGET_COUNT));
        assertThat(pane.resultTypes()).containsOnly(WIDGET_TYPE);
    }

    @Test
    void sortsByAnIntPropertyNumerically() {
        pane.selectItemType(WIDGET_TYPE).sortBy("rank", true).search();

        // Descending by value, not by text: a text sort would put "9" ahead of "12".
        assertThat(pane.resultLabels()).containsExactlyElementsOf(widgetNames(WIDGET_COUNT, 1));
    }

    @Test
    void filtersByEquality() {
        pane.selectItemType(WIDGET_TYPE).filter("color", "EQUALS", "red").search();

        assertThat(pane.resultLabels()).containsExactlyInAnyOrder(
                "Widget 01", "Widget 03", "Widget 05", "Widget 07", "Widget 09", "Widget 11");
    }

    @Test
    void filtersByPattern() {
        pane.selectItemType(WIDGET_TYPE).filter("name", "LIKE", "Widget 1%").search();

        assertThat(pane.resultLabels()).containsExactlyInAnyOrder("Widget 10", "Widget 11", "Widget 12");
    }

    @Test
    void showsNoResultsWhenNothingMatches() {
        pane.selectItemType(WIDGET_TYPE).filter("name", "EQUALS", "No such widget").search();

        assertThat(pane.showsNoResults()).isTrue();
        assertThat(pane.resultLabels()).isEmpty();
    }

    @Test
    void pagesThroughResults() {
        pane.selectItemType(WIDGET_TYPE).sortBy("rank", false).pageSize(10).search();
        assertThat(pane.resultLabels()).containsExactlyElementsOf(widgetNames(1, 10));
        assertThat(pane.pagerText()).contains("of 2");

        pane.nextPage();
        assertThat(pane.resultLabels()).containsExactly("Widget 11", "Widget 12");
        assertThat(pane.currentPage()).isEqualTo(2);

        pane.previousPage();
        assertThat(pane.resultLabels()).containsExactlyElementsOf(widgetNames(1, 10));

        pane.goToPage(2);
        assertThat(pane.resultLabels()).containsExactly("Widget 11", "Widget 12");
    }

    @Test
    void showsOnlyTheSelectedProperties() {
        pane.selectItemType(WIDGET_TYPE).onlyProperties("rank").search();

        assertThat(pane.propertyNamesOfEachResult()).hasSize(WIDGET_COUNT).allSatisfy(names -> assertThat(names).containsExactly("rank"));
    }

    // The display label comes from "name", which isn't one of the selected properties -- results
    // must still be labeled by it rather than by their raw item ids.
    @Test
    void keepsDisplayLabelsWhenTheirPropertyIsNotSelected() {
        pane.selectItemType(WIDGET_TYPE).onlyProperties("rank").search();

        assertThat(pane.resultLabels()).containsExactlyInAnyOrderElementsOf(widgetNames(1, WIDGET_COUNT));
    }

    @Test
    void cancellingADeleteKeepsTheItem() {
        String name = createScratchItem();
        pane.selectItemType(SCRATCH_TYPE).filter("name", "EQUALS", name).search();

        pane.deleteResult(name, false);

        assertThat(pane.resultLabels()).containsExactly(name);
    }

    @Test
    void confirmingADeleteRemovesTheItem() {
        String name = createScratchItem();
        pane.selectItemType(SCRATCH_TYPE).filter("name", "EQUALS", name).search();

        pane.deleteResult(name, true);

        assertThat(pane.showsNoResults()).isTrue();
        pane.search();
        assertThat(pane.showsNoResults()).isTrue();
    }

    @Test
    void openingAResultOpensItsItemPane() {
        pane.selectItemType(WIDGET_TYPE).search();

        pane.openResult("Widget 03");

        await().until(d -> (WIDGET_TYPE + ": Widget 03").equals(
                d.findElement(By.cssSelector("pane-view:nth-of-type(2) .pane-view-title")).getDomProperty("textContent")));
    }

    @Test
    void maximizesRestoresAndClosesPanes() {
        SearchPanePage second = SearchPanePage.openNew(driver);
        assertThat(second.title()).isNotEqualTo(pane.title());

        pane.toggleMaximize();
        assertThat(pane.isMaximized()).isTrue();
        assertThat(second.isMaximized()).isFalse();

        pane.toggleMaximize();
        assertThat(pane.isMaximized()).isFalse();

        pane.close();
        assertThat(driver.findElements(By.cssSelector("pane-view"))).hasSize(1);
    }

    private static List<String> widgetNames(int from, int to) {
        IntStream range = from <= to
                ? IntStream.rangeClosed(from, to)
                : IntStream.rangeClosed(to, from).map(i -> from + to - i);
        return range.mapToObj(SearchPaneUiTest::widgetName).toList();
    }

    private static String widgetName(int n) {
        return "Widget %02d".formatted(n);
    }

    private String createScratchItem() {
        String name = "Scratch " + UUID.randomUUID();
        testData.createItem(scratchTypeId, Map.of(scratchNamePropertyId, name));
        return name;
    }

    // The shared context lives for the whole test run, so the fixture is created once, by
    // whichever test runs first.
    private void createFixtureOnce() {
        if (scratchTypeId != null) {
            return;
        }
        AdminItemDefinitionView widget = testData.createItemType(WIDGET_TYPE, "name",
                List.of(property("name", PropertyType.STRING), property("rank", PropertyType.INT), property("color", PropertyType.STRING)),
                List.of());
        UUID name = propertyId(widget, "name");
        UUID rank = propertyId(widget, "rank");
        UUID color = propertyId(widget, "color");
        for (int n = 1; n <= WIDGET_COUNT; n++) {
            testData.createItem(widget.id(), Map.of(name, widgetName(n), rank, n, color, n % 2 == 1 ? "red" : "blue"));
        }

        AdminItemDefinitionView scratch = testData.createItemType(SCRATCH_TYPE, "name", List.of(property("name", PropertyType.STRING)), List.of());
        scratchNamePropertyId = propertyId(scratch, "name");
        scratchTypeId = scratch.id();
    }
}
