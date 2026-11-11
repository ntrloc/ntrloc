package org.ntrloc.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.changelog.ChangeLogRetention;
import org.openqa.selenium.By;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// The Operations screen (docs/ntrloc-drafts-plan.md step 2.2): the changelog card shows how long entries
// are kept and what the log holds, and an admin sets the retention in days, or blank to keep everything.
class OperationsUiTest extends AbstractUiIntegrationTest {

    @Autowired
    private ChangeLogRetention retention;

    @BeforeEach
    void openOperations() {
        retention.setRetentionDays(null);
        openAdminUiAs(admin(), "/operations");
        await().until(d -> !d.findElements(By.cssSelector("operations-card-actions button")).isEmpty());
    }

    @AfterEach
    void keepEverything() {
        retention.setRetentionDays(null);
    }

    private WebElement card() {
        return driver.findElement(By.cssSelector("operations-card"));
    }

    private void click(String label) {
        card().findElement(By.xpath(".//button[normalize-space()='" + label + "']")).click();
    }

    private void typeDays(String value) {
        WebElement input = card().findElement(By.cssSelector("input.input-control-field"));
        input.sendKeys(Keys.chord(Keys.COMMAND, "a"), Keys.chord(Keys.CONTROL, "a"), Keys.DELETE);
        input.sendKeys(value);
    }

    @Test
    void byDefault_theChangelogKeepsEverything() {
        assertThat(card().getText()).contains("Changelog").contains("Everything");
    }

    @Test
    void anAdminSetsHowManyDaysToKeep_andCanGoBackToKeepingEverything() {
        click("Edit");
        typeDays("abc");
        assertThat(card().getText()).contains("A whole number of days, at least 1, or blank to keep everything.");

        typeDays("30");
        click("Save");
        await().until(d -> card().getText().contains("30 days"));
        assertThat(retention.retentionDays()).contains(30);

        click("Edit");
        typeDays("");
        click("Save");
        await().until(d -> card().getText().contains("Everything"));
        assertThat(retention.retentionDays()).isEmpty();
    }
}
