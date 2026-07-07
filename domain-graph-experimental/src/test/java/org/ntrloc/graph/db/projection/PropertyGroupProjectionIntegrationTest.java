package org.ntrloc.graph.db.projection;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Exercises the "groupProperties" projection toggle against the fixture seeded by
 * PropertyGroupTestDataInitializer: "PropertyGroupTestDoc" has an ungrouped "title" property
 * and an "isbn13" property assigned to the "IDs" group.
 */
class PropertyGroupProjectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void defaultRenderingIsFlat() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"PropertyGroupTestDoc"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].properties.title").isEqualTo("Some Title")
                .jsonPath("$.items[0].properties.isbn13").isEqualTo("9781234567890")
                .jsonPath("$.items[0].properties.IDs").doesNotExist();
    }

    @Test
    void groupPropertiesNestsGroupedPropertiesUnderGroupName() {
        webTestClient.post().uri("/entity/projection")
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"itemTypeName":"PropertyGroupTestDoc","groupProperties":true}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[0].properties.title").isEqualTo("Some Title")
                .jsonPath("$.items[0].properties.isbn13").doesNotExist()
                .jsonPath("$.items[0].properties.IDs.isbn13").isEqualTo("9781234567890");
    }
}
