package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Set;

/**
 * Exercises the bearer-token (PAT) authentication path with real Spring Security enabled —
 * unlike AuthorizationEndpointsIntegrationTest, which deliberately runs with security disabled
 * to test the marker/grant model via the stand-in header in isolation. Tokens are minted
 * directly through PersonalAccessTokenService (bypassing the /pat endpoint's own auth
 * requirement) so each test only exercises the one thing it's meant to prove: does a bearer
 * token presented via the Authorization header authenticate as its owning user.
 *
 * Deliberately does NOT extend AbstractIntegrationTest / share its static Postgres container:
 * this class's @TestPropertySource forces a second, distinct Spring context, and every
 * schema/register/security/authorization initializer in this codebase drops and recreates its
 * tables on every context boot — a second context pointed at the same physical database as the
 * default-config context would wipe that context's tables out from under it. A dedicated
 * container keeps this context's from-scratch rebuild from touching anyone else's database.
 *
 * Also deliberately does NOT use @Testcontainers/@Container/@ServiceConnection — see
 * AbstractIntegrationTest for why (the "singleton container" pattern: a plain static
 * initializer ties the container's lifecycle to the JVM instead of to JUnit's per-class
 * extension lifecycle).
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "ntrloc.security.enabled=true",
        "ntrloc.auth.oauth.enabled=false",
        "ntrloc.auth.ldap.enabled=false"
})
class PersonalAccessTokenAuthenticationIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PersonalAccessTokenService tokenService;

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void bearerTokenAuthenticatesAsTheOwningUser() {
        var user = securityRepo.createUser("pat-user", "PAT Test User", false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), Set.of(), false);
        var issued = tokenService.issue(principal, "test-token", null);

        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownTokenIsRejected() {
        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer ntrloc_pat_" + "not-a-real-token")
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void revokedTokenNoLongerAuthenticates() {
        var user = securityRepo.createUser("pat-revoke-user", "PAT Revoke User", false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), Set.of(), false);
        var issued = tokenService.issue(principal, "revoke-me", null);

        tokenService.revoke(principal, issued.id());

        webTestClient.get().uri("/api/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void expiredTokenIsRejected() {
        var user = securityRepo.createUser("pat-expired-user", "PAT Expired User", false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), Set.of(), false);
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
