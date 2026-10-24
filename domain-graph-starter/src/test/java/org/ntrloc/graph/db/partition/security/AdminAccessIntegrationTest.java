package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// SecurityConfig's /admin/** rule (the admin UI pages) must follow security_user.is_admin for
// every way of logging in -- a personal access token and a local login session alike -- since
// admin is only ever granted from within ntrloc.
class AdminAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_PAGE = "/admin/index.html";
    private static final String PASSWORD = "s3cret!";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    private SecurityRepository.UserRow createUser(boolean isAdmin) {
        var user = securityRepo.createUser("Admin Access User", "admin-access-" + UUID.randomUUID() + "@example.com", isAdmin);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD));
        return user;
    }

    private WebTestClient.ResponseSpec getAdminPageWithToken(String email) {
        return webTestClient.get().uri(ADMIN_PAGE)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(email))
                .exchange();
    }

    // Signs in through the real /login form and returns the resulting session cookie.
    private ResponseCookie logIn(String email) {
        ResponseCookie session = webTestClient.post().uri("/login")
                .body(BodyInserters.fromFormData("username", email).with("password", PASSWORD))
                .exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class)
                .getResponseCookies().getFirst("JSESSIONID");
        assertThat(session).isNotNull();
        return session;
    }

    private void setAdmin(SecurityRepository.UserRow user, boolean isAdmin) {
        webTestClient.put().uri("/api/admin/users/" + user.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", user.displayName(), "email", user.email(),
                        "role", isAdmin ? "ADMIN" : "USER"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aAdminsToken_canOpenTheAdminUi() {
        var admin = createUser(true);

        getAdminPageWithToken(admin.email()).expectStatus().isOk();
    }

    @Test
    void aRegularUsersToken_isForbiddenFromTheAdminUi() {
        var user = createUser(false);

        getAdminPageWithToken(user.email()).expectStatus().isForbidden();
    }

    @Test
    void aAdminsLoginSession_canOpenTheAdminUi() {
        var admin = createUser(true);
        ResponseCookie session = logIn(admin.email());

        webTestClient.get().uri(ADMIN_PAGE).cookie(session.getName(), session.getValue())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void aRegularUsersLoginSession_isForbiddenFromTheAdminUi() {
        var user = createUser(false);
        ResponseCookie session = logIn(user.email());

        webTestClient.get().uri(ADMIN_PAGE).cookie(session.getName(), session.getValue())
                .exchange()
                .expectStatus().isForbidden();
    }

    // A token re-resolves its user on every request, so a change an admin makes applies to the
    // very next request made with it.
    @Test
    void aTokensAdminAccess_followsPromotionAndDemotionByAnAdmin() {
        var user = createUser(false);
        getAdminPageWithToken(user.email()).expectStatus().isForbidden();

        setAdmin(user, true);
        getAdminPageWithToken(user.email()).expectStatus().isOk();

        setAdmin(user, false);
        getAdminPageWithToken(user.email()).expectStatus().isForbidden();
    }

    @Test
    void anAnonymousRequest_isSentToTheLoginPage() {
        webTestClient.get().uri(ADMIN_PAGE)
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login"));
    }
}
