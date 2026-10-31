package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

/**
 * Exercises the bearer-token (PAT) authentication path. Tokens are minted
 * directly through PersonalAccessTokenService (bypassing the /pat endpoint's own auth
 * requirement) so each test only exercises the one thing it's meant to prove: does a bearer
 * token presented via the Authorization header authenticate as its owning user.
 *
 * Its @TestPropertySource makes a Spring context of its own, so it has a database of its own (see
 * TestDatabases): two contexts on one database would each change data the other relies on.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "graph.auth.oauth.enabled=false",
        "graph.auth.ldap.enabled=false"
})
@DirtiesContext
class PersonalAccessTokenAuthenticationIntegrationTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "PersonalAccessTokenAuthenticationIntegrationTest");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void bearerTokenAuthenticatesAsTheOwningUser() {
        var user = securityRepo.createUser("PAT Test User", "pat-user-" + UUID.randomUUID() + "@example.com", false);
        var principal = new NtrlocPrincipal(user.id(), user.displayName(), user.email(), Set.of(), false);
        var issued = tokenService.issue(principal, "test-token", null);

        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownTokenIsRejected() {
        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + "not-a-real-token")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void revokedTokenNoLongerAuthenticates() {
        var user = securityRepo.createUser("PAT Revoke User", "pat-revoke-user-" + UUID.randomUUID() + "@example.com", false);
        var principal = new NtrlocPrincipal(user.id(), user.displayName(), user.email(), Set.of(), false);
        var issued = tokenService.issue(principal, "revoke-me", null);

        tokenService.revoke(principal, issued.id());

        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void expiredTokenIsRejected() {
        var user = securityRepo.createUser("PAT Expired User", "pat-expired-user-" + UUID.randomUUID() + "@example.com", false);
        var principal = new NtrlocPrincipal(user.id(), user.displayName(), user.email(), Set.of(), false);
        var issued = tokenService.issue(principal, "already-expired", -1);

        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void noCredentialsAtAllIsRejected() {
        webTestClient.get().uri("/api/schema")
                .exchange()
                .expectStatus().is3xxRedirection();
    }
}
