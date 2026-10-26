package org.ntrloc.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.security.KeycloakContainers;
import org.ntrloc.graph.security.OidcProviderService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in to the React admin UI through Keycloak in a real browser: ntrloc's login page, its
 * "Sign In with Keycloak" button, Keycloak's own login form, and back. Admin comes from the ntrloc
 * account, never the provider.
 *
 * The browser (in a container) and ntrloc (in this JVM) reach Keycloak at the same URL, on
 * host.testcontainers.internal -- the browser through Testcontainers' host port forwarding, ntrloc
 * through TestcontainersHostResolverProvider -- so Keycloak issues tokens under one issuer, which
 * ntrloc validates in full. Keycloak marks its session cookies Secure, so the browser treats that
 * plain-HTTP origin as secure, as Chrome does localhost.
 *
 * Keycloak is registered as a sign-in provider through OidcProviderService, as the admin UI does.
 * Its own Spring context (SSO only) and Postgres, closed after this class (@DirtiesContext).
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
@ExtendWith(UiBrowser.FailureCapture.class)
class OidcAdminUiTest implements UiBrowser.Holder {

    // Seeded by AuthorizationTestDataInitializer (graph.security.seed-test-data in the test config).
    private static final String SEEDED_ITEM_TYPE = "AclTestUnmarkedDoc";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final GenericContainer<?> KEYCLOAK = KeycloakContainers.shared();

    static {
        POSTGRES.start();
        Testcontainers.exposeHostPorts(keycloakPort());
    }

    private static int keycloakPort() {
        return KEYCLOAK.getMappedPort(KeycloakContainers.PORT);
    }

    private static String keycloakUrl() {
        return "http://" + TestcontainersHostResolverProvider.HOST + ":" + keycloakPort();
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

    private UiBrowser browser;

    @Override
    public UiBrowser uiBrowser() {
        return browser;
    }

    @Autowired
    private OidcProviderService oidcProviders;

    @BeforeEach
    void openBrowser() {
        KeycloakContainers.register(oidcProviders, keycloakUrl() + "/realms/ntrloc");
        browser = new UiBrowser(port, List.of(keycloakUrl()));
    }

    @AfterEach
    void closeBrowser(TestInfo testInfo) {
        browser.close(getClass().getSimpleName() + "-" + testInfo.getTestMethod().orElseThrow().getName());
    }

    // Opens an admin UI page, is sent to ntrloc's login page, picks Keycloak there, signs in on
    // Keycloak's own form, and waits to be returned to the page it asked for.
    private void openAdminUiThroughKeycloakAs(String username, String path) {
        browser.driver().get(browser.serverUrl(UiBrowser.ADMIN_UI_PATH + path));
        browser.await().until(ExpectedConditions.elementToBeClickable(
                By.cssSelector("a[href='/oauth2/authorization/keycloak'] button"))).click();
        browser.await().until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(username);
        browser.driver().findElement(By.id("password")).sendKeys(KeycloakContainers.USER_PASSWORD);
        browser.driver().findElement(By.id("kc-login")).click();
        browser.await().until(ExpectedConditions.urlContains(UiBrowser.ADMIN_UI_PATH + path));
    }

    @Test
    void anNtrlocAdmin_signingInThroughKeycloak_getsTheAdminUi() {
        securityRepo.createUser("Admin User", "admin.user@example.org", true);

        openAdminUiThroughKeycloakAs("admin.user", "/schema");

        SchemaPage schema = SchemaPage.of(browser.driver());
        browser.await().until(d -> schema.itemTypeNames().contains(SEEDED_ITEM_TYPE));
    }

    @Test
    void aNonAdmin_signingInThroughKeycloak_isForbiddenFromTheAdminUi() {
        securityRepo.createUser("Plain User", "plain.user@example.org", false);

        openAdminUiThroughKeycloakAs("plain.user", "/schema");

        assertThat(browser.driver().getPageSource()).contains("403");
        assertThat(browser.driver().findElements(By.cssSelector("schema-view"))).isEmpty();
    }
}
