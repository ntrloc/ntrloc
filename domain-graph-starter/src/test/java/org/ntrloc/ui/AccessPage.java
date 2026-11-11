package org.ntrloc.ui;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The Access view: the Users and Groups lists, the selected user's or group's detail tabs (Info /
 * Tokens, Group membership, Info / Members, Permission grants), and the dialogs they open.
 */
class AccessPage {

    private static final By DETAIL = By.cssSelector("user-detail-view");
    private static final By DIALOG = By.cssSelector("dialog-view[role='dialog']");
    private static final By CONFIRM = By.cssSelector("modal-popup-view[role='alertdialog']");

    private final WebDriver driver;

    private AccessPage(WebDriver driver) {
        this.driver = driver;
    }

    static AccessPage of(WebDriver driver) {
        var page = new AccessPage(driver);
        page.await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("access-view")));
        return page;
    }

    // --- Lists and tabs ---

    AccessPage section(String label) {
        driver.findElement(By.xpath("//access-view/tab-view/tab-view-tabs/button[normalize-space()='" + label + "']")).click();
        await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("access-list")));
        return this;
    }

    /** Selects a user or group in the list and waits for its detail. */
    AccessPage select(String label) {
        driver.findElement(By.xpath("//access-list//button[.//span[@class='access-list-label' and normalize-space()='" + label + "']]")).click();
        await().until(d -> heading().equals(label));
        return this;
    }

    List<String> listed() {
        return driver.findElements(By.cssSelector("access-list .access-list-label")).stream().map(WebElement::getText).toList();
    }

    String heading() {
        return driver.findElements(By.cssSelector("user-detail-header h2")).stream().findFirst().map(WebElement::getText).orElse("");
    }

    List<String> headerBadges() {
        return driver.findElements(By.cssSelector("user-detail-header span")).stream().map(WebElement::getText).toList();
    }

    AccessPage detailTab(String label) {
        detail().findElement(By.xpath("./tab-view/tab-view-tabs/button[normalize-space()='" + label + "']")).click();
        return this;
    }

    String draftNote() {
        return detail().findElements(By.cssSelector(".access-draft-note")).stream().findFirst().map(WebElement::getText).orElse("");
    }

    boolean hasButton(String label) {
        return !detail().findElements(By.xpath(".//button[normalize-space()='" + label + "']")).isEmpty();
    }

    AccessPage click(String label) {
        detail().findElement(By.xpath(".//button[normalize-space()='" + label + "']")).click();
        return this;
    }

    /** Replaces the text of the detail's (or open dialog's) input labelled `name`. */
    AccessPage type(String name, String value) {
        WebElement scope = dialogOpen() ? dialog() : detail();
        WebElement input = scope.findElement(By.xpath(".//input-control[.//span[@class='input-control-name' and normalize-space()='" + name + "']]//input"));
        input.sendKeys(Keys.chord(Keys.COMMAND, "a"), Keys.chord(Keys.CONTROL, "a"), Keys.DELETE);
        input.sendKeys(value);
        return this;
    }

    String messages() {
        return String.join("\n", detail().findElements(By.cssSelector(".user-dialog-message, .group-overview-message"))
                .stream().map(WebElement::getText).toList());
    }

    AccessPage toggleSwitch(String name) {
        detail().findElement(By.cssSelector("button[role='switch'][aria-label='" + name + "']")).click();
        return this;
    }

    /** Answers the open OK/Cancel confirmation. */
    AccessPage confirm(boolean ok) {
        WebElement popup = await().until(ExpectedConditions.visibilityOfElementLocated(CONFIRM));
        popup.findElement(By.xpath(".//button[normalize-space()='" + (ok ? "OK" : "Cancel") + "']")).click();
        await().until(ExpectedConditions.invisibilityOfElementLocated(CONFIRM));
        return this;
    }

    // --- Dialogs ---

    boolean dialogOpen() {
        return !driver.findElements(DIALOG).isEmpty();
    }

    WebElement dialog() {
        return await().until(ExpectedConditions.visibilityOfElementLocated(DIALOG));
    }

    String dialogText() {
        return dialog().getText();
    }

    boolean dialogButtonEnabled(String label) {
        return dialog().findElement(By.xpath(".//button[normalize-space()='" + label + "']")).isEnabled();
    }

    AccessPage dialogButton(String label) {
        dialog().findElement(By.xpath(".//button[normalize-space()='" + label + "']")).click();
        return this;
    }

    AccessPage dialogChoose(String popoverName, String option) {
        WebElement dialog = dialog();
        dialog.findElement(By.xpath(".//popover-button[.//span[@class='input-control-name' and normalize-space()='" + popoverName + "']]/button")).click();
        dialog.findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='" + option + "']")).click();
        return this;
    }

    void awaitDialogClosed() {
        await().until(ExpectedConditions.invisibilityOfElementLocated(DIALOG));
    }

    // --- Rows: tokens and members ---

    List<String> rows() {
        return detail().findElements(By.cssSelector(".access-rows .access-row-main > span:first-child")).stream().map(WebElement::getText).toList();
    }

    AccessPage rowAction(String ariaLabel) {
        detail().findElement(By.cssSelector("button[aria-label='" + ariaLabel + "']")).click();
        return this;
    }

    /** Picks `option` from the detail's popover button showing `summary` (e.g. "Add member"). */
    AccessPage choose(String summary, String option) {
        detail().findElement(By.xpath(".//button[contains(@class,'popover-button-trigger') and normalize-space()='" + summary + "']")).click();
        detail().findElement(By.xpath(".//ul[@role='listbox']//button[normalize-space()='" + option + "']")).click();
        return this;
    }

    // --- Checkbox trees: group membership, a group's parent ---

    AccessPage tick(String label) {
        checkbox(label).click();
        return this;
    }

    boolean ticked(String label) {
        return checkbox(label).isSelected();
    }

    boolean tickable(String label) {
        return checkbox(label).isEnabled();
    }

    private WebElement checkbox(String label) {
        return driver.findElement(By.xpath("//label[contains(@class,'checkbox-tree-label') and normalize-space()='" + label + "']/input"));
    }

    /** Opens the "Parent group" picker (in edit mode) and ticks a group in it. */
    AccessPage chooseParent(String group) {
        detail().findElement(By.xpath(".//popover-button[.//span[@class='input-control-name' and normalize-space()='Parent group']]/button")).click();
        return tick(group);
    }

    // --- Permission grants ---

    private WebElement card(String itemType) {
        WebElement card = detail().findElement(By.xpath(".//collapsible-card[.//span[@class='collapsible-card-title' and normalize-space()='" + itemType + "']]"));
        if (card.getAttribute("data-expanded") == null) {
            card.findElement(By.cssSelector(".collapsible-card-toggle")).click();
        }
        return card;
    }

    String cardSummary(String itemType) {
        return detail().findElement(By.xpath(".//collapsible-card[.//span[@class='collapsible-card-title' and normalize-space()='" + itemType + "']]"
                + "//span[contains(@class,'permissions-summary')]")).getText();
    }

    /** The item-type pill ("Read" or "Create") on an item type's card: its data-state (direct, inherited, none). */
    String typePill(String itemType, String label) {
        return pill(card(itemType).findElement(By.cssSelector("permissions-type-row")), label).getAttribute("data-state");
    }

    AccessPage toggleTypePill(String itemType, String label) {
        String before = typePill(itemType, label);
        pill(card(itemType).findElement(By.cssSelector("permissions-type-row")), label).click();
        await().until(d -> !typePill(itemType, label).equals(before));
        return this;
    }

    /** Opens a marker's row on an item type's card (if it isn't open) and returns its pills' area/label/state. */
    private WebElement markerRow(String itemType, String marker) {
        WebElement row = card(itemType).findElement(By.xpath(".//li[contains(@class,'permissions-marker')][.//span[@class='permissions-marker-name' and normalize-space()='" + marker + "']]"));
        if (row.getAttribute("data-open") == null) row.findElement(By.cssSelector(".permissions-marker-toggle")).click();
        return row;
    }

    String markerSummary(String itemType, String marker) {
        return markerRow(itemType, marker).findElement(By.cssSelector(".permissions-summary")).getText();
    }

    /** A pill in a marker's area (e.g. "Items"/"Read", or "Properties"/"Write" on the row `rowLabel`). */
    String markerPill(String itemType, String marker, String area, String rowLabel, String label) {
        return pill(markerArea(itemType, marker, area, rowLabel), label).getAttribute("data-state");
    }

    AccessPage toggleMarkerPill(String itemType, String marker, String area, String rowLabel, String label) {
        String before = markerPill(itemType, marker, area, rowLabel, label);
        pill(markerArea(itemType, marker, area, rowLabel), label).click();
        await().until(d -> !markerPill(itemType, marker, area, rowLabel, label).equals(before));
        return this;
    }

    private WebElement markerArea(String itemType, String marker, String area, String rowLabel) {
        WebElement section = markerRow(itemType, marker).findElement(By.xpath(".//permissions-area[h4[normalize-space()='" + area + "']]"));
        if (rowLabel == null) return section.findElement(By.cssSelector("permissions-row"));
        return section.findElement(By.xpath(".//permissions-row[span[@class='permissions-row-label' and normalize-space()='" + rowLabel + "']]"));
    }

    boolean pillsEditable(String itemType) {
        return pill(card(itemType).findElement(By.cssSelector("permissions-type-row")), "Read").isEnabled();
    }

    private static WebElement pill(WebElement scope, String label) {
        return scope.findElement(By.xpath(".//button[contains(@class,'permission-pill') and starts-with(@aria-label,'" + label + ":')]"));
    }

    /**
     * What the current draft marks as changed: the text of each element carrying a modified dot (the
     * sidebar link, a tab, a list entry, a card, a marker row), and each marked pill as "pill {label}".
     */
    List<String> modified() {
        var dotted = driver.findElements(By.cssSelector(".modified-dot")).stream()
                .map(dot -> dot.findElement(By.xpath("..")).getText().trim());
        var pills = driver.findElements(By.cssSelector(".permission-pill[data-modified]")).stream()
                .map(pill -> "pill " + pill.getText().trim());
        return Stream.concat(dotted, pills).toList();
    }

    private WebElement detail() {
        return await().until(ExpectedConditions.visibilityOfElementLocated(DETAIL));
    }

    WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }
}
