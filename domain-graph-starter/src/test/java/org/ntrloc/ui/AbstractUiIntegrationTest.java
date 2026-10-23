package org.ntrloc.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.testcontainers.Testcontainers;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for Selenium tests of the React admin UI (ntrloc-admin-ui). The browser runs in a container
 * and reaches this JVM's server through host.testcontainers.internal. The UI is served from the
 * Istanbul-instrumented build in ntrloc-admin-ui/target/dist-coverage/ (npm run build:coverage),
 * and each test's window.__coverage__ is written to ntrloc-admin-ui/target/coverage-data/ for
 * npm run coverage:report to merge into an LCOV file.
 *
 * Shares AbstractIntegrationTest's application context (TestApplication scans UI hosting, and the
 * /admin-react mount is in the test application.yml): a second context against the same Postgres
 * container would re-run startup seeding and break every integration test after it. The browser
 * container is a JVM-wide singleton for the same reason as the Postgres one.
 */
public abstract class AbstractUiIntegrationTest extends AbstractIntegrationTest {

    protected static final String ADMIN_UI_PATH = "/admin-react";

    private static final Path ADMIN_UI_DIR = Path.of("..", "ntrloc-admin-ui").toAbsolutePath().normalize();
    private static final Path INSTRUMENTED_BUILD_DIR = ADMIN_UI_DIR.resolve("target/dist-coverage");
    private static final Path COVERAGE_OUTPUT_DIR = ADMIN_UI_DIR.resolve("target/coverage-data");

    // Multi-arch (unlike selenium/standalone-chrome), so it runs natively on Apple Silicon too.
    private static final DockerImageName BROWSER_IMAGE = DockerImageName
            .parse("selenium/standalone-chromium:4.43.0-20260404")
            .asCompatibleSubstituteFor("selenium/standalone-chrome");

    protected static final BrowserWebDriverContainer BROWSER = new BrowserWebDriverContainer(BROWSER_IMAGE)
            .withAccessToHost(true);

    static {
        if (!Files.isRegularFile(INSTRUMENTED_BUILD_DIR.resolve("index.html"))) {
            throw new IllegalStateException("No instrumented admin UI build at " + INSTRUMENTED_BUILD_DIR
                    + " -- run `npm run build:coverage` in ntrloc-admin-ui, or a Maven build without -DskipUiTests");
        }
        BROWSER.start();
    }

    @LocalServerPort
    private int port;

    protected WebDriver driver;

    @BeforeEach
    void openBrowser() {
        Testcontainers.exposeHostPorts(port);
        driver = new RemoteWebDriver(BROWSER.getSeleniumAddress(), new ChromeOptions());
    }

    @AfterEach
    void closeBrowser(TestInfo testInfo) {
        try {
            writeCoverage(testInfo.getTestClass().orElseThrow().getSimpleName() + "-"
                    + testInfo.getTestMethod().orElseThrow().getName());
        } finally {
            driver.quit();
        }
    }

    protected String serverUrl(String path) {
        return "http://host.testcontainers.internal:" + port + path;
    }

    protected WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    /**
     * window.__coverage__ is reset by every full page load, so a test that navigates with
     * driver.get() more than once must call this (with a distinct name) before each one.
     */
    protected void writeCoverage(String name) {
        Object coverage = ((JavascriptExecutor) driver)
                .executeScript("return window.__coverage__ ? JSON.stringify(window.__coverage__) : null");
        if (coverage == null) {
            return;
        }
        try {
            Files.createDirectories(COVERAGE_OUTPUT_DIR);
            Files.writeString(COVERAGE_OUTPUT_DIR.resolve(name + ".json"), coverage.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
