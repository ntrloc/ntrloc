package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the item-type direct-grant tracer bullet end-to-end against a real Postgres
 * (Testcontainers) and the fixture seeded by AuthorizationTestDataInitializer:
 *  - alice, bob -> group "viewers" -> item-type:read grant -> AclTestPublicDoc
 *  - carol -> direct user grant -> item-type:read grant -> AclTestConfidentialDoc
 *  - root -> admin -> bypasses type-visibility authorization entirely, including
 *    AclTestUnmarkedDoc, which has no grant at all and is therefore invisible to everyone else
 */
class AuthorizationEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    // The schema holds every fixture of the shared test run, which can outgrow the client's default
    // 256KB response buffer.
    @BeforeEach
    void allowLargeResponses() {
        webTestClient = webTestClient.mutate()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Test
    void schemaViewShowsOnlyGrantedTypes_forUserGroupMember() {
        webTestClient.get().uri("/api/schema")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[*].name").value(names -> {
                    var list = (java.util.List<String>) names;
                    org.assertj.core.api.Assertions.assertThat(list)
                            .contains("AclTestPublicDoc")
                            .doesNotContain("AclTestConfidentialDoc");
                });
    }

    @Test
    void schemaViewShowsOnlyGrantedTypes_forDirectUserGrant() {
        webTestClient.get().uri("/api/schema")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("carol@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[*].name").value(names -> {
                    var list = (java.util.List<String>) names;
                    org.assertj.core.api.Assertions.assertThat(list)
                            .contains("AclTestConfidentialDoc")
                            .doesNotContain("AclTestPublicDoc");
                });
    }

    // The old ?asUser= stand-in is gone: naming a user in the URL authenticates nobody.
    @Test
    void schemaViewIgnoresAsUserQueryParam() {
        webTestClient.get().uri("/api/schema?asUser=bob")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login"));
    }

    @Test
    void schemaViewRedirectsUnauthenticatedRequestToLogin() {
        webTestClient.get().uri("/api/schema")
                .exchange()
                .expectStatus().is3xxRedirection()
                .expectHeader().value(HttpHeaders.LOCATION, location -> assertThat(location).endsWith("/login"));
    }

    @Test
    void schemaViewRejectsUnrecognizedBearerToken() {
        webTestClient.get().uri("/api/schema")
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void projectionAllowsQueryingUserGroupGrantedType() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestPublicDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksInaccessibleTypeAsNotFound() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestConfidentialDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void projectionAllowsDirectUserGrantedType() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("carol@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestConfidentialDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksTypeNotGrantedToDirectUser() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("carol@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestPublicDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void schemaViewShowsAllTypesForAdmin() {
        webTestClient.get().uri("/api/schema")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[*].name").value(names -> {
                    var list = (java.util.List<String>) names;
                    org.assertj.core.api.Assertions.assertThat(list)
                            .contains("AclTestPublicDoc", "AclTestConfidentialDoc", "AclTestUnmarkedDoc");
                });
    }

    @Test
    void projectionAllowsAdminOnUnmarkedType() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestUnmarkedDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksUnmarkedTypeForNonAdmin() {
        webTestClient.post().uri("/api/projection/collection")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestUnmarkedDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }
}
