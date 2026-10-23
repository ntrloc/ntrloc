package org.ntrloc.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.support.ui.ExpectedConditions;

class AdminUiSmokeUiTest extends AbstractUiIntegrationTest {

    @Test
    void adminUiLoadsFromServerUnderTest() {
        driver.get(serverUrl(ADMIN_UI_PATH + "/"));

        await().until(ExpectedConditions.textToBe(By.cssSelector("layout-navbar .navbar-brand"), "ntrloc Admin"));
        assertThat(driver.getCurrentUrl()).endsWith(ADMIN_UI_PATH + "/search");

        // The app fetches its extension manifest from the backend before its first render.
        Object extensionsStatus = ((JavascriptExecutor) driver).executeScript("""
                return performance.getEntriesByType('resource')
                    .find(e => e.name.endsWith('/api/admin/ui-extensions'))?.responseStatus""");
        assertThat(extensionsStatus).isEqualTo(200L);
    }
}
