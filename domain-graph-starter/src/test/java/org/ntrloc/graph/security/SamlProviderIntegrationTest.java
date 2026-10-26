package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// SAML providers managed through the admin API: ntrloc generates each provider's key pair (or
// takes an admin's own), keeps the private key encrypted and never returns it, rotates keys in
// steps an IdP can follow, reports what to register at the IdP, and only admins can touch any of it. Each test starts from the IdP registered (AbstractSamlIntegrationTest).
class SamlProviderIntegrationTest extends AbstractSamlIntegrationTest {

    private static final String API = "/api/admin/security/saml";
    private static final String PROVIDER = API + "/" + REGISTRATION_ID;

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

    private ResponseCookie admin;

    @BeforeEach
    void signInAsLocalAdmin() {
        admin = logInLocally(true);
    }

    @Test
    void aProviderCreatedThroughTheApi_signsPeopleIn() {
        webTestClient.delete().uri(PROVIDER).cookie(admin.getName(), admin.getValue()).exchange()
                .expectStatus().isNoContent();

        post(request(true)).expectStatus().isOk();
        // A new provider has a new key pair, so the IdP needs ntrloc's new metadata, as an admin
        // would import it; until then it refuses ntrloc's requests, signed with an unknown key, with
        // an error page instead of its login form.
        assertThat(new Browser().idpAsksForPassword()).isFalse();
        registerAtIdp(false);
        assertThat(new Browser().idpAsksForPassword()).isTrue();

        securityRepo.findUserByEmail("existing.user@example.org")
                .orElseGet(() -> securityRepo.createUser("Existing User", "existing.user@example.org", false));
        assertThat(me(logIn("existing.user"))).containsEntry("email", "existing.user@example.org");
    }

    @Test
    void ntrlocsSigningKey_isEncryptedAtRest_andNeverReturned() {
        String storedKey = jdbcClient.sql("SELECT private_key_encrypted FROM security_saml_provider_key")
                .query(String.class).single();
        String body = list();

        assertThat(storedKey).startsWith("v1:").doesNotContain("PRIVATE KEY");
        assertThat(body).doesNotContain("PRIVATE KEY").doesNotContain(storedKey);
    }

    @Test
    void aProvider_showsWhatToRegisterAtTheIdp_andWhenItsCertificateExpires() {
        Map<String, Object> provider = webTestClient.get().uri(API).cookie(admin.getName(), admin.getValue()).exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class).returnResult().getResponseBody().getFirst();

        assertThat(provider)
                .containsEntry("entityId", appUrl("/saml2/metadata/" + REGISTRATION_ID))
                .containsEntry("acsUrl", appUrl("/login/saml2/sso/" + REGISTRATION_ID))
                .containsEntry("sloUrl", appUrl("/logout/saml2/slo"));
        @SuppressWarnings("unchecked")
        Map<String, Object> key = ((List<Map<String, Object>>) provider.get("keys")).getFirst();
        assertThat(key).containsEntry("role", "ACTIVE");
        Instant expires = Instant.parse((String) key.get("certificateExpires"));
        assertThat(expires).isBetween(Instant.now().plus(1090, ChronoUnit.DAYS), Instant.now().plus(1100, ChronoUnit.DAYS));
    }

    @Test
    void invalidProviders_areRejectedWithTheirReason() {
        Map<String, Object> badId = request(true);
        badId.put("registrationId", "Not Valid");
        Map<String, Object> badUri = request(true);
        badUri.put("registrationId", "other");
        badUri.put("metadataUri", "ftp://idp.example.com/metadata");

        assertRejected(post(badId), "registration ID");
        assertRejected(post(badUri), "metadata URI");
        assertRejected(post(request(true)), "already exists");
    }

    @Test
    void testingMetadata_readsTheIdpsMetadata_withoutSaving() {
        assertThat(test(KeycloakContainers.samlMetadataUri(KEYCLOAK))).containsEntry("ok", true);
        assertThat(test("http://127.0.0.1:1/metadata")).containsEntry("ok", false);
    }

    @Test
    void aNonAdmin_canNeitherReadNorChangeProviders() {
        ResponseCookie nonAdmin = logInLocally(false);

        webTestClient.get().uri(API).cookie(nonAdmin.getName(), nonAdmin.getValue()).exchange().expectStatus().isForbidden();
        webTestClient.put().uri(PROVIDER).cookie(nonAdmin.getName(), nonAdmin.getValue()).bodyValue(request(false))
                .exchange().expectStatus().isForbidden();
        webTestClient.delete().uri(PROVIDER).cookie(nonAdmin.getName(), nonAdmin.getValue()).exchange()
                .expectStatus().isForbidden();
        assertThat(list()).contains("\"enabled\":true");
    }

    // --- Key rotation: add a next key, give the IdP ntrloc's new metadata, activate, retire ---

    // An IdP that refreshes ntrloc's metadata can learn the next key early; until it's activated,
    // ntrloc still signs with the active key, so an IdP that hasn't refreshed carries on as before.
    @Test
    void aNextKey_isPublished_whileNtrlocKeepsSigningWithTheActiveOne() {
        addNextKey(Map.of()).expectStatus().isOk();

        assertThat(spMetadata()).containsPattern("(?s)use=\"signing\".*use=\"signing\"");
        assertThat(new Browser().idpAsksForPassword()).isTrue();
    }

    // The IdP only trusts the certificates it was given, so it refuses ntrloc's newly signed requests
    // until it has the new metadata -- which is what it must be given before (or right after) activating.
    @Test
    void activatingTheNextKey_switchesSigning_andKeepsTheOldKeyForDecryption() {
        addNextKey(Map.of()).expectStatus().isOk();
        keyAction(webTestClient.post().uri(PROVIDER + "/keys/activate")).expectStatus().isOk();

        assertThat(roles()).containsExactlyInAnyOrder("ACTIVE", "RETIRING");
        assertThat(samlProviders.findByRegistrationId(REGISTRATION_ID).getDecryptionX509Credentials()).hasSize(2);
        assertThat(new Browser().idpAsksForPassword()).isFalse();
        registerAtIdp(false);
        assertThat(new Browser().idpAsksForPassword()).isTrue();

        keyAction(webTestClient.delete().uri(PROVIDER + "/keys/retiring")).expectStatus().isOk();
        assertThat(roles()).containsExactly("ACTIVE");
        assertThat(samlProviders.findByRegistrationId(REGISTRATION_ID).getDecryptionX509Credentials()).hasSize(1);
    }

    @Test
    void anUploadedKeyPair_signsOnceActivated() {
        SamlCertificates.SigningPair own = SamlCertificates.generate("Corporate PKI");

        addNextKey(Map.of("privateKey", own.privateKeyPem(), "certificate", own.certificatePem())).expectStatus().isOk();
        keyAction(webTestClient.post().uri(PROVIDER + "/keys/activate")).expectStatus().isOk();
        registerAtIdp(false);

        assertThat(spMetadata()).contains(own.certificatePem().lines()
                .filter(line -> !line.startsWith("-----")).findFirst().orElseThrow());
        assertThat(list()).contains("CN=Corporate PKI");
        account("existing.user@example.org");
        assertThat(me(logIn("existing.user"))).containsEntry("email", "existing.user@example.org");
    }

    @Test
    void keyChanges_thatWouldBreakSigning_areRejected() {
        SamlCertificates.SigningPair one = SamlCertificates.generate("One");
        SamlCertificates.SigningPair two = SamlCertificates.generate("Two");

        assertRejected(addNextKey(Map.of("privateKey", one.privateKeyPem(), "certificate", two.certificatePem())),
                "doesn't belong");
        assertRejected(addNextKey(Map.of("privateKey", "not a key", "certificate", one.certificatePem())), "PEM");
        assertRejected(keyAction(webTestClient.post().uri(PROVIDER + "/keys/activate")), "no next key");
        assertRejected(keyAction(webTestClient.delete().uri(PROVIDER + "/keys/retiring")), "no retiring key");
        addNextKey(Map.of()).expectStatus().isOk();
        assertRejected(addNextKey(Map.of()), "already a next key");

        keyAction(webTestClient.delete().uri(PROVIDER + "/keys/next")).expectStatus().isOk();
        assertThat(roles()).containsExactly("ACTIVE");
    }

    private WebTestClient.ResponseSpec addNextKey(Map<String, String> body) {
        return webTestClient.post().uri(PROVIDER + "/keys").cookie(admin.getName(), admin.getValue()).bodyValue(body).exchange();
    }

    private WebTestClient.ResponseSpec keyAction(WebTestClient.RequestHeadersSpec<?> request) {
        return request.cookie(admin.getName(), admin.getValue()).exchange();
    }

    private List<String> roles() {
        return jdbcClient.sql("SELECT role FROM security_saml_provider_key").query(String.class).list();
    }

    private String spMetadata() {
        return webTestClient.get().uri("/saml2/metadata/" + REGISTRATION_ID).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private void account(String email) {
        securityRepo.findUserByEmail(email).orElseGet(() -> securityRepo.createUser("Existing User", email, false));
    }

    private ResponseCookie logInLocally(boolean isAdmin) {
        String email = "local-" + UUID.randomUUID() + "@example.com";
        var user = securityRepo.createUser("Local User", email, isAdmin);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode("local-secret"));
        return webTestClient.post().uri("/login")
                .body(BodyInserters.fromFormData("username", email).with("password", "local-secret"))
                .exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class).getResponseCookies().getFirst("SESSION");
    }

    private static Map<String, Object> request(boolean enabled) {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationId", REGISTRATION_ID);
        body.put("displayName", "Corporate SSO");
        body.put("metadataUri", KeycloakContainers.samlMetadataUri(KEYCLOAK));
        body.put("emailAttribute", "email");
        body.put("displayNameAttribute", "displayName");
        body.put("enabled", enabled);
        return body;
    }

    private String list() {
        return webTestClient.get().uri(API).cookie(admin.getName(), admin.getValue()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec post(Map<String, Object> body) {
        return webTestClient.post().uri(API).cookie(admin.getName(), admin.getValue()).bodyValue(body).exchange();
    }

    private static void assertRejected(WebTestClient.ResponseSpec response, String reason) {
        response.expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").value(message -> assertThat((String) message).contains(reason));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> test(String metadataUri) {
        return webTestClient.post().uri(API + "/test").cookie(admin.getName(), admin.getValue())
                .bodyValue(Map.of("metadataUri", metadataUri))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }
}
