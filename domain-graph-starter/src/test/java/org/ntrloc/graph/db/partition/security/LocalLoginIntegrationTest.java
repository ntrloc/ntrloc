package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Submits the real /login form: a local account signs in with its email, in any case. Success
// redirects to the app root; every kind of failure redirects the same way, to /login?error.
class LocalLoginIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "s3cret!";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private JdbcClient jdbcClient;

    private String createLocalUser() {
        String email = "Login.User-" + UUID.randomUUID() + "@Example.com";
        var user = securityRepo.createUser("Login User", email, false);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD));
        return email;
    }

    private WebTestClient.ResponseSpec submitLogin(String username, String password) {
        return webTestClient.post().uri("/login")
                .body(BodyInserters.fromFormData("username", username).with("password", password))
                .exchange();
    }

    @Test
    void signingInWithTheEmail_succeeds() {
        String email = createLocalUser();

        submitLogin(email, PASSWORD)
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).doesNotContain("error"));
    }

    @Test
    void signingInWithTheEmailInADifferentCase_succeeds() {
        String email = createLocalUser();

        submitLogin(email.toLowerCase(), PASSWORD)
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).doesNotContain("error"));
    }

    @Test
    void signingInWithAWrongPassword_fails() {
        String email = createLocalUser();

        submitLogin(email, "wrong")
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login?error"));
    }

    private void assertLoginFails(String username, String password) {
        submitLogin(username, password)
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login?error"));
    }

    @Test
    void signingInWithAnUnknownEmail_fails() {
        assertLoginFails("nobody-" + UUID.randomUUID() + "@example.com", PASSWORD);
    }

    @Test
    void signingInAsAUserWithoutLocalCredentials_fails() {
        var user = securityRepo.createUser("No Local Login", "no-local-" + UUID.randomUUID() + "@example.com", false);

        assertLoginFails(user.email(), PASSWORD);
    }

    @Test
    void signingInToAnInactiveAccount_failsEvenWithTheRightPassword() {
        String email = createLocalUser();
        jdbcClient.sql("""
                UPDATE security_local_credentials SET active = FALSE
                WHERE user_id = (SELECT id FROM security_user WHERE email = :email)
                """).param("email", email).update();

        assertLoginFails(email, PASSWORD);
    }
}
