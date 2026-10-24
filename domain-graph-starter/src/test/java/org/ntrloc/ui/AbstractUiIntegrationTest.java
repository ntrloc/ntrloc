package org.ntrloc.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.logging.LogType;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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
 * Every test signs in through the real login page as a real local user it creates
 * (createLocalAdmin), exactly as a person would -- nothing about authentication is stubbed.
 *
 * Shares AbstractIntegrationTest's application context (TestApplication scans UI hosting, and the
 * /admin-react mount is in the test application.yml): a second context against the same Postgres
 * container would re-run startup seeding and break every integration test after it. The browser
 * container is a JVM-wide singleton for the same reason as the Postgres one.
 *
 * A failing test leaves a screenshot, the page's HTML and the browser console in
 * target/ui-test-failures/ (see FailureCapture).
 */
@ExtendWith(AbstractUiIntegrationTest.FailureCapture.class)
public abstract class AbstractUiIntegrationTest extends AbstractIntegrationTest {

    protected static final String ADMIN_UI_PATH = "/admin-react";

    private static final Path ADMIN_UI_DIR = Path.of("..", "ntrloc-admin-ui").toAbsolutePath().normalize();
    private static final Path INSTRUMENTED_BUILD_DIR = ADMIN_UI_DIR.resolve("target/dist-coverage");
    private static final Path COVERAGE_OUTPUT_DIR = ADMIN_UI_DIR.resolve("target/coverage-data");
    private static final Path FAILURE_OUTPUT_DIR = Path.of("target", "ui-test-failures").toAbsolutePath();

    // Multi-arch (unlike selenium/standalone-chrome), so it runs natively on Apple Silicon too.
    private static final DockerImageName BROWSER_IMAGE = DockerImageName
            .parse("selenium/standalone-chromium:4.43.0-20260404")
            .asCompatibleSubstituteFor("selenium/standalone-chrome");

    protected static final BrowserWebDriverContainer BROWSER = new BrowserWebDriverContainer(BROWSER_IMAGE)
            .withAccessToHost(true)
            .withEnv("SE_SCREEN_WIDTH", "1600")
            .withEnv("SE_SCREEN_HEIGHT", "1000");

    static {
        if (!Files.isRegularFile(INSTRUMENTED_BUILD_DIR.resolve("index.html"))) {
            throw new IllegalStateException("No instrumented admin UI build at " + INSTRUMENTED_BUILD_DIR
                    + " -- run `npm run build:coverage` in ntrloc-admin-ui, or a Maven build without -DskipUiTests");
        }
        BROWSER.start();
    }

    /** A real local account: what a person would type into the login form, plus its user id. */
    protected record LocalUser(UUID id, String email, String password) {
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SecurityRepository securityRepository;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private LedgerRegisterCoordinator coordinator;

    protected WebDriver driver;

    protected UiTestData testData;

    @BeforeEach
    void openBrowser() {
        testData = new UiTestData(schemaManager, coordinator, securityRepository);
        Testcontainers.exposeHostPorts(port);
        // A typical desktop window; Chrome's default in the container is narrow enough to squeeze
        // table columns down to nothing.
        ChromeOptions options = new ChromeOptions().addArguments("--window-size=1600,1000");
        options.setCapability("goog:loggingPrefs", Map.of(LogType.BROWSER, "ALL"));
        driver = new RemoteWebDriver(BROWSER.getSeleniumAddress(), options);
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

    /** Creates a real admin with local credentials, the same way LocalAccountSeeder does. */
    protected LocalUser createLocalAdmin() {
        String email = "ui-admin-" + UUID.randomUUID() + "@example.com";
        String password = UUID.randomUUID().toString();
        var user = securityRepository.createUser("UI Test Admin", email, true);
        securityRepository.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(password));
        return new LocalUser(user.id(), email, password);
    }

    /**
     * Requests an admin UI page, signs in on the login page the server redirects to, and waits to
     * land back on the requested page.
     */
    protected void openAdminUiAs(LocalUser user, String path) {
        String url = serverUrl(ADMIN_UI_PATH + path);
        driver.get(url);
        await().until(ExpectedConditions.visibilityOfElementLocated(By.name("username"))).sendKeys(user.email());
        driver.findElement(By.name("password")).sendKeys(user.password());
        driver.findElement(By.name("password")).submit();
        // Spring Security returns to the saved request with "?continue" appended.
        await().until(ExpectedConditions.urlMatches("^" + Pattern.quote(url) + "(\\?.*)?$"));
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

    /**
     * Saves what the browser showed when a test failed. Runs before @AfterEach closes the browser,
     * which a TestWatcher wouldn't.
     */
    static class FailureCapture implements TestExecutionExceptionHandler {

        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            WebDriver driver = ((AbstractUiIntegrationTest) context.getRequiredTestInstance()).driver;
            if (driver != null) {
                try {
                    capture(driver, FAILURE_OUTPUT_DIR.resolve(context.getRequiredTestClass().getSimpleName() + "-"
                            + context.getRequiredTestMethod().getName()));
                } catch (Exception e) {
                    failure.addSuppressed(e);
                }
            }
            throw failure;
        }

        private static void capture(WebDriver driver, Path dir) throws IOException {
            Files.createDirectories(dir);
            Files.write(dir.resolve("screenshot.png"), ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES));
            Files.writeString(dir.resolve("page.html"), driver.getPageSource());
            Files.writeString(dir.resolve("browser.txt"), "URL: " + driver.getCurrentUrl()
                    + "\nWindow: " + ((JavascriptExecutor) driver).executeScript("return innerWidth + 'x' + innerHeight") + "\n");
            Files.writeString(dir.resolve("console.log"), driver.manage().logs().get(LogType.BROWSER).getAll().stream()
                    .map(entry -> entry.getLevel() + " " + entry.getMessage())
                    .collect(Collectors.joining("\n")));
        }
    }
}
