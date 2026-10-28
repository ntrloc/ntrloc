package org.ntrloc.ui;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * One search pane in the admin UI's Search view, driven the way a person would: through its
 * item-type, properties, sort, filter, and page-size controls, its Search button, its results, and
 * its pager. Every action that triggers a search waits for the new results to finish rendering.
 */
class SearchPanePage {

    // The pane's three PopoverButtons, in the order SearchPaneControlsView renders them. TreeSelect
    // (the properties picker) is a different element, so it doesn't count toward these positions.
    private static final By ITEM_TYPE_TRIGGER = By.cssSelector("search-pane-controls-view > popover-button:nth-of-type(1) > .popover-button-trigger");
    private static final By SORT_POPOVER = By.cssSelector("search-pane-controls-view > popover-button:nth-of-type(2)");
    private static final By FILTER_POPOVER = By.cssSelector("search-pane-controls-view > popover-button:nth-of-type(3)");
    private static final By RESULTS_LIST = By.cssSelector("search-pane-results-list");
    private static final By RESULT = By.cssSelector("search-result-item-view");

    private final WebDriver driver;
    private final WebElement pane;

    private SearchPanePage(WebDriver driver, WebElement pane) {
        this.driver = driver;
        this.pane = pane;
    }

    /** Opens a new search pane from the Search view's header and returns it. */
    static SearchPanePage openNew(WebDriver driver) {
        WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        // Waited for, not assumed: just after a page load the view may not have rendered yet, and
        // counting its panes before it has would count none.
        WebElement button = wait.until(ExpectedConditions.elementToBeClickable(
                By.xpath("//search-header-view//button[normalize-space()='New search pane']")));
        int before = driver.findElements(By.cssSelector("pane-view")).size();
        button.click();
        wait.until(d -> d.findElements(By.cssSelector("pane-view")).size() > before);
        List<WebElement> panes = driver.findElements(By.cssSelector("pane-view"));
        return new SearchPanePage(driver, panes.get(panes.size() - 1));
    }

    SearchPanePage selectItemType(String name) {
        WebElement trigger = pane.findElement(ITEM_TYPE_TRIGGER);
        await().until(d -> trigger.isEnabled());
        trigger.click();
        pane.findElement(By.xpath(".//popover-button-popover//button[normalize-space()='" + name + "']")).click();
        return this;
    }

    SearchPanePage sortBy(String field, boolean descending) {
        WebElement sort = pane.findElement(SORT_POPOVER);
        sort.findElement(By.cssSelector(".popover-button-trigger")).click();
        List<WebElement> pickers = sort.findElements(By.cssSelector("popover-button-popover popover-button > .popover-button-trigger"));
        pickers.get(0).click();
        sort.findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='" + field + "']")).click();
        pickers.get(1).click();
        sort.findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='"
                + (descending ? "Descending" : "Ascending") + "']")).click();
        sort.findElement(By.cssSelector(".popover-button-trigger")).click();
        return this;
    }

    /** operator is a FilterOperator wire value, e.g. EQUALS or LIKE. */
    SearchPanePage filter(String property, String operator, String value) {
        WebElement filter = pane.findElement(FILTER_POPOVER);
        filter.findElement(By.cssSelector(".popover-button-trigger")).click();
        new Select(filter.findElement(By.name("filter-property"))).selectByVisibleText(property);
        new Select(filter.findElement(By.name("filter-operator"))).selectByValue(operator);
        filter.findElement(By.cssSelector("input.search-pane-filter-value")).sendKeys(value);
        filter.findElement(By.cssSelector(".popover-button-trigger")).click();
        return this;
    }

    SearchPanePage pageSize(int size) {
        new Select(pane.findElement(By.name("page-size"))).selectByValue(String.valueOf(size));
        return this;
    }

    /** Unchecks "(All properties)", then checks only the named top-level properties. */
    SearchPanePage onlyProperties(String... names) {
        WebElement trigger = pane.findElement(By.cssSelector("tree-select > .tree-select-trigger"));
        trigger.click();
        checkboxLabeled("(All properties)").click();
        for (String name : names) {
            checkboxLabeled(name).click();
        }
        trigger.click();
        return this;
    }

    SearchPanePage search() {
        return waitingForNewResults(() -> pane.findElement(By.xpath(".//search-pane-controls-view//button[normalize-space()='Search']")).click());
    }

    SearchPanePage nextPage() {
        return waitingForNewResults(() -> pane.findElement(By.cssSelector(".pager-next-btn")).click());
    }

    SearchPanePage previousPage() {
        return waitingForNewResults(() -> pane.findElement(By.cssSelector(".pager-prev-btn")).click());
    }

    SearchPanePage goToPage(int page) {
        WebElement input = pane.findElement(By.name("page"));
        return waitingForNewResults(() -> {
            input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE, String.valueOf(page));
            input.sendKeys(Keys.ENTER);
        });
    }

    List<String> resultLabels() {
        return pane.findElements(By.cssSelector("search-result-item-view .search-result-item-label"))
                .stream().map(WebElement::getText).toList();
    }

    List<String> resultTypes() {
        return pane.findElements(By.cssSelector("search-result-item-view .search-result-item-type"))
                .stream().map(WebElement::getText).toList();
    }

    /** For each result, the property names at the top level of its property tree. */
    List<List<String>> propertyNamesOfEachResult() {
        return pane.findElements(RESULT).stream()
                .map(result -> result.findElements(By.cssSelector("property-tree-view > ul > li > .property-tree-leaf > .property-tree-leaf-name"))
                        .stream().map(WebElement::getText).toList())
                .toList();
    }

    boolean showsNoResults() {
        return !pane.findElements(By.xpath(".//search-pane-results-list/p[normalize-space()='No results.']")).isEmpty();
    }

    String pagerText() {
        return pane.findElement(By.cssSelector(".pager")).getText().replaceAll("\\s+", " ").trim();
    }

    int currentPage() {
        return Integer.parseInt(pane.findElement(By.name("page")).getDomProperty("value"));
    }

    /** Clicks the result's delete button, then OK (confirm) or Cancel in the confirmation popup. */
    SearchPanePage deleteResult(String label, boolean confirm) {
        result(label).findElement(By.cssSelector(".search-result-item-delete-btn")).click();
        WebElement dialog = await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("modal-popup-view")));
        WebElement button = dialog.findElement(By.xpath(".//button[normalize-space()='" + (confirm ? "OK" : "Cancel") + "']"));
        if (!confirm) {
            button.click();
            await().until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("modal-popup-view")));
            return this;
        }
        return waitingForNewResults(button::click);
    }

    void openResult(String label) {
        result(label).findElement(By.cssSelector(".search-result-item-open-btn")).click();
    }

    // textContent, not getText(): the header styles its title uppercase, and getText() returns
    // the rendered text.
    String title() {
        return pane.findElement(By.cssSelector(".pane-view-title")).getDomProperty("textContent");
    }

    boolean isMaximized() {
        return pane.getDomAttribute("data-maximized") != null;
    }

    void toggleMaximize() {
        pane.findElement(By.cssSelector("pane-view-header button[title='Maximize'], pane-view-header button[title='Restore']")).click();
    }

    void close() {
        pane.findElement(By.cssSelector("pane-view-header button[title='Close']")).click();
    }

    private WebElement result(String label) {
        return pane.findElement(By.xpath(".//search-result-item-view[.//span[contains(@class,'search-result-item-label')][normalize-space()='"
                + label + "']]"));
    }

    private WebElement checkboxLabeled(String label) {
        return pane.findElement(By.xpath(".//tree-select-popover//label[normalize-space()='" + label + "']/input[@type='checkbox']"));
    }

    // A search replaces the results list with "Searching…" and then a new list, so the old list
    // going stale is the signal that the new search has started; the next list appearing (or
    // "Search failed.") is the signal that it has finished.
    private SearchPanePage waitingForNewResults(Runnable action) {
        List<WebElement> previous = pane.findElements(RESULTS_LIST);
        action.run();
        if (!previous.isEmpty()) {
            await().until(ExpectedConditions.stalenessOf(previous.get(0)));
        }
        await().until(d -> !pane.findElements(RESULTS_LIST).isEmpty()
                || pane.findElement(By.cssSelector("search-pane-results-view")).getText().contains("Search failed."));
        return this;
    }

    private WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    int resultCount() {
        return pane.findElements(RESULT).size();
    }
}
