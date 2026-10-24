package org.ntrloc.ui;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.LedgerRegisterCoordinator;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Base for Selenium tests of the React admin UI (ntrloc-admin-ui), each driving its own
 * {@link UiBrowser} session.
 *
 * Every test signs in through the real login page as a real local user it creates
 * (createLocalAdmin), exactly as a person would -- nothing about authentication is stubbed.
 *
 * Shares AbstractIntegrationTest's application context (TestApplication scans UI hosting, and the
 * /admin-react mount is in the test application.yml): a second context against the same Postgres
 * container would re-run startup seeding and break every integration test after it.
 */
@ExtendWith(UiBrowser.FailureCapture.class)
public abstract class AbstractUiIntegrationTest extends AbstractIntegrationTest implements UiBrowser.Holder {

    protected static final String ADMIN_UI_PATH = UiBrowser.ADMIN_UI_PATH;

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

    private UiBrowser browser;

    protected WebDriver driver;

    protected UiTestData testData;

    @Override
    public UiBrowser uiBrowser() {
        return browser;
    }

    @BeforeEach
    void openBrowser() {
        testData = new UiTestData(schemaManager, coordinator, securityRepository);
        browser = new UiBrowser(port);
        driver = browser.driver();
    }

    @AfterEach
    void closeBrowser(TestInfo testInfo) {
        browser.close(testInfo.getTestClass().orElseThrow().getSimpleName() + "-"
                + testInfo.getTestMethod().orElseThrow().getName());
    }

    /** Creates a real admin with local credentials, the same way LocalAccountSeeder does. */
    protected LocalUser createLocalAdmin() {
        String email = "ui-admin-" + UUID.randomUUID() + "@example.com";
        String password = UUID.randomUUID().toString();
        var user = securityRepository.createUser("UI Test Admin", email, true);
        securityRepository.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(password));
        return new LocalUser(user.id(), email, password);
    }

    /** See {@link UiBrowser#openAdminUiAs}. */
    protected void openAdminUiAs(LocalUser user, String path) {
        browser.openAdminUiAs(user.email(), user.password(), path);
    }

    protected String serverUrl(String path) {
        return browser.serverUrl(path);
    }

    protected WebDriverWait await() {
        return browser.await();
    }

    /** See {@link UiBrowser#writeCoverage}. */
    protected void writeCoverage(String name) {
        browser.writeCoverage(name);
    }
}
