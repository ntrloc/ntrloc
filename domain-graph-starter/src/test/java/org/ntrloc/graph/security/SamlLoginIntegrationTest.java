package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

// SAML 2.0 login with auto-provisioning off: the email Keycloak asserts must already belong to an
// ntrloc account, or the sign-in is rejected. Each test uses its own Keycloak user (see
// oidc/ntrloc-realm.json), so none depends on another's account.
class SamlLoginIntegrationTest extends AbstractSamlIntegrationTest {

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

    @Test
    void theLoginPage_offersTheSamlProvider() {
        assertThat(loginPage()).contains("href=\"/saml2/authenticate/corporate-saml\"").contains("Sign In with Corporate SSO");
    }

    @Test
    void aUserWithAnNtrlocAccount_signsInThroughSaml_asThatAccount() {
        account("Existing ntrloc User", "existing.user@example.org", false);

        assertThat(me(logIn("existing.user")))
                .containsEntry("email", "existing.user@example.org")
                .containsEntry("displayName", "Existing ntrloc User");
    }

    @Test
    void theEmail_canBeTheNameId() {
        saveProvider(idp("", true));
        account("Mixed Case", "mixed.case@example.org", false);

        assertThat(me(logIn("mixed.case"))).containsEntry("email", "mixed.case@example.org");
    }

    @Test
    void aUserWithoutAnNtrlocAccount_isRejected_andNoAccountIsCreated() {
        assertLoginFails("unprovisioned.user");

        assertThat(securityRepo.findUserByEmail("unprovisioned.user@example.org")).isEmpty();
    }

    @Test
    void anEmailAttributeTheIdpDoesntSend_rejectsTheSignIn() {
        saveProvider(idp("mail", true));
        account("Plain User", "plain.user@example.org", false);

        assertLoginFails("plain.user");
    }

    // The IdP imports this to trust ntrloc: its entity ID, where to send responses, and the
    // certificate its requests are signed with. Served to anyone, since the IdP fetches it unsigned in.
    @Test
    void ntrlocsMetadata_isPublic_andDescribesThisServer() {
        String metadata = webTestClient.get().uri("/saml2/metadata/" + REGISTRATION_ID).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(metadata)
                .contains("entityID=\"" + appUrl("/saml2/metadata/" + REGISTRATION_ID) + "\"")
                .contains("Location=\"" + appUrl("/login/saml2/sso/" + REGISTRATION_ID) + "\"")
                .contains("X509Certificate");
    }

    @Test
    void aDisabledProvider_isNotOffered_andCantStartASignIn() {
        saveProvider(idp("email", false));

        assertThat(loginPage()).doesNotContain("Sign In with Corporate SSO");
        webTestClient.get().uri("/saml2/authenticate/" + REGISTRATION_ID).exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value("Location", location -> assertThat(location).doesNotContain("/realms/ntrloc/"));
    }

    // The control for the test below: the IdP's own session signs a browser straight back in.
    @Test
    void withoutLoggingOut_theIdpSignsTheBrowserStraightBackIn() {
        account("Admin User", "admin.user@example.org", true);
        Browser browser = new Browser();
        browser.logIn("admin.user", SAML_PASSWORD);

        assertThat(browser.idpAsksForPassword()).isFalse();
    }

    // Single logout: logging out of ntrloc sends the IdP a signed LogoutRequest, which ends the
    // IdP's session too, and the IdP's LogoutResponse returns the browser to the login page.
    @Test
    void loggingOut_endsTheIdpSessionToo() {
        account("Resync User", "resync.user@example.org", false);
        Browser browser = new Browser();
        String session = browser.logIn("resync.user", SAML_PASSWORD).sessionId().orElseThrow();

        assertThat(browser.logOut()).endsWith("/login");
        assertSessionEnded(session);
        assertThat(browser.idpAsksForPassword()).isTrue();
    }

    // Signing out at the IdP -- from any of its applications -- reaches ntrloc too: the IdP sends
    // ntrloc its own LogoutRequest through the browser, and ntrloc ends its session.
    @Test
    void signingOutAtTheIdp_endsTheNtrlocSession() {
        account("New User", "new.user@example.org", false);
        Browser browser = new Browser();
        String session = browser.logIn("new.user", SAML_PASSWORD).sessionId().orElseThrow();

        HttpResponse<String> confirm = browser.open(KeycloakContainers.issuerUri(KEYCLOAK) + "/protocol/openid-connect/logout");
        browser.submit(confirm, "confirmLogout", "Logout");

        assertSessionEnded(session);
    }

    @Test
    void withAssertionEncryptionOff_theIdpSendsAPlainAssertion() {
        account("Existing ntrloc User", "existing.user@example.org", false);
        Browser browser = new Browser();

        assertThat(browser.logIn("existing.user", SAML_PASSWORD).succeeded()).isTrue();
        assertThat(browser.lastSamlResponse()).contains("Assertion").doesNotContain("EncryptedAssertion");
    }

    // ntrloc's metadata offers its certificate for encryption; an IdP that encrypts to it is
    // understood the same way.
    @Test
    void anIdpThatEncryptsAssertions_stillSignsPeopleIn() {
        registerAtIdp(true);
        account("Plain User", "plain.user@example.org", false);
        Browser browser = new Browser();

        assertThat(browser.logIn("plain.user", SAML_PASSWORD).succeeded()).isTrue();
        assertThat(browser.lastSamlResponse()).contains("EncryptedAssertion");
    }

    // Several tests here may use the same IdP user; its ntrloc account is created once.
    private void account(String displayName, String email, boolean isAdmin) {
        securityRepo.findUserByEmail(email).orElseGet(() -> securityRepo.createUser(displayName, email, isAdmin));
    }

    private void assertSessionEnded(String session) {
        webTestClient.get().uri("/api/account/me").cookie("JSESSIONID", session).exchange()
                .expectStatus().is3xxRedirection();
    }

    private String loginPage() {
        return webTestClient.get().uri("/login").accept(MediaType.TEXT_HTML).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }
}
