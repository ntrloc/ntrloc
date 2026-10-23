package org.ntrloc.ui;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The admin UI's Schema view: the sidebar accordion (item types, traits, controlled lists) and the
 * selected item type's or trait's detail tabs. Text is read with textContent, as in ItemPanePage.
 */
class SchemaPage {

    private static final By CONTENT = By.cssSelector("schema-view-content");

    private final WebDriver driver;

    private SchemaPage(WebDriver driver) {
        this.driver = driver;
    }

    /** Waits for the schema to load. */
    static SchemaPage of(WebDriver driver) {
        SchemaPage page = new SchemaPage(driver);
        page.await().until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("schema-view")));
        return page;
    }

    /** Opens an accordion section, and waits for its opening animation to finish. */
    SchemaPage openSection(String label) {
        driver.findElement(By.xpath("//schema-view-nav//button[contains(@class,'accordion-tab')][.='" + label + "']")).click();
        await().until(d -> "true".equals(d.findElement(By.xpath(
                "//schema-view-nav//button[contains(@class,'accordion-tab')][.='" + label + "']")).getDomAttribute("aria-expanded")));
        await().until(d -> Boolean.TRUE.equals(((JavascriptExecutor) d).executeScript("return document.getAnimations().length === 0")));
        return this;
    }

    List<String> itemTypeNames() {
        return texts(driver.findElements(By.cssSelector(".schema-item-types-list-item")));
    }

    List<String> traitNames() {
        return texts(driver.findElements(By.cssSelector(".schema-traits-list-item")));
    }

    List<String> controlledListNames() {
        return texts(driver.findElements(By.cssSelector(".schema-controlled-lists-list li")));
    }

    /** Selects an item type, waiting for it to be listed if it was only just created. */
    SchemaPage selectItemType(String name) {
        await().until(ExpectedConditions.elementToBeClickable(
                By.xpath("//button[contains(@class,'schema-item-types-list-item')][.='" + name + "']"))).click();
        return awaitHeading(name);
    }

    SchemaPage selectTrait(String name) {
        driver.findElement(By.xpath("//button[contains(@class,'schema-traits-list-item')][.='" + name + "']")).click();
        return awaitHeading(name);
    }

    /** The highlighted entry in whichever sidebar list is open. */
    String selectedListEntry() {
        return text(driver.findElement(By.cssSelector("schema-view-nav button[aria-current='true']")));
    }

    SchemaPage awaitHeading(String name) {
        await().until(d -> d.findElements(By.cssSelector("schema-view-content h2")).stream().anyMatch(h -> text(h).equals(name)));
        return this;
    }

    String heading() {
        return settled(() -> text(content().findElement(By.tagName("h2"))));
    }

    /** The whole detail area's text, e.g. its placeholder when nothing is selected. */
    String contentText() {
        return text(content());
    }

    List<String> tabs() {
        return texts(content().findElements(By.cssSelector("tab-view-tabs button[role='tab']")));
    }

    SchemaPage openTab(String label) {
        WebElement tab = content().findElement(By.xpath(".//tab-view-tabs/button[@role='tab'][.='" + label + "']"));
        tab.click();
        await().until(d -> "true".equals(tab.getDomAttribute("aria-selected")));
        return this;
    }

    String activeTab() {
        return text(content().findElement(By.cssSelector("tab-view-tabs button[aria-selected='true']")));
    }

    String tabText() {
        return text(content().findElement(By.cssSelector("tab-view-panel")));
    }

    /** The Overview tab's rows, label to value. */
    Map<String, String> overview() {
        return settled(() -> {
            Map<String, String> rows = new LinkedHashMap<>();
            for (WebElement row : content().findElements(By.cssSelector(".entity-overview-row"))) {
                rows.put(text(row.findElement(By.tagName("dt"))), text(row.findElement(By.tagName("dd"))));
            }
            return rows;
        });
    }

    /** Follows one of the Overview tab's links (parent type, trait, implementing item type). */
    SchemaPage followOverviewLink(String name) {
        settled(() -> {
            content().findElement(By.xpath(".//button[contains(@class,'entity-overview-link')][.='" + name + "']")).click();
            return true;
        });
        return awaitHeading(name);
    }

    /** The Properties tab's table, one list of cell texts per row (Group, Name, Description, Type). */
    List<List<String>> propertyRows() {
        return propertyRows(content());
    }

    static List<List<String>> propertyRows(WebElement table) {
        return table.findElements(By.cssSelector("table.property-tree-table tbody tr")).stream()
                .map(row -> texts(row.findElements(By.tagName("td"))))
                .toList();
    }

    SchemaPage toggleGroup(String name) {
        content().findElement(By.xpath(".//button[contains(@class,'property-tree-group-toggle')][normalize-space()='" + name + "']")).click();
        return this;
    }

    SchemaPage filterPropertiesByName(String text) {
        return filter("property-name-filter", text);
    }

    SchemaPage filterPropertiesByDescription(String text) {
        return filter("property-description-filter", text);
    }

    SchemaPage clearNameFilter() {
        content().findElement(By.cssSelector("input[name='property-name-filter'] + .filter-clear")).click();
        return this;
    }

    /** Each link card on an item type's Links tab, as its box and arrow-label texts in reading order. */
    List<List<String>> linkDiagrams() {
        return content().findElements(By.tagName("item-type-link-view")).stream()
                .map(card -> texts(card.findElements(By.cssSelector(".item-type-link-box, .item-type-link-arrow-label"))))
                .toList();
    }

    /** The property table under the link card whose arrows carry this perspective name. */
    WebElement linkCard(String perspectiveName) {
        return content().findElement(By.xpath(".//item-type-link-view[.//span[contains(@class,'item-type-link-arrow-label')][starts-with(.,'"
                + perspectiveName + " (')]]"));
    }

    static void toggleLinkProperties(WebElement card) {
        card.findElement(By.cssSelector(".item-type-link-properties-toggle")).click();
    }

    /** A trait's Links tab: just the perspective names. */
    List<String> linkNames() {
        return texts(content().findElements(By.cssSelector("ul.link-list li")));
    }

    List<String> stateMachineNames() {
        return texts(content().findElements(By.cssSelector(".item-type-state-machine-name")));
    }

    /** The NORMAL states drawn in one machine's diagram (START/END are unlabeled dots). */
    List<String> stateNames(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-node-label")).stream().map(SchemaPage::text).toList();
    }

    int pseudoStateCount(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-node-pseudo-fill")).size();
    }

    /** The transition labels drawn in one machine's diagram. */
    List<String> transitionNames(String machineName) {
        return texts(machine(machineName).findElements(By.cssSelector(".sm-edge-label")));
    }

    int edgeCount(String machineName) {
        return machine(machineName).findElements(By.cssSelector(".sm-edge")).size();
    }

    private WebElement machine(String name) {
        return content().findElement(By.xpath(".//div[contains(@class,'item-type-state-machine')][h3[.='" + name + "']]"));
    }

    private SchemaPage filter(String inputName, String text) {
        WebElement input = content().findElement(By.cssSelector("input[name='" + inputName + "']"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    private <T> T settled(Supplier<T> read) {
        return await().until(d -> read.get());
    }

    private WebElement content() {
        return driver.findElement(CONTENT);
    }

    static List<String> texts(List<WebElement> elements) {
        return elements.stream().map(SchemaPage::text).toList();
    }

    static String text(WebElement element) {
        return element.getDomProperty("textContent").trim();
    }

    // Tries again if React replaces an element mid-read, as it does just after a selection changes.
    private WebDriverWait await() {
        return (WebDriverWait) new WebDriverWait(driver, Duration.ofSeconds(15)).ignoring(StaleElementReferenceException.class);
    }
}
