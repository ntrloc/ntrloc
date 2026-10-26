package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

// SecurityConfig's admin-only rule for both admin UIs (/admin/**, /admin-react/**) must follow
// security_user.is_admin for every way of logging in -- a personal access token and a local login
// session alike -- since admin is only ever granted from within ntrloc.
class AdminAccessIntegrationTest extends AbstractIntegrationTest {

    // The vanilla-JS admin UI and the React admin UI; both are admin-only.
    private static final String ADMIN_UI_PAGE = "/admin/index.html";
    private static final String REACT_ADMIN_UI_PAGE = "/admin-react/index.html";
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

    private WebTestClient.ResponseSpec getWithToken(String page, String email) {
        return webTestClient.get().uri(page)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(email))
                .exchange();
    }

    private WebTestClient.ResponseSpec getWithSession(String page, ResponseCookie session) {
        return webTestClient.get().uri(page).cookie(session.getName(), session.getValue()).exchange();
    }

    // Signs in through the real /login form and returns the resulting session cookie.
    private ResponseCookie logIn(String email) {
        ResponseCookie session = webTestClient.post().uri("/login")
                .body(BodyInserters.fromFormData("username", email).with("password", PASSWORD))
                .exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class)
                .getResponseCookies().getFirst("SESSION");
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

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_UI_PAGE, REACT_ADMIN_UI_PAGE})
    void anAdminsToken_canOpenTheAdminUi(String page) {
        var admin = createUser(true);

        getWithToken(page, admin.email()).expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_UI_PAGE, REACT_ADMIN_UI_PAGE})
    void aRegularUsersToken_isForbiddenFromTheAdminUi(String page) {
        var user = createUser(false);

        getWithToken(page, user.email()).expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_UI_PAGE, REACT_ADMIN_UI_PAGE})
    void anAdminsLoginSession_canOpenTheAdminUi(String page) {
        var admin = createUser(true);

        getWithSession(page, logIn(admin.email())).expectStatus().isOk();
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_UI_PAGE, REACT_ADMIN_UI_PAGE})
    void aRegularUsersLoginSession_isForbiddenFromTheAdminUi(String page) {
        var user = createUser(false);

        getWithSession(page, logIn(user.email())).expectStatus().isForbidden();
    }

    // A token re-resolves its user on every request, so a change an admin makes applies to the
    // very next request made with it.
    @Test
    void aTokensAdminAccess_followsPromotionAndDemotionByAnAdmin() {
        var user = createUser(false);
        getWithToken(ADMIN_UI_PAGE, user.email()).expectStatus().isForbidden();

        setAdmin(user, true);
        getWithToken(ADMIN_UI_PAGE, user.email()).expectStatus().isOk();

        setAdmin(user, false);
        getWithToken(ADMIN_UI_PAGE, user.email()).expectStatus().isForbidden();
    }

    @ParameterizedTest
    @ValueSource(strings = {ADMIN_UI_PAGE, REACT_ADMIN_UI_PAGE})
    void anAnonymousRequest_isSentToTheLoginPage(String page) {
        webTestClient.get().uri(page)
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login"));
    }
}
