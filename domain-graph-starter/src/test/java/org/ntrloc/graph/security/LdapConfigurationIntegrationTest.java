package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.security.LdapConfigurationService.LdapSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// LDAP configured through the admin API rather than YAML: changes apply to the next login, the bind
// password is encrypted and never returned, only admins can touch it, and a broken configuration
// can't stop a local admin from signing in to fix it.
class LdapConfigurationIntegrationTest extends AbstractLdapIntegrationTest {

    private static final String API = "/api/admin/security/ldap";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "LdapConfigurationIntegrationTest");
        registry.add("graph.security.auto-provision.enabled", () -> "false");
    }

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private JdbcClient jdbcClient;

    private ResponseCookie admin;

    @BeforeEach
    void signInAsLocalAdmin() {
        admin = logIn(localAccount(true), "local-secret");
        securityRepo.findUserByEmail("existing.user@example.org")
                .orElseGet(() -> securityRepo.createUser("Existing User", "existing.user@example.org", false));
    }

    @Test
    void aSavedChange_appliesToTheNextLogin_withoutARestart() {
        logIn("existing.user", LDAP_PASSWORD);

        put(request(disabled(directorySettings()), null)).expectStatus().isOk();
        assertLoginFails("existing.user", LDAP_PASSWORD);

        put(request(directorySettings(), null)).expectStatus().isOk();
        logIn("existing.user", LDAP_PASSWORD);
    }

    @Test
    void theBindPassword_isEncryptedAtRest_andNeverReturned() {
        put(request(directorySettings(), BIND_PASSWORD)).expectStatus().isOk();

        String stored = jdbcClient.sql("SELECT bind_password_encrypted FROM security_ldap_config")
                .query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(BIND_PASSWORD);
        String body = get(admin).expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"bindPasswordSet\":true").doesNotContain(BIND_PASSWORD).doesNotContain(stored);
    }

    @Test
    void savingWithoutAPassword_keepsTheStoredOne() {
        put(request(directorySettings(), null)).expectStatus().isOk();

        logIn("existing.user", LDAP_PASSWORD);
    }

    @Test
    void anInvalidConfiguration_isRejectedWithItsReason() {
        LdapSettings noPlaceholder = new LdapSettings(true, directorySettings().url(), "dc=example,dc=org", BIND_DN,
                "ou=people", "(uid=someone)", "mail", "displayName");

        put(request(noPlaceholder, null)).expectStatus().isBadRequest()
                .expectBody().jsonPath("$.message").value(message -> assertThat((String) message).contains("{0}"));
    }

    @Test
    void testingTheConnection_reportsSuccessOrWhatWentWrong_withoutSaving() {
        assertThat(test(request(directorySettings(), null))).containsEntry("ok", true);
        assertThat(test(request(directorySettings(), "wrong-password")))
                .containsEntry("ok", false).containsEntry("message", "The directory rejected the bind DN or password.");
        assertThat((String) test(request(unreachable(), null)).get("message")).startsWith("Couldn't reach");

        logIn("existing.user", LDAP_PASSWORD);
    }

    // The break-glass path: LDAP is tried first, and an unusable directory mustn't stop local login.
    @Test
    void anUnreachableDirectory_stillLetsALocalAdminSignIn() {
        put(request(unreachable(), null)).expectStatus().isOk();

        assertLoginFails("existing.user", LDAP_PASSWORD);
        logIn(localAccount(true), "local-secret");
    }

    @Test
    void aNonAdmin_canNeitherReadNorChangeTheConfiguration() {
        ResponseCookie nonAdmin = logIn(localAccount(false), "local-secret");

        get(nonAdmin).expectStatus().isForbidden();
        webTestClient.put().uri(API).cookie(nonAdmin.getName(), nonAdmin.getValue())
                .bodyValue(request(disabled(directorySettings()), null)).exchange().expectStatus().isForbidden();
        webTestClient.post().uri(API + "/test").cookie(nonAdmin.getName(), nonAdmin.getValue())
                .bodyValue(request(directorySettings(), null)).exchange().expectStatus().isForbidden();
        logIn("existing.user", LDAP_PASSWORD);
    }

    private String localAccount(boolean isAdmin) {
        String email = "local-" + UUID.randomUUID() + "@example.com";
        var user = securityRepo.createUser("Local User", email, isAdmin);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode("local-secret"));
        return email;
    }

    private static LdapSettings disabled(LdapSettings s) {
        return new LdapSettings(false, s.url(), s.baseDn(), s.bindDn(), s.userSearchBase(), s.userSearchFilter(),
                s.emailAttribute(), s.displayNameAttribute());
    }

    // Port 1 refuses connections at once, rather than timing out.
    private static LdapSettings unreachable() {
        LdapSettings s = directorySettings();
        return new LdapSettings(true, "ldap://127.0.0.1:1", s.baseDn(), s.bindDn(), s.userSearchBase(),
                s.userSearchFilter(), s.emailAttribute(), s.displayNameAttribute());
    }

    private static Map<String, Object> request(LdapSettings s, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", s.enabled());
        body.put("url", s.url());
        body.put("baseDn", s.baseDn());
        body.put("bindDn", s.bindDn());
        body.put("password", password);
        body.put("userSearchBase", s.userSearchBase());
        body.put("userSearchFilter", s.userSearchFilter());
        body.put("emailAttribute", s.emailAttribute());
        body.put("displayNameAttribute", s.displayNameAttribute());
        return body;
    }

    private WebTestClient.ResponseSpec get(ResponseCookie session) {
        return webTestClient.get().uri(API).cookie(session.getName(), session.getValue()).exchange();
    }

    private WebTestClient.ResponseSpec put(Map<String, Object> body) {
        return webTestClient.put().uri(API).cookie(admin.getName(), admin.getValue()).bodyValue(body).exchange();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> test(Map<String, Object> body) {
        return webTestClient.post().uri(API + "/test").cookie(admin.getName(), admin.getValue()).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    }
}
