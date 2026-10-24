package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Submits the real /login form: a local account signs in with its email, in any case. Success
// redirects to the app root, failure back to /login?error.
class LocalLoginIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "s3cret!";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    private String createLocalUser() {
        String email = "Login.User-" + UUID.randomUUID() + "@Example.com";
        var user = securityRepo.createUser("Login User", email, false);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD), "USER");
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
}
