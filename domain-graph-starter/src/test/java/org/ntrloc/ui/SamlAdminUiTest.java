package org.ntrloc.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.security.KeycloakContainers;
import org.ntrloc.graph.security.SamlProviderService;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.openqa.selenium.By;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

/**
 * Signing in to the React admin UI through a SAML IdP in a real browser, and out again: ntrloc's
 * login page, its SAML button, the IdP's login form, the auto-posted (encrypted) assertion back to
 * ntrloc -- and single logout through the IdP. Admin comes from the ntrloc account, never the IdP.
 *
 * As in OidcAdminUiTest, the browser (in a container) and ntrloc (in this JVM) both reach the IdP
 * and ntrloc on host.testcontainers.internal, so ntrloc's entity ID -- built from the host it's
 * reached at -- is the same for both, and the IdP is registered from ntrloc's metadata as fetched
 * there. Its own Spring context (SSO only) and Postgres, closed after this class (@DirtiesContext).
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@ExtendWith(UiBrowser.FailureCapture.class)
class SamlAdminUiTest implements UiBrowser.Holder {

    // Seeded by AuthorizationTestDataInitializer (graph.security.seed-test-data in the test config).
    private static final String SEEDED_ITEM_TYPE = "AclTestUnmarkedDoc";
    private static final String REGISTRATION_ID = "corporate-saml";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final GenericContainer<?> KEYCLOAK = KeycloakContainers.shared();

    static {
        POSTGRES.start();
        Testcontainers.exposeHostPorts(KEYCLOAK.getMappedPort(KeycloakContainers.PORT));
    }

    private static String idpUrl() {
        return "http://" + TestcontainersHostResolverProvider.HOST + ":" + KEYCLOAK.getMappedPort(KeycloakContainers.PORT);
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("graph.auth.local.enabled", () -> "false");
        registry.add("graph.security.auto-provision.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private SamlProviderService samlProviders;

    private UiBrowser browser;

    @Override
    public UiBrowser uiBrowser() {
        return browser;
    }

    @BeforeEach
    void openBrowser() {
        if (samlProviders.list().isEmpty()) {
            samlProviders.create(new SamlProvider(REGISTRATION_ID, "Corporate SSO",
                    idpUrl() + "/realms/ntrloc/protocol/saml/descriptor", "email", "displayName", true), null);
        }
        browser = new UiBrowser(port, List.of(idpUrl()));
        KeycloakContainers.registerSamlClient(KEYCLOAK, browser.serverUrl("/saml2/metadata/" + REGISTRATION_ID), true);
    }

    @AfterEach
    void closeBrowser(TestInfo testInfo) {
        browser.close(getClass().getSimpleName() + "-" + testInfo.getTestMethod().orElseThrow().getName());
    }

    @Test
    void anNtrlocAdmin_signingInThroughSaml_getsTheAdminUi() {
        securityRepo.createUser("Admin User", "admin.user@example.org", true);

        browser.driver().get(browser.serverUrl(UiBrowser.ADMIN_UI_PATH + "/schema"));
        signInAtIdp("admin.user");
        browser.await().until(ExpectedConditions.urlContains(UiBrowser.ADMIN_UI_PATH + "/schema"));

        SchemaPage schema = SchemaPage.of(browser.driver());
        browser.await().until(d -> schema.itemTypeNames().contains(SEEDED_ITEM_TYPE));
    }

    // Single logout: logging out of ntrloc signs the browser out of the IdP too, so signing in again
    // shows the IdP's login form rather than signing straight back in.
    @Test
    void loggingOut_signsOutOfTheIdpToo() {
        securityRepo.createUser("Plain User", "plain.user@example.org", false);
        browser.driver().get(browser.serverUrl("/login"));
        signInAtIdp("plain.user");
        browser.await().until(ExpectedConditions.not(ExpectedConditions.urlContains(idpUrl())));

        browser.driver().get(browser.serverUrl("/logout"));
        browser.await().until(ExpectedConditions.urlToBe(browser.serverUrl("/login")));
        clickSamlButton();

        browser.await().until(ExpectedConditions.visibilityOfElementLocated(By.id("username")));
    }

    // From ntrloc's login page: the SAML button, then the IdP's own login form.
    private void signInAtIdp(String username) {
        clickSamlButton();
        browser.await().until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(username);
        browser.driver().findElement(By.id("password")).sendKeys(KeycloakContainers.USER_PASSWORD);
        browser.driver().findElement(By.id("kc-login")).click();
    }

    private void clickSamlButton() {
        browser.await().until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("a[href='/saml2/authenticate/" + REGISTRATION_ID + "'] button"))).click();
    }
}
