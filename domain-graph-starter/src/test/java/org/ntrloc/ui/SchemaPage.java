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
 * selected item type's or trait's detail tabs -- for an item type, the Structure tab's Overview,
 * Properties and Links cards, and editing them. Text is read with textContent, as in ItemPanePage.
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

    /** The item types the sidebar marks as having unsaved changes. */
    List<String> unsavedItemTypes() {
        return texts(driver.findElements(By.xpath(
                "//button[contains(@class,'schema-item-types-list-item')][span[contains(@class,'schema-item-types-unsaved')]]")));
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

    /** The titles of the Structure tab's cards, in order. */
    List<String> structureCards() {
        return texts(content().findElements(By.cssSelector("collapsible-card .collapsible-card-title")));
    }

    SchemaPage toggleCard(String title) {
        card(title).findElement(By.cssSelector(".collapsible-card-toggle")).click();
        return this;
    }

    boolean cardExpanded(String title) {
        return "true".equals(card(title).findElement(By.cssSelector(".collapsible-card-toggle")).getDomAttribute("aria-expanded"));
    }

    /** A Structure card's content, e.g. its placeholder when it's empty. */
    String cardText(String title) {
        return settled(() -> text(card(title).findElement(By.tagName("collapsible-card-body"))));
    }

    /** The buttons in a Structure card's title bar, e.g. "New property" while editing. */
    List<String> cardActions(String title) {
        return texts(card(title).findElements(By.cssSelector("collapsible-card-actions button")));
    }

    /**
     * An item type's Overview card, label to value. Switches read as Yes/No; text fields read the
     * same whether or not the item type is being edited.
     */
    Map<String, String> itemTypeOverview() {
        return settled(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            for (WebElement field : card("Overview").findElements(By.cssSelector("item-type-overview > *"))) {
                fields.put(text(field.findElement(By.cssSelector(".input-control-name"))), fieldValue(field));
            }
            return fields;
        });
    }

    private static String fieldValue(WebElement field) {
        List<WebElement> inputs = field.findElements(By.cssSelector("input.input-control-field"));
        if (!inputs.isEmpty()) {
            return inputs.getFirst().getDomProperty("value");
        }
        List<WebElement> switches = field.findElements(By.cssSelector("[role='switch']"));
        if (!switches.isEmpty()) {
            return "true".equals(switches.getFirst().getDomAttribute("aria-checked")) ? "Yes" : "No";
        }
        return text(field.findElement(By.cssSelector(".input-control-value, .popover-button-value, .popover-button-trigger-label")));
    }

    /** Waits for an Overview field to show a value, e.g. one someone else just saved. */
    SchemaPage awaitOverviewEntry(String fieldLabel, String value) {
        await().until(d -> value.equals(itemTypeOverview().get(fieldLabel)));
        return this;
    }

    /** Whether the Overview's fields can be changed right now (inputs rather than plain text). */
    boolean overviewEditable() {
        return !card("Overview").findElements(By.cssSelector("input.input-control-field")).isEmpty();
    }

    SchemaPage edit() {
        footerButton("Edit").click();
        await().until(d -> !footerButtons("Save").isEmpty());
        return this;
    }

    SchemaPage cancel() {
        footerButton("Cancel").click();
        await().until(d -> !footerButtons("Edit").isEmpty());
        return this;
    }

    /** Saves, and waits until the item type is back out of editing. */
    SchemaPage save() {
        footerButton("Save").click();
        await().until(d -> !footerButtons("Edit").isEmpty());
        return this;
    }

    boolean saveEnabled() {
        return footerButton("Save").isEnabled();
    }

    /** Replaces an Overview text field's value. */
    SchemaPage typeInto(String fieldLabel, String text) {
        WebElement input = card("Overview").findElement(By.xpath(
                ".//input-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    SchemaPage toggleSwitch(String fieldLabel) {
        card("Overview").findElement(By.xpath(
                ".//switch-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//button[@role='switch']")).click();
        return this;
    }

    /** Marks a property for delete, or unmarks it -- its row's trash/undo button. */
    SchemaPage toggleDeleteProperty(String name) {
        card("Properties").findElement(By.xpath(
                ".//td[contains(@class,'property-tree-actions')]//button[@aria-label='Delete " + name + "' or @aria-label='Keep " + name + "']")).click();
        return this;
    }

    /** Opens the property editor from a property's row. */
    SchemaPage editProperty(String name) {
        card("Properties").findElement(By.cssSelector("td.property-tree-actions button[aria-label='Edit " + name + "']")).click();
        await().until(d -> !d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    /** Opens the property editor for a new property, from the Properties card's title bar. */
    SchemaPage newProperty() {
        return openEditor(card("Properties").findElement(By.xpath(".//collapsible-card-actions//button[.='New property']")));
    }

    /** Opens the group editor for a new top-level group, from the Properties card's title bar. */
    SchemaPage newGroup() {
        return openEditor(card("Properties").findElement(By.xpath(".//collapsible-card-actions//button[.='New group']")));
    }

    SchemaPage addPropertyTo(String groupName) {
        return openEditor(card("Properties").findElement(By.cssSelector("button[aria-label='Add property to " + groupName + "']")));
    }

    SchemaPage addGroupTo(String groupName) {
        return openEditor(card("Properties").findElement(By.cssSelector("button[aria-label='Add group to " + groupName + "']")));
    }

    /** Waits for a group to appear in the Properties card, e.g. one someone else just saved. */
    SchemaPage awaitGroup(String groupName) {
        await().until(d -> !card("Properties").findElements(By.xpath(
                ".//div[contains(@class,'property-tree-group-cell')]/*[1][normalize-space(.)='" + groupName + "']")).isEmpty());
        return this;
    }

    /** The buttons on a group's row in the Properties card, by accessible name. */
    List<String> groupActions(String groupName) {
        return card("Properties").findElements(By.xpath(
                        ".//tr[td//div[contains(@class,'property-tree-group-cell')]/*[1][normalize-space(.)='" + groupName + "']]"
                                + "//td[contains(@class,'property-tree-actions')]//button")).stream()
                .map(button -> button.getDomAttribute("aria-label"))
                .toList();
    }

    private SchemaPage openEditor(WebElement button) {
        button.click();
        await().until(d -> !d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    String propertyEditorTitle() {
        return text(dialog().findElement(By.className("dialog-title")));
    }

    boolean propertyEditorCanApply() {
        return dialog().findElement(By.xpath(".//dialog-actions//button[.='Apply']")).isEnabled();
    }

    /** The property editor's validation message, or empty when there's none. */
    String propertyEditorError() {
        return texts(dialog().findElements(By.className("property-edit-error"))).stream().findFirst().orElse("");
    }

    /** The properties whose rows offer an edit button. */
    List<String> editableProperties() {
        return card("Properties").findElements(By.cssSelector("td.property-tree-actions button[aria-label^='Edit ']")).stream()
                .map(button -> button.getDomAttribute("aria-label").substring("Edit ".length()))
                .toList();
    }

    /** Properties with edits not yet saved. */
    List<String> editedProperties() {
        return card("Properties").findElements(By.cssSelector("tr[data-edited] td:nth-child(2)")).stream()
                .map(SchemaPage::text)
                .toList();
    }

    /** The open property editor's fields, label to value. */
    Map<String, String> propertyEditorFields() {
        return settled(() -> {
            Map<String, String> fields = new LinkedHashMap<>();
            for (WebElement field : dialog().findElements(By.cssSelector("property-edit-fields > *"))) {
                fields.put(text(field.findElement(By.cssSelector(".input-control-name"))), fieldValue(field));
            }
            return fields;
        });
    }

    SchemaPage typeInPropertyEditor(String fieldLabel, String text) {
        WebElement input = dialog().findElement(By.xpath(
                ".//input-control[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]//input"));
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    /** Picks one of a choice field's options in the property editor. */
    SchemaPage chooseInPropertyEditor(String fieldLabel, String option) {
        WebElement field = dialog().findElement(By.xpath(
                ".//popover-button[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]"));
        field.findElement(By.cssSelector(".popover-button-trigger")).click();
        field.findElement(By.xpath(".//button[contains(@class,'option-list-option')][.='" + option + "']")).click();
        return this;
    }

    /** The options a choice field in the property editor offers, closing it again afterwards. */
    List<String> propertyEditorOptions(String fieldLabel) {
        WebElement field = dialog().findElement(By.xpath(
                ".//popover-button[span[contains(@class,'input-control-name')][.='" + fieldLabel + "']]"));
        WebElement trigger = field.findElement(By.cssSelector(".popover-button-trigger"));
        trigger.click();
        List<String> options = texts(field.findElements(By.cssSelector(".option-list-option")));
        trigger.click();
        return options;
    }

    SchemaPage applyPropertyEditor() {
        return closePropertyEditor("Apply");
    }

    SchemaPage cancelPropertyEditor() {
        return closePropertyEditor("Cancel");
    }

    private SchemaPage closePropertyEditor(String button) {
        dialog().findElement(By.xpath(".//dialog-actions//button[.='" + button + "']")).click();
        await().until(d -> d.findElements(By.tagName("dialog-view")).isEmpty());
        return this;
    }

    private WebElement dialog() {
        return driver.findElement(By.tagName("dialog-view"));
    }

    /** The properties whose rows offer a delete button: the ones this item type declares itself. */
    List<String> deletableProperties() {
        return card("Properties").findElements(By.cssSelector("td.property-tree-actions button:not([aria-label^='Edit '])")).stream()
                .map(button -> button.getDomAttribute("aria-label").replaceFirst("^(Delete|Keep) ", ""))
                .toList();
    }

    List<String> propertiesMarkedForDelete() {
        return card("Properties").findElements(By.cssSelector("tr[data-marked-for-delete] td:nth-child(2)")).stream()
                .map(SchemaPage::text)
                .toList();
    }

    SchemaPage toggleDeleteLink(String perspectiveName) {
        linkCard(perspectiveName).findElement(By.xpath(
                ".//button[@aria-label='Delete " + perspectiveName + "' or @aria-label='Keep " + perspectiveName + "']")).click();
        return this;
    }

    boolean linkMarkedForDelete(String perspectiveName) {
        return !linkCard(perspectiveName).findElements(By.cssSelector("button[aria-label='Keep " + perspectiveName + "']")).isEmpty();
    }

    /** Fields someone else changed while they were being edited here, e.g. "Description is now “x”". */
    List<String> conflicts() {
        return texts(content().findElements(By.cssSelector("item-type-conflicts li span")));
    }

    SchemaPage awaitConflict() {
        await().until(d -> !conflicts().isEmpty());
        return this;
    }

    SchemaPage keepMine(String fieldLabel) {
        return resolveConflict(fieldLabel, "Keep mine");
    }

    SchemaPage useTheirs(String fieldLabel) {
        return resolveConflict(fieldLabel, "Use theirs");
    }

    private SchemaPage resolveConflict(String fieldLabel, String choice) {
        content().findElement(By.xpath(".//item-type-conflicts//li[@data-field='" + fieldLabel + "']//button[.='" + choice + "']")).click();
        return this;
    }

    /** The trait's Overview tab's rows, label to value. */
    Map<String, String> overview() {
        return settled(() -> {
            Map<String, String> rows = new LinkedHashMap<>();
            for (WebElement row : content().findElements(By.cssSelector(".entity-overview-row"))) {
                rows.put(text(row.findElement(By.tagName("dt"))), text(row.findElement(By.tagName("dd"))));
            }
            return rows;
        });
    }

    /** Follows one of a trait's Overview links to an item type that implements it. */
    SchemaPage followOverviewLink(String name) {
        settled(() -> {
            content().findElement(By.xpath(".//button[contains(@class,'entity-overview-link')][.='" + name + "']")).click();
            return true;
        });
        return awaitHeading(name);
    }

    /**
     * The property table, one list of cell texts per row (Group, Name, Description, Type): an item
     * type's Properties card, or a trait's Properties tab.
     */
    List<List<String>> propertyRows() {
        return propertyRows(propertyTable());
    }

    /** Opens a trait from the "(trait)" link on its group in the Properties card. */
    SchemaPage followTraitLink(String traitName) {
        card("Properties").findElement(By.cssSelector("button.property-tree-trait-link[aria-label='Open trait " + traitName + "']")).click();
        return awaitHeading(traitName);
    }

    static List<List<String>> propertyRows(WebElement table) {
        return table.findElements(By.cssSelector("table.property-tree-table tbody tr")).stream()
                .map(row -> texts(row.findElements(By.tagName("td"))))
                .toList();
    }

    SchemaPage toggleGroup(String name) {
        propertyTable().findElement(By.xpath(".//button[contains(@class,'property-tree-group-toggle')][normalize-space()='" + name + "']")).click();
        return this;
    }

    SchemaPage filterPropertiesByName(String text) {
        return filter("property-name-filter", text);
    }

    SchemaPage filterPropertiesByDescription(String text) {
        return filter("property-description-filter", text);
    }

    SchemaPage clearNameFilter() {
        propertyTable().findElement(By.cssSelector("input[name='property-name-filter'] + .filter-clear")).click();
        return this;
    }

    /** Each link in an item type's Links card, as its box and arrow-label texts in reading order. */
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
        WebElement input = propertyTable().findElement(By.cssSelector("input[name='" + inputName + "']"));
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

    private WebElement card(String title) {
        return content().findElement(By.xpath(
                ".//collapsible-card[.//span[contains(@class,'collapsible-card-title')][.='" + title + "']]"));
    }

    // An item type's properties are in its Properties card -- the Links card holds link property
    // tables too -- while a trait's are on its own Properties tab.
    private WebElement propertyTable() {
        List<WebElement> cards = content().findElements(By.xpath(
                ".//collapsible-card[.//span[contains(@class,'collapsible-card-title')][.='Properties']]"));
        return cards.isEmpty() ? content() : cards.getFirst();
    }

    private WebElement footerButton(String label) {
        return footerButtons(label).getFirst();
    }

    private List<WebElement> footerButtons(String label) {
        return content().findElements(By.xpath(".//item-type-structure-footer//button[.='" + label + "']"));
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
