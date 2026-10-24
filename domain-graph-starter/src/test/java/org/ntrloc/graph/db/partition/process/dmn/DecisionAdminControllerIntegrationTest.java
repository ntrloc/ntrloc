package org.ntrloc.graph.db.partition.process.dmn;

import org.springframework.http.HttpHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.coordinator.CoordinatorTestDomainInitializer;
import org.ntrloc.graph.db.partition.process.dmn.DecisionAdminController.DecisionDefinitionView;
import org.ntrloc.graph.db.partition.register.RegisterPartitionManager;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMachineMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.CreateStateMutation;
import org.ntrloc.graph.db.partition.schema.definition.mutation.DefinitionMutation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Mirrors ProcessAdminControllerIntegrationTest exactly, DMN-side (same relationship as the
// controllers themselves -- see DecisionAdminController's own class comment). Reuses
// ProcessTestDomainInitializer's deployed "approvalDecision" decision table for the listing/XML
// fetch tests; deployNewVersion tests use their own dedicated key, never "approvalDecision",
// for the same reason ProcessAdminControllerIntegrationTest's TEST_KEY isn't "helloWorld" -- this
// shared test database also backs other decision-deployment assertions elsewhere in the suite.
class DecisionAdminControllerIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_KEY = "adminEditorTestDecision";

    @Autowired
    private WebTestClient webTestClient;

    // Every endpoint requires an authenticated caller. root is AuthorizationTestDataInitializer's
    // seeded superuser; a request that sets its own Authorization header still uses that one.
    @BeforeEach
    void authenticateAsRoot() {
        webTestClient = webTestClient.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com")).build();
    }

    @Autowired
    private CoordinatorTestDomainInitializer fixture;

    @Autowired
    private SchemaManager schemaManager;

    @Autowired
    private RegisterPartitionManager registerPartitionManager;

    @Test
    void listsDeployedDecisionsIncludingApprovalDecision() {
        List<DecisionDefinitionView> decisions = fetchDecisions();

        assertThat(decisions).anyMatch(d -> d.key().equals("approvalDecision") && d.version() == 1);
    }

    @Test
    void fetchesRawXmlForADecision() {
        DecisionDefinitionView approval = fetchDecisions().stream()
                .filter(d -> d.key().equals("approvalDecision"))
                .findFirst()
                .orElseThrow();

        webTestClient.get().uri(uriBuilder -> uriBuilder.path("/api/admin/dmn/decisions/xml").queryParam("id", approval.id()).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(xml -> {
                    assertThat(xml).contains("approvalDecision");
                    assertThat(xml).contains("<?xml");
                });
    }

    @Test
    void deployingNewXmlCreatesANewVersion() {
        deploy(TEST_KEY, "v1");
        int versionsBefore = (int) fetchDecisions().stream().filter(d -> d.key().equals(TEST_KEY)).count();

        DecisionDefinitionView deployed = deploy(TEST_KEY, "v2");

        assertThat(deployed.key()).isEqualTo(TEST_KEY);

        List<DecisionDefinitionView> versions = fetchDecisions().stream()
                .filter(d -> d.key().equals(TEST_KEY))
                .toList();
        assertThat(versions).hasSize(versionsBefore + 1);
        assertThat(versions).anyMatch(d -> d.id().equals(deployed.id()));
    }

    @Test
    void deployingInvalidXmlReturnsBadRequestNotAServerError() {
        webTestClient.post().uri("/api/admin/dmn/decisions/{key}/versions", "invalid-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue("not valid dmn xml at all")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").exists();
    }

    @Test
    void deletingADecisionRemovesEveryDeployedVersion() {
        String key = "deleteDecisionTest-" + UUID.randomUUID();
        deploy(key, "v1");
        deploy(key, "v2");
        assertThat(fetchDecisions()).anyMatch(d -> d.key().equals(key));

        webTestClient.delete().uri("/api/admin/dmn/decisions/{key}", key)
                .exchange()
                .expectStatus().isNoContent();

        assertThat(fetchDecisions()).noneMatch(d -> d.key().equals(key));
    }

    @Test
    void deletingAnUnknownDecisionReturnsNotFound() {
        webTestClient.delete().uri("/api/admin/dmn/decisions/{key}", "missing-" + UUID.randomUUID())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deletingADecisionStillReferencedByAStatesEntryMarkerDecisionIsBlockedWithA409() {
        String key = "referencedDecisionTest-" + UUID.randomUUID();
        deploy(key, "v1");
        String machineName = "decision-delete-guard-machine-" + UUID.randomUUID();
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateStateMachineMutation(fixture.productTypeId(), machineName, null)));
        UUID smId = registerPartitionManager.resolveStateMachineId(fixture.productTypeId(), machineName);
        schemaManager.applyMutations(List.<DefinitionMutation>of(
                new CreateStateMutation(smId, "Guarded", null, null, null, key)));

        webTestClient.delete().uri("/api/admin/dmn/decisions/{key}", key)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.message").value(m -> assertThat((String) m).contains("Guarded"));

        assertThat(fetchDecisions()).anyMatch(d -> d.key().equals(key));
    }

    private DecisionDefinitionView deploy(String key, String labelSuffix) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions id="definitions_%s" name="%s" namespace="http://flowable.org/dmn"
                             xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                             xmlns:flowable="http://flowable.org/dmn">
                  <decision id="%s" name="%s">
                    <decisionTable id="decisionTable_%s" hitPolicy="UNIQUE">
                      <input id="input1" label="Amount">
                        <inputExpression id="inputExpression1" typeRef="number"><text>amount</text></inputExpression>
                      </input>
                      <output id="output1" label="Approved" name="approved" typeRef="boolean"/>
                      <rule id="rule1">
                        <inputEntry id="inputEntry1"><text>&lt;= 1000</text></inputEntry>
                        <outputEntry id="outputEntry1"><text>true</text></outputEntry>
                      </rule>
                    </decisionTable>
                  </decision>
                </definitions>
                """.formatted(key, key, key, labelSuffix, key);

        return webTestClient.post().uri("/api/admin/dmn/decisions/{key}/versions", key)
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(xml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DecisionDefinitionView.class)
                .returnResult().getResponseBody();
    }

    private List<DecisionDefinitionView> fetchDecisions() {
        DecisionDefinitionView[] body = webTestClient.get().uri("/api/admin/dmn/decisions")
                .exchange()
                .expectStatus().isOk()
                .expectBody(DecisionDefinitionView[].class)
                .returnResult().getResponseBody();
        return Arrays.asList(body);
    }
}
