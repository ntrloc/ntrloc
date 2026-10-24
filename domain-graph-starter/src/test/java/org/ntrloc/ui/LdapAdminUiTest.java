package org.ntrloc.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.security.AbstractLdapIntegrationTest;
import org.openqa.selenium.By;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

// Signing in to the React admin UI through the real login page with LDAP credentials. Admin comes
// from the ntrloc account, never the directory. The schema view's item types come from an
// admin-only API, so seeing a seeded one proves the admin UI and its admin data both loaded.
@ExtendWith(UiBrowser.FailureCapture.class)
class LdapAdminUiTest extends AbstractLdapIntegrationTest implements UiBrowser.Holder {

    // Seeded by AuthorizationTestDataInitializer (graph.security.seed-test-data in the test config).
    private static final String SEEDED_ITEM_TYPE = "AclTestUnmarkedDoc";

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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

    @BeforeEach
    void openBrowser() {
        browser = new UiBrowser(port);
    }

    @AfterEach
    void closeBrowser(TestInfo testInfo) {
        browser.close(getClass().getSimpleName() + "-" + testInfo.getTestMethod().orElseThrow().getName());
    }

    @Test
    void anNtrlocAdmin_signingInThroughLdap_getsTheAdminUi() {
        securityRepo.createUser("Admin User", "admin.user@example.org", true);

        browser.openAdminUiAs("admin.user", LDAP_PASSWORD, "/schema");

        SchemaPage schema = SchemaPage.of(browser.driver());
        browser.await().until(d -> schema.itemTypeNames().contains(SEEDED_ITEM_TYPE));
    }

    @Test
    void aNonAdmin_signingInThroughLdap_isForbiddenFromTheAdminUi() {
        securityRepo.createUser("Plain User", "plain.user@example.org", false);

        browser.openAdminUiAs("plain.user", LDAP_PASSWORD, "/schema");

        assertThat(browser.driver().getPageSource()).contains("403");
        assertThat(browser.driver().findElements(By.cssSelector("schema-view"))).isEmpty();
    }
}
