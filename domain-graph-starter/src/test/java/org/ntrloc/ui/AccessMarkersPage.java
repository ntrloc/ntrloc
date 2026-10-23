package org.ntrloc.ui;

import static org.ntrloc.ui.SchemaPage.text;
import static org.ntrloc.ui.SchemaPage.texts;

import java.time.Duration;
import java.util.List;

import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.WheelInput;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * An item type's Access Markers tab in the Schema view: the marker list with its create/edit/delete
 * dialogs, and the item type's marker decision pipeline diagram with its layout editing.
 */
class AccessMarkersPage {

    private static final By VIEW = By.tagName("access-markers-view");
    private static final By DIALOG = By.tagName("marker-dialog-view");
    private static final By DIAGRAM = By.cssSelector("svg.marker-pipeline-diagram-svg");
    private static final By CANVAS = By.cssSelector(".marker-pipeline-diagram-scroll");

    private final WebDriver driver;

    private AccessMarkersPage(WebDriver driver) {
        this.driver = driver;
    }

    /** Selects the item type and opens its Access Markers tab. */
    static AccessMarkersPage open(SchemaPage schema, WebDriver driver, String itemTypeName) {
        schema.selectItemType(itemTypeName).openTab("Access Markers");
        AccessMarkersPage page = new AccessMarkersPage(driver);
        page.await().until(ExpectedConditions.presenceOfElementLocated(VIEW));
        return page;
    }

    // --- Markers -----------------------------------------------------------------------------------

    List<String> markerNames() {
        return texts(view().findElements(By.cssSelector(".access-marker-name")));
    }

    /** The marker panel's status line, e.g. "No markers defined." */
    String markerStatus() {
        return text(view().findElement(By.cssSelector("p.status")));
    }

    AccessMarkersPage openNewMarkerDialog() {
        view().findElement(By.xpath(".//button[normalize-space()='+ New Marker']")).click();
        return awaitDialog();
    }

    AccessMarkersPage openEditMarkerDialog(String markerName) {
        markerRow(markerName).findElement(By.xpath(".//button[.='Edit']")).click();
        return awaitDialog();
    }

    String dialogTitle() {
        return text(dialog().findElement(By.cssSelector(".marker-dialog-title")));
    }

    String dialogScope() {
        return text(dialog().findElement(By.cssSelector(".marker-dialog-scope-readout")));
    }

    String dialogName() {
        return dialog().findElement(By.id("marker-name-input")).getDomProperty("value");
    }

    AccessMarkersPage typeName(String name) {
        return replace(dialog().findElement(By.id("marker-name-input")), name);
    }

    AccessMarkersPage typeDescription(String description) {
        return replace(dialog().findElement(By.id("marker-description-input")), description);
    }

    /** Clicks Create or Save, and waits for the dialog to close. */
    AccessMarkersPage submitDialog() {
        clickDialogButton();
        return awaitDialogClosed();
    }

    /** Clicks Create or Save on a dialog the save is expected to fail for, and returns its error. */
    String submitDialogExpectingError() {
        clickDialogButton();
        return text(await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".marker-dialog-error"))));
    }

    AccessMarkersPage cancelDialog() {
        dialog().findElement(By.xpath(".//button[.='Cancel']")).click();
        return awaitDialogClosed();
    }

    AccessMarkersPage pressEscape() {
        dialog().findElement(By.id("marker-name-input")).sendKeys(Keys.ESCAPE);
        return awaitDialogClosed();
    }

    boolean isDialogOpen() {
        return !driver.findElements(DIALOG).isEmpty();
    }

    AccessMarkersPage createMarker(String name, String description) {
        return openNewMarkerDialog().typeName(name).typeDescription(description).submitDialog();
    }

    /** Clicks a marker's Delete, then OK (confirm) or Cancel in the confirmation popup. */
    AccessMarkersPage deleteMarker(String markerName, boolean confirm) {
        markerRow(markerName).findElement(By.xpath(".//button[.='Delete']")).click();
        WebElement popup = await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector("modal-popup-view")));
        popup.findElement(By.xpath(".//button[normalize-space()='" + (confirm ? "OK" : "Cancel") + "']")).click();
        await().until(ExpectedConditions.invisibilityOfElementLocated(By.cssSelector("modal-popup-view")));
        return this;
    }

    AccessMarkersPage awaitMarkerNames(List<String> names) {
        await().until(d -> markerNames().equals(names));
        return this;
    }

    // --- Pipeline diagram ----------------------------------------------------------------------------

    /** The pipeline area's text when there's no diagram, e.g. "No marker decision pipeline defined." */
    String pipelineAreaText() {
        return text(await().until(d -> d.findElement(By.tagName("access-markers-pipeline-area"))));
    }

    /** Waits for the diagram to be measured and drawn. */
    AccessMarkersPage awaitDiagram() {
        await().until(d -> d.findElements(DIAGRAM).stream().anyMatch(svg -> svg.getDomAttribute("viewBox") != null));
        return this;
    }

    List<String> blockTitles() {
        return texts(diagram().findElements(By.cssSelector(".mp-node-title")));
    }

    List<String> signalTitles() {
        return texts(diagram().findElements(By.cssSelector(".mp-signal-node-title")));
    }

    List<String> markerChips() {
        return texts(diagram().findElements(By.cssSelector(".mp-marker-chip-text")));
    }

    /** Every piece of text drawn in the diagram, in document order. */
    List<String> diagramTexts() {
        return texts(diagram().findElements(By.tagName("text")));
    }

    int carrierEdgeCount() {
        return diagram().findElements(By.cssSelector(".mp-edge-path")).size();
    }

    int signalEdgeCount() {
        return diagram().findElements(By.cssSelector(".mp-signal-edge-path")).size();
    }

    String viewBox() {
        return diagram().getDomAttribute("viewBox");
    }

    /** Drags the empty canvas by this many screen pixels. */
    AccessMarkersPage pan(int dx, int dy) {
        WebElement canvas = driver.findElement(CANVAS);
        // From near the top-left corner, clear of any node.
        int fromX = -canvas.getRect().getWidth() / 2 + 10;
        int fromY = -canvas.getRect().getHeight() / 2 + 10;
        new Actions(driver).moveToElement(canvas, fromX, fromY).clickAndHold().moveByOffset(dx, dy).release().perform();
        return this;
    }

    /** Scrolls the mouse wheel over the canvas, which pans it. */
    AccessMarkersPage scroll(int dx, int dy) {
        new Actions(driver).scrollFromOrigin(WheelInput.ScrollOrigin.fromElement(driver.findElement(CANVAS)), dx, dy).perform();
        return this;
    }

    AccessMarkersPage openContextMenu(String blockTitle) {
        new Actions(driver).contextClick(node(blockTitle).findElement(By.cssSelector(".mp-node-title"))).perform();
        await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".mp-context-menu")));
        return this;
    }

    List<String> contextMenuItems() {
        return texts(driver.findElements(By.cssSelector(".mp-context-menu .mp-context-menu-item")));
    }

    boolean isContextMenuOpen() {
        return !driver.findElements(By.cssSelector(".mp-context-menu")).isEmpty();
    }

    AccessMarkersPage chooseContextMenuItem(String label) {
        driver.findElement(By.xpath("//div[contains(@class,'mp-context-menu')]/button[.='" + label + "']")).click();
        await().until(d -> !isContextMenuOpen());
        return this;
    }

    /** Clicks a node's header Edit icon (shown only while editing the layout). */
    AccessMarkersPage clickHeaderEditIcon(String blockTitle) {
        node(blockTitle).findElement(By.cssSelector(".mp-header-icon-edit")).click();
        await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".mp-context-menu")));
        return this;
    }

    /** Clicks the overlay behind an open context menu, away from the menu, which dismisses it. */
    AccessMarkersPage dismissContextMenu() {
        WebElement overlay = driver.findElement(By.cssSelector(".mp-context-menu-overlay"));
        new Actions(driver).moveToElement(overlay, -overlay.getRect().getWidth() / 2 + 5, -overlay.getRect().getHeight() / 2 + 5).click().perform();
        await().until(d -> !isContextMenuOpen());
        return this;
    }

    boolean isEditingLayout() {
        return !editBar().findElements(By.xpath(".//button[.='Save']")).isEmpty();
    }

    AccessMarkersPage editLayout() {
        editBar().findElement(By.xpath(".//button[.='Edit']")).click();
        await().until(d -> isEditingLayout());
        return this;
    }

    AccessMarkersPage saveLayout() {
        editBar().findElement(By.xpath(".//button[.='Save']")).click();
        await().until(d -> !isEditingLayout());
        return this;
    }

    String saveLayoutExpectingError() {
        editBar().findElement(By.xpath(".//button[.='Save']")).click();
        return text(await().until(ExpectedConditions.visibilityOfElementLocated(By.cssSelector(".mp-edit-error"))));
    }

    AccessMarkersPage cancelLayout() {
        editBar().findElement(By.xpath(".//button[.='Cancel']")).click();
        await().until(d -> !isEditingLayout());
        return this;
    }

    /** Drags a block (by its title) this many screen pixels. */
    AccessMarkersPage dragBlock(String blockTitle, int dx, int dy) {
        new Actions(driver).clickAndHold(node(blockTitle).findElement(By.cssSelector(".mp-node-title")))
                .moveByOffset(dx, dy).release().perform();
        return this;
    }

    /** Drags the first edge's bend handle this many screen pixels. */
    AccessMarkersPage dragFirstEdgeHandle(int dx, int dy) {
        new Actions(driver).clickAndHold(diagram().findElement(By.cssSelector(".mp-edge-handle"))).moveByOffset(dx, dy).release().perform();
        return this;
    }

    int edgeHandleCount() {
        return diagram().findElements(By.cssSelector(".mp-edge-handle")).size();
    }

    /** A block's drawn position, in diagram units. */
    double[] blockPosition(String blockTitle) {
        WebElement rect = node(blockTitle).findElement(By.cssSelector("rect.mp-node-rect"));
        return new double[] {Double.parseDouble(rect.getDomAttribute("x")), Double.parseDouble(rect.getDomAttribute("y"))};
    }

    private WebElement node(String blockTitle) {
        return diagram().findElement(By.xpath(".//*[local-name()='g'][contains(@class,'mp-node')][*[local-name()='text'][contains(@class,'mp-node-title')][.='"
                + blockTitle + "']]"));
    }

    private WebElement diagram() {
        return driver.findElement(DIAGRAM);
    }

    private WebElement editBar() {
        return driver.findElement(By.cssSelector(".mp-edit-bar"));
    }

    private WebElement view() {
        return driver.findElement(VIEW);
    }

    private WebElement dialog() {
        return driver.findElement(DIALOG);
    }

    private WebElement markerRow(String markerName) {
        return view().findElement(By.xpath(".//ul[contains(@class,'access-markers-list')]/li[span[contains(@class,'access-marker-name')][.='"
                + markerName + "']]"));
    }

    private void clickDialogButton() {
        dialog().findElement(By.xpath(".//marker-dialog-actions/button[.='Create' or .='Save']")).click();
    }

    private AccessMarkersPage awaitDialog() {
        await().until(ExpectedConditions.visibilityOfElementLocated(DIALOG));
        return this;
    }

    private AccessMarkersPage awaitDialogClosed() {
        await().until(d -> !isDialogOpen());
        return this;
    }

    private AccessMarkersPage replace(WebElement input, String text) {
        input.sendKeys(Keys.chord(Keys.CONTROL, "a"), Keys.BACK_SPACE);
        input.sendKeys(text);
        return this;
    }

    private WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }
}
