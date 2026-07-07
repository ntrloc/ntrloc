package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;

/**
 * Exercises the bearer-token (PAT) authentication path with real Spring Security enabled —
 * unlike AuthorizationEndpointsIntegrationTest, which deliberately runs with security disabled
 * to test the marker/grant model via the stand-in header in isolation. Tokens are minted
 * directly through PersonalAccessTokenService (bypassing the /pat endpoint's own auth
 * requirement) so each test only exercises the one thing it's meant to prove: does a bearer
 * token presented via the Authorization header authenticate as its owning user.
 */
@TestPropertySource(properties = {
        "ntrloc.security.enabled=true",
        "ntrloc.auth.oauth.enabled=false",
        "ntrloc.auth.ldap.enabled=false"
})
class PersonalAccessTokenAuthenticationIntegrationTest extends AbstractIntegrationTest {

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

        webTestClient.get().uri("/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void unknownTokenIsRejected() {
        webTestClient.get().uri("/schema")
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

        webTestClient.get().uri("/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void expiredTokenIsRejected() {
        var user = securityRepo.createUser("pat-expired-user", "PAT Expired User", false);
        var principal = new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), Set.of(), false);
        var issued = tokenService.issue(principal, "already-expired", -1);

        webTestClient.get().uri("/schema")
                .header("Authorization", "Bearer " + issued.rawToken())
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void noCredentialsAtAllIsRejected() {
        webTestClient.get().uri("/schema")
                .exchange()
                .expectStatus().is3xxRedirection();
    }
}
