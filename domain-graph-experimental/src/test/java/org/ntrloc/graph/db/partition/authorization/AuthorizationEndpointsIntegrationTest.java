package org.ntrloc.graph.db.partition.authorization;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Exercises the item-type-marker tracer bullet end-to-end against a real Postgres
 * (Testcontainers) and the fixture seeded by AuthorizationTestDataInitializer:
 *  - alice, bob -> group "viewers" -> item:read on "public-read" marker -> AclTestPublicDoc
 *  - carol -> direct grant -> item:read on "confidential-read" marker -> AclTestConfidentialDoc
 *  - root -> superuser -> bypasses marker authorization entirely, including AclTestUnmarkedDoc,
 *    which has no marker assignment at all and is therefore invisible to everyone else
 */
class AuthorizationEndpointsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void schemaViewShowsOnlyMarkerGrantedTypes_forGroupMember() {
        webTestClient.get().uri("/schema")
                .header("X-Ntrloc-User", "alice")
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
    void schemaViewShowsOnlyMarkerGrantedTypes_forDirectUserGrant() {
        webTestClient.get().uri("/schema")
                .header("X-Ntrloc-User", "carol")
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

    @Test
    void schemaViewSupportsQueryParamFallback() {
        webTestClient.get().uri("/schema?asUser=bob")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[*].name").value(names -> {
                    var list = (java.util.List<String>) names;
                    org.assertj.core.api.Assertions.assertThat(list).contains("AclTestPublicDoc");
                });
    }

    @Test
    void schemaViewRejectsMissingPrincipal() {
        webTestClient.get().uri("/schema")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void schemaViewRejectsUnknownPrincipal() {
        webTestClient.get().uri("/schema")
                .header("X-Ntrloc-User", "nobody")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void projectionAllowsQueryingGroupGrantedType() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestPublicDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksInaccessibleTypeAsNotFound() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestConfidentialDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void projectionAllowsDirectUserGrantedType() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "carol")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestConfidentialDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksTypeNotGrantedToDirectUser() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "carol")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestPublicDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void schemaViewShowsAllTypesForSuperuser() {
        webTestClient.get().uri("/schema")
                .header("X-Ntrloc-User", "root")
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
    void projectionAllowsSuperuserOnUnmarkedType() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestUnmarkedDoc"}
                        """)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void projectionMasksUnmarkedTypeForNonSuperuser() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"AclTestUnmarkedDoc"}
                        """)
                .exchange()
                .expectStatus().isNotFound();
    }
}
