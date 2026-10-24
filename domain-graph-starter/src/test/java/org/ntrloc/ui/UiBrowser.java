package org.ntrloc.ui;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
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
import org.testcontainers.Testcontainers;
import org.testcontainers.selenium.BrowserWebDriverContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * One test's browser session against the React admin UI (ntrloc-admin-ui), for any Spring context
 * a Selenium test runs in -- AbstractUiIntegrationTest's shared one, or a test with its own (an
 * LDAP-enabled one, e.g.). The browser runs in a container and reaches the server through
 * host.testcontainers.internal. The UI is served from the Istanbul-instrumented build in
 * ntrloc-admin-ui/target/dist-coverage/ (npm run build:coverage), and each test's
 * window.__coverage__ is written to ntrloc-admin-ui/target/coverage-data/ for
 * npm run coverage:report to merge into an LCOV file.
 *
 * The browser container is a JVM-wide singleton, like the Postgres ones. A failing test leaves a
 * screenshot, the page's HTML and the browser console in target/ui-test-failures/ (see
 * FailureCapture).
 */
public final class UiBrowser {

    public static final String ADMIN_UI_PATH = "/admin-react";
    private static final Path ADMIN_UI_DIR = Path.of("..", "ntrloc-admin-ui").toAbsolutePath().normalize();
    private static final Path INSTRUMENTED_BUILD_DIR = ADMIN_UI_DIR.resolve("target/dist-coverage");
    private static final Path COVERAGE_OUTPUT_DIR = ADMIN_UI_DIR.resolve("target/coverage-data");
    private static final Path FAILURE_OUTPUT_DIR = Path.of("target", "ui-test-failures").toAbsolutePath();
    // Multi-arch (unlike selenium/standalone-chrome), so it runs natively on Apple Silicon too.
    private static final DockerImageName BROWSER_IMAGE = DockerImageName
            .parse("selenium/standalone-chromium:4.43.0-20260404")
            .asCompatibleSubstituteFor("selenium/standalone-chrome");

    private static final BrowserWebDriverContainer BROWSER = new BrowserWebDriverContainer(BROWSER_IMAGE)
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

    /** Implemented by test classes that hold a UiBrowser, so FailureCapture can find it. */
    public interface Holder {
        UiBrowser uiBrowser();
    }

    private final int port;
    private final WebDriver driver;

    /** Opens a browser session against the server on this port. */
    public UiBrowser(int port) {
        this.port = port;
        Testcontainers.exposeHostPorts(port);
        // A typical desktop window; Chrome's default in the container is narrow enough to squeeze
        // table columns down to nothing.
        ChromeOptions options = new ChromeOptions().addArguments("--window-size=1600,1000");
        options.setCapability("goog:loggingPrefs", Map.of(LogType.BROWSER, "ALL"));
        driver = new RemoteWebDriver(BROWSER.getSeleniumAddress(), options);
    }

    public WebDriver driver() {
        return driver;
    }

    public String serverUrl(String path) {
        return "http://host.testcontainers.internal:" + port + path;
    }

    public WebDriverWait await() {
        return new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    /**
     * Requests an admin UI page, signs in on the login page the server redirects to, and waits to
     * land back on the requested page.
     */
    public void openAdminUiAs(String username, String password, String path) {
        String url = serverUrl(ADMIN_UI_PATH + path);
        driver.get(url);
        await().until(ExpectedConditions.visibilityOfElementLocated(By.name("username"))).sendKeys(username);
        driver.findElement(By.name("password")).sendKeys(password);
        driver.findElement(By.name("password")).submit();
        // Spring Security returns to the saved request with "?continue" appended.
        await().until(ExpectedConditions.urlMatches("^" + Pattern.quote(url) + "(\\?.*)?$"));
    }

    /**
     * window.__coverage__ is reset by every full page load, so a test that navigates with
     * driver.get() more than once must call this (with a distinct name) before each one.
     */
    public void writeCoverage(String name) {
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

    /** Writes this session's coverage under the given name, then closes the browser. */
    public void close(String coverageName) {
        try {
            writeCoverage(coverageName);
        } finally {
            driver.quit();
        }
    }

    /**
     * Saves what the browser showed when a test failed. Runs before @AfterEach closes the browser,
     * which a TestWatcher wouldn't.
     */
    public static class FailureCapture implements TestExecutionExceptionHandler {

        @Override
        public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
            UiBrowser browser = ((Holder) context.getRequiredTestInstance()).uiBrowser();
            if (browser != null) {
                try {
                    capture(browser.driver, FAILURE_OUTPUT_DIR.resolve(context.getRequiredTestClass().getSimpleName() + "-"
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
