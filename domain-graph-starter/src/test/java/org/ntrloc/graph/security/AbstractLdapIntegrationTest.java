package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.security.LdapConfigurationService.LdapSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.containers.wait.strategy.WaitAllStrategy;
import org.testcontainers.utility.MountableFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for LDAP login tests: a real OpenLDAP server seeded from ldap/test-directory.ldif, with
 * LDAP login configured (through LdapConfigurationService, as the admin UI does) alongside local
 * login. Subclasses add their own properties, making each a separate Spring context from
 * AbstractIntegrationTest's, so each subclass also brings its own Postgres container
 * (see PersonalAccessTokenAuthenticationIntegrationTest for why two contexts must never share
 * one database). The directory itself is read-only to these tests, so one server is shared.
 *
 * Singleton containers started in static initializers, not @Testcontainers/@Container -- see
 * AbstractIntegrationTest for why.
 *
 * @DirtiesContext (inherited by every subclass) closes each subclass's context once it finishes,
 * so these extra contexts don't all stay alive, and in memory, for the rest of the test run.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext
public abstract class AbstractLdapIntegrationTest {

    protected static final String LDAP_PASSWORD = "ldap-secret";
    protected static final String BIND_DN = "cn=admin,dc=example,dc=org";
    protected static final String BIND_PASSWORD = "admin-secret";
    private static final int LDAP_PORT = 1389;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> LDAP = new GenericContainer<>("bitnamilegacy/openldap:2.6.10")
            .withEnv(Map.of(
                    "LDAP_ROOT", "dc=example,dc=org",
                    "LDAP_ADMIN_USERNAME", "admin",
                    "LDAP_ADMIN_PASSWORD", BIND_PASSWORD,
                    "LDAP_CUSTOM_LDIF_DIR", "/ldifs"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("ldap/test-directory.ldif"), "/ldifs/test-directory.ldif")
            .withExposedPorts(LDAP_PORT)
            .waitingFor(new WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*\\*\\* Starting slapd \\*\\*.*", 1))
                    .withStrategy(Wait.forListeningPort()));

    static {
        LDAP.start();
    }

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected LdapConfigurationService ldapConfiguration;

    @BeforeEach
    void configureLdap() {
        ldapConfiguration.save(directorySettings(), BIND_PASSWORD, null);
    }

    /** The test directory, as an admin would enter it. */
    protected static LdapSettings directorySettings() {
        return new LdapSettings(true, "ldap://" + LDAP.getHost() + ":" + LDAP.getMappedPort(LDAP_PORT),
                "dc=example,dc=org", BIND_DN, "ou=people", "(uid={0})", "mail", "displayName");
    }

    /** Submits the real /login form; the redirect's target tells success from failure. */
    protected EntityExchangeResult<Void> submitLogin(String username, String password) {
        return webTestClient.post().uri("/login")
                .body(BodyInserters.fromFormData("username", username).with("password", password))
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectBody(Void.class)
                .returnResult();
    }

    /** Logs in and returns the session cookie, asserting the login succeeded. */
    protected ResponseCookie logIn(String username, String password) {
        EntityExchangeResult<Void> result = submitLogin(username, password);
        assertThat(result.getResponseHeaders().getFirst(HttpHeaders.LOCATION)).doesNotContain("error");
        ResponseCookie session = result.getResponseCookies().getFirst("JSESSIONID");
        assertThat(session).isNotNull();
        return session;
    }

    protected void assertLoginFails(String username, String password) {
        assertThat(submitLogin(username, password).getResponseHeaders().getFirst(HttpHeaders.LOCATION))
                .endsWith("/login?error");
    }

    /** The signed-in user, as /api/account/me reports them. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> me(ResponseCookie session) {
        return webTestClient.get().uri("/api/account/me")
                .cookie(session.getName(), session.getValue())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    protected WebTestClient.ResponseSpec getAdminPage(ResponseCookie session) {
        return webTestClient.get().uri("/admin/index.html")
                .cookie(session.getName(), session.getValue())
                .exchange();
    }
}
