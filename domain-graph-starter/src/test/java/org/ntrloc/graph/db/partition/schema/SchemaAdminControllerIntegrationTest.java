package org.ntrloc.graph.db.partition.schema;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.schema.definition.PropertyCardinality;
import org.ntrloc.graph.db.partition.schema.definition.PropertyType;
import org.ntrloc.graph.db.partition.schema.definition.PropertyUsage;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemDefinitionMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateItemPropertyDefinitionMutation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers SchemaAdminController's schema/controlled-list/mutation endpoints. Unlike the other admin
// controllers (Access/User/Group/Task), this one never calls PrincipalResolver -- no admin check
// gates any of these endpoints -- so tests here don't set an X-Ntrloc-User header. Uses a fresh,
// UUID-suffixed item type + property per test (via SchemaManager directly) rather than
// CoordinatorTestDomainInitializer's shared fixture, since the controlled-list tests mutate a
// specific property's controlled-list state and shouldn't risk perturbing another test class's
// assumptions about that fixture.
class SchemaAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SchemaManager schemaManager;

    private UUID createProperty() {
        String itemName = "SchemaAdminTest-" + UUID.randomUUID();
        String propName = "prop-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), null, false, null)));
        UUID itemId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemName)).findFirst().orElseThrow().id();
        schemaManager.applyMutations(List.of(new CreateItemPropertyDefinitionMutation(
                itemId, propName, "d", PropertyType.STRING, PropertyCardinality.SINGLE, PropertyUsage.OPTIONAL)));
        return schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.id().equals(itemId)).findFirst().orElseThrow()
                .properties().stream().filter(p -> p.name().equals(propName)).findFirst().orElseThrow().id();
    }

    @Test
    void getAdminSchema_returnsTheCurrentSchema() {
        String itemName = "SchemaAdminTest-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), null, false, null)));

        webTestClient.get().uri("/api/admin/schema")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.items[?(@.name == '" + itemName + "')]").exists();
    }

    @Test
    void getControlledList_forAPropertyWithNoList_returnsNotFound() {
        UUID propertyId = createProperty();

        webTestClient.get().uri("/api/admin/schema/properties/{id}/controlled-list", propertyId)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void createOrReplaceControlledList_forAPropertyWithNoExistingList_createsOne() {
        UUID propertyId = createProperty();

        webTestClient.put().uri("/api/admin/schema/properties/{id}/controlled-list", propertyId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("name", "list-" + UUID.randomUUID(), "values", List.of(
                        java.util.Map.of("value", "A", "label", "Alpha"),
                        java.util.Map.of("value", "B", "label", "Beta"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.values.length()").isEqualTo(2)
                .jsonPath("$.values[0].value").isEqualTo("A");

        webTestClient.get().uri("/api/admin/schema/properties/{id}/controlled-list", propertyId)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.values.length()").isEqualTo(2);
    }

    @Test
    void createOrReplaceControlledList_forAPropertyWithAnExistingList_replacesTheValues() {
        UUID propertyId = createProperty();
        webTestClient.put().uri("/api/admin/schema/properties/{id}/controlled-list", propertyId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("name", "list-" + UUID.randomUUID(), "values", List.of(
                        java.util.Map.of("value", "Old", "label", "Old"))))
                .exchange()
                .expectStatus().isOk();

        webTestClient.put().uri("/api/admin/schema/properties/{id}/controlled-list", propertyId)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("name", "ignored-on-replace", "values", List.of(
                        java.util.Map.of("value", "New", "label", "New"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.values.length()").isEqualTo(1)
                .jsonPath("$.values[0].value").isEqualTo("New");
    }

    // Item-type inheritance: a rejected supertype mutation must surface as a friendly 400 with
    // the message in the body (via ApiExceptionHandler), not a bare 500 -- exercises that wiring
    // end to end through the real HTTP endpoint, not just SchemaManager directly.
    @Test
    void applyMutations_settingASupertypeThatWouldCreateACycle_returnsBadRequestWithMessage() {
        String itemName = "SchemaAdminTest-" + UUID.randomUUID();
        schemaManager.applyMutations(List.of(new CreateItemDefinitionMutation(itemName, "d", List.of(), null, false, null)));
        UUID itemId = schemaManager.getAdminSchema().items().stream()
                .filter(i -> i.name().equals(itemName)).findFirst().orElseThrow().id();

        String rawJson = """
                [{"type":"UPDATE_ITEM","id":"%s","name":"%s","description":"d","supertypeId":"%s","abstractType":false}]
                """.formatted(itemId, itemName, itemId);

        var body = webTestClient.post().uri("/api/admin/schema/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawJson)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(java.util.Map.class)
                .returnResult().getResponseBody();

        assertThat((String) body.get("message")).contains("cycle");
    }

    @Test
    void applyMutations_appliesARawJsonMutationList() {
        String itemName = "SchemaAdminTest-" + UUID.randomUUID();
        String rawJson = """
                [{"type":"CREATE_ITEM","name":"%s","description":"d","properties":[],"supertypeId":null,"abstractType":false}]
                """.formatted(itemName);

        webTestClient.post().uri("/api/admin/schema/mutations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rawJson)
                .exchange()
                .expectStatus().isNoContent();

        assertThat(schemaManager.getAdminSchema().items()).extracting(i -> i.name()).contains(itemName);
    }
}
