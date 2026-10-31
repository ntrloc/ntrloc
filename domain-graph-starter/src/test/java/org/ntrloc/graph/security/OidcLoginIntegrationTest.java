package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// OpenID Connect login with auto-provisioning off: the provider's verified email must already
// belong to an ntrloc account, or the login is rejected. Each test uses its own Keycloak user (see
// oidc/ntrloc-realm.json), so none depends on another's account.
class OidcLoginIntegrationTest extends AbstractOidcIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "OidcLoginIntegrationTest");
        registry.add("graph.security.auto-provision.enabled", () -> "false");
    }

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void theLoginPage_offersEachConfiguredProvider() {
        String page = webTestClient.get().uri("/login").accept(MediaType.TEXT_HTML)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(page).contains("href=\"/oauth2/authorization/corporate-oidc\"").contains("Sign In with Corporate SSO")
                .doesNotContain("Local Account");
    }

    // With local login off, a local password is refused everywhere -- including HTTP Basic, which
    // would otherwise reach Spring Security's global fallback AuthenticationManager.
    @Test
    void aLocalPassword_isRefused_whenLocalLoginIsOff() {
        var user = securityRepo.createUser("Local Only", "local-only@example.org", false);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode("local-secret"));

        webTestClient.get().uri("/api/account/me")
                .headers(headers -> headers.setBasicAuth("local-only@example.org", "local-secret"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void aUserWithAnNtrlocAccount_logsInThroughOidcAsThatAccount() {
        var account = securityRepo.createUser("Existing ntrloc User", "existing.user@example.org", false);

        assertThat(me(logIn("existing.user")))
                .containsEntry("email", account.email())
                .containsEntry("displayName", "Existing ntrloc User");
    }

    @Test
    void theProvidersEmail_matchesTheNtrlocAccountIgnoringCase() {
        securityRepo.createUser("Mixed Case", "mixed.case@example.org", false);

        assertThat(me(logIn("mixed.case"))).containsEntry("email", "mixed.case@example.org");
    }

    @Test
    void aUserWithoutAnNtrlocAccount_isRejected_andNoAccountIsCreated() {
        assertLoginFails("unprovisioned.user");

        assertThat(securityRepo.findUserByEmail("unprovisioned.user@example.org")).isEmpty();
    }

    // An unverified email is never trusted, even when it matches an existing account -- otherwise
    // anyone able to set an arbitrary email at the provider could sign in as that account.
    @Test
    void anUnverifiedEmail_isRejected_evenWhenItMatchesAnAccount() {
        securityRepo.createUser("Unverified User", "unverified.user@example.org", false);

        assertLoginFails("unverified.user");
    }

    // Admin comes from the ntrloc account, never from the provider.
    @Test
    void anNtrlocAdmin_canOpenTheAdminUi_afterAnOidcLogin() {
        securityRepo.createUser("Admin User", "admin.user@example.org", true);

        getAdminPage(logIn("admin.user")).expectStatus().isOk();
    }

    @Test
    void aNonAdmin_isForbiddenFromTheAdminUi_afterAnOidcLogin() {
        securityRepo.createUser("Plain User", "plain.user@example.org", false);

        getAdminPage(logIn("plain.user")).expectStatus().isForbidden();
    }

    // The control for the test below: Keycloak's own session signs a browser straight back in.
    @Test
    void withoutLoggingOut_keycloakSignsTheBrowserStraightBackIn() {
        ensureAccount("resync.user@example.org");
        Browser browser = new Browser();
        browser.logIn("resync.user", OIDC_PASSWORD);

        assertThat(browser.keycloakAsksForPassword()).isFalse();
    }

    // RP-initiated logout: logging out of ntrloc ends the Keycloak session too, so signing in again
    // asks for a password, and Keycloak returns the browser to ntrloc's login page.
    @Test
    void loggingOut_endsTheKeycloakSessionToo() {
        ensureAccount("resync.user@example.org");
        Browser browser = new Browser();
        String session = browser.logIn("resync.user", OIDC_PASSWORD).sessionId().orElseThrow();

        assertThat(browser.logOut()).endsWith("/login");
        webTestClient.get().uri("/api/account/me").cookie("SESSION", session).exchange()
                .expectStatus().is3xxRedirection();
        assertThat(browser.keycloakAsksForPassword()).isTrue();
    }

    private void ensureAccount(String email) {
        securityRepo.findUserByEmail(email).orElseGet(() -> securityRepo.createUser("Resync User", email, false));
    }
}
