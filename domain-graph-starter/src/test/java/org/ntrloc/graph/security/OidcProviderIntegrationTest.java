package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// OIDC providers managed through the admin API rather than YAML: changes apply to the next sign-in,
// the client secret is encrypted and never returned, and only admins can touch them. Each test
// starts from Keycloak registered as the "corporate-oidc" provider (AbstractOidcIntegrationTest).
class OidcProviderIntegrationTest extends AbstractOidcIntegrationTest {

    private static final String API = "/api/admin/security/oidc";
    private static final String PROVIDER = API + "/" + KeycloakContainers.REGISTRATION_ID;

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

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private JdbcClient jdbcClient;

    private String admin;

    @BeforeEach
    void signInAsAdmin() {
        for (String user : List.of("admin.user", "existing.user")) {
            String email = user + "@example.org";
            securityRepo.findUserByEmail(email)
                    .orElseGet(() -> securityRepo.createUser(user, email, user.equals("admin.user")));
        }
        admin = logIn("admin.user");
    }

    @Test
    void anEnabledProvider_isOfferedOnTheLoginPage() {
        assertThat(loginPage()).contains("Sign In with Corporate SSO").contains("/oauth2/authorization/corporate-oidc");
    }

    @Test
    void disablingAProvider_stopsItsSignIns_andEnablingItAgainRestoresThem_withoutARestart() {
        put(provider(false), null).expectStatus().isOk();

        assertThat(loginPage()).doesNotContain("Sign In with Corporate SSO");
        assertSignInEndsAtTheLoginError();

        put(provider(true), null).expectStatus().isOk();
        logIn("existing.user");
    }

    // Proves the stored secret is the one actually sent to Keycloak.
    @Test
    void aWrongClientSecret_failsTheSignIn_andAKeptSecretStillWorks() {
        put(provider(true), "wrong-secret").expectStatus().isOk();
        assertLoginFails("existing.user");

        put(provider(true), KeycloakContainers.CLIENT_SECRET).expectStatus().isOk();
        put(provider(true), null).expectStatus().isOk();
        logIn("existing.user");
    }

    @Test
    void theClientSecret_isEncryptedAtRest_andNeverReturned() {
        String stored = jdbcClient.sql("SELECT client_secret_encrypted FROM security_oidc_provider")
                .query(String.class).single();
        String body = webTestClient.get().uri(API).cookie("SESSION", admin).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(stored).startsWith("v1:").doesNotContain(KeycloakContainers.CLIENT_SECRET);
        assertThat(body).contains("\"registrationId\":\"corporate-oidc\"")
                .doesNotContain(KeycloakContainers.CLIENT_SECRET).doesNotContain(stored);
    }

    @Test
    void aProvider_showsTheUrisToRegisterAtTheProvider() {
        String body = webTestClient.get().uri(API).cookie("SESSION", admin).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body)
                .containsPattern("\"redirectUri\":\"http://localhost:\\d+/login/oauth2/code/corporate-oidc\"")
                .containsPattern("\"postLogoutRedirectUri\":\"http://localhost:\\d+/login\"");
    }

    @Test
    void invalidProviders_areRejectedWithTheirReason() {
        Map<String, Object> badId = provider(true);
        badId.put("registrationId", "Not Valid");
        Map<String, Object> noEmailScope = provider(true);
        noEmailScope.put("registrationId", "other");
        noEmailScope.put("scopes", "openid profile");

        assertRejected(post(badId, "secret"), "registration ID");
        assertRejected(post(noEmailScope, "secret"), "email");
        assertRejected(post(provider(true), "secret"), "already exists");
        assertRejected(post(Map.of("registrationId", "nosecret", "displayName", "No Secret", "issuerUri",
                "http://localhost", "clientId", "x", "scopes", "openid email", "enabled", true), null), "client secret");
    }

    @Test
    void aDeletedProvider_isGoneFromTheLoginPage() {
        webTestClient.delete().uri(PROVIDER).cookie("SESSION", admin).exchange().expectStatus().isNoContent();

        assertThat(loginPage()).doesNotContain("Sign In with Corporate SSO");
        assertSignInEndsAtTheLoginError();
        webTestClient.delete().uri(PROVIDER).cookie("SESSION", admin).exchange().expectStatus().isNotFound();
    }

    @Test
    void testingAnIssuer_readsItsDiscoveryDocument_withoutSaving() {
        String issuer = (String) provider(true).get("issuerUri");

        assertThat(test(issuer)).containsEntry("ok", true);
        assertThat(test("http://127.0.0.1:1/realms/nowhere")).containsEntry("ok", false);
    }

    @Test
    void aNonAdmin_canNeitherReadNorChangeProviders() {
        String nonAdmin = logIn("existing.user");

        webTestClient.get().uri(API).cookie("SESSION", nonAdmin).exchange().expectStatus().isForbidden();
        webTestClient.put().uri(PROVIDER).cookie("SESSION", nonAdmin).bodyValue(provider(false)).exchange()
                .expectStatus().isForbidden();
        webTestClient.delete().uri(PROVIDER).cookie("SESSION", nonAdmin).exchange().expectStatus().isForbidden();
        assertThat(loginPage()).contains("Sign In with Corporate SSO");
    }

    private Map<String, Object> provider(boolean enabled) {
        OidcProviderService.OidcProvider registered = oidcProviders.list().stream()
                .filter(p -> p.provider().registrationId().equals(KeycloakContainers.REGISTRATION_ID))
                .findFirst().orElseThrow().provider();
        Map<String, Object> body = new HashMap<>();
        body.put("registrationId", registered.registrationId());
        body.put("displayName", registered.displayName());
        body.put("issuerUri", registered.issuerUri());
        body.put("clientId", registered.clientId());
        body.put("scopes", registered.scopes());
        body.put("enabled", enabled);
        return body;
    }

    // Starting a sign-in with a provider that's gone lands on the login page's error, not at Keycloak.
    private void assertSignInEndsAtTheLoginError() {
        webTestClient.get().uri("/oauth2/authorization/corporate-oidc").exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value("Location", location -> assertThat(location).endsWith("/login?error"));
    }

    private String loginPage() {
        return webTestClient.get().uri("/login").exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec put(Map<String, Object> body, String clientSecret) {
        Map<String, Object> withSecret = new HashMap<>(body);
        withSecret.put("clientSecret", clientSecret);
        return webTestClient.put().uri(PROVIDER).cookie("SESSION", admin).bodyValue(withSecret).exchange();
    }

    private WebTestClient.ResponseSpec post(Map<String, Object> body, String clientSecret) {
        Map<String, Object> withSecret = new HashMap<>(body);
        withSecret.put("clientSecret", clientSecret);
        return webTestClient.post().uri(API).cookie("SESSION", admin).bodyValue(withSecret).exchange();
    }

    private static void assertRejected(WebTestClient.ResponseSpec response, String reason) {
        response.expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").value(message -> assertThat((String) message).contains(reason));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> test(String issuerUri) {
        return webTestClient.post().uri(API + "/test").cookie("SESSION", admin).bodyValue(Map.of("issuerUri", issuerUri))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }
}
