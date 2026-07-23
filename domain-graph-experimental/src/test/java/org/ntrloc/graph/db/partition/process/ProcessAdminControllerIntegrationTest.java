package org.ntrloc.graph.db.partition.process;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.process.ProcessAdminController.ProcessDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Tests share one running context/database across the whole test run (AbstractIntegrationTest's
// "singleton container" pattern), so assertions here tolerate other definitions/versions already
// existing by the time a given test method runs -- never assume exact list contents or order.
class ProcessAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void listsDeployedDefinitionsIncludingHelloWorld() {
        List<ProcessDefinitionView> definitions = fetchDefinitions();

        assertThat(definitions)
                .anyMatch(d -> d.key().equals("helloWorld") && d.name().equals("Hello World") && d.version() == 1);
    }

    @Test
    void fetchesRawXmlForADefinition() {
        ProcessDefinitionView helloWorld = fetchDefinitions().stream()
                .filter(d -> d.key().equals("helloWorld"))
                .findFirst()
                .orElseThrow();

        webTestClient.get().uri(uriBuilder -> uriBuilder.path("/api/admin/process/definitions/xml").queryParam("id", helloWorld.id()).build())
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(xml -> {
                    assertThat(xml).contains("helloWorld");
                    assertThat(xml).contains("<?xml");
                });
    }

    // Uses its own dedicated process key (never "helloWorld"): this shared test database also
    // backs ProcessEngineIntegrationTest, which asserts exact version/deployment counts for the
    // helloWorld key specifically. Deploying under that key here would silently break those
    // assertions in whichever test happened to run second.
    private static final String TEST_KEY = "adminEditorTestProcess";

    @Test
    void deployingModifiedXmlCreatesANewVersion() {
        deploy(TEST_KEY, "Admin Editor Test", "v1");
        int versionsBefore = (int) fetchDefinitions().stream().filter(d -> d.key().equals(TEST_KEY)).count();

        ProcessDefinitionView deployed = deploy(TEST_KEY, "Admin Editor Test, Edited", "v2");

        assertThat(deployed.key()).isEqualTo(TEST_KEY);
        assertThat(deployed.name()).isEqualTo("Admin Editor Test, Edited");

        List<ProcessDefinitionView> versions = fetchDefinitions().stream()
                .filter(d -> d.key().equals(TEST_KEY))
                .toList();
        assertThat(versions).hasSize(versionsBefore + 1);
        assertThat(versions).anyMatch(d -> d.id().equals(deployed.id()));
    }

    @Test
    void deployingInvalidXmlReturnsBadRequestNotAServerError() {
        webTestClient.post().uri("/api/admin/process/definitions/{key}/versions", TEST_KEY)
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue("not valid bpmn xml at all")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").exists();
    }

    private ProcessDefinitionView deploy(String key, String name, String flowSuffix) {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="org.ntrloc.workflow">
                  <process id="%s" name="%s" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="flow-%s" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(key, name, flowSuffix);

        return webTestClient.post().uri("/api/admin/process/definitions/{key}/versions", key)
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(xml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProcessDefinitionView.class)
                .returnResult().getResponseBody();
    }

    private List<ProcessDefinitionView> fetchDefinitions() {
        ProcessDefinitionView[] body = webTestClient.get().uri("/api/admin/process/definitions")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProcessDefinitionView[].class)
                .returnResult().getResponseBody();
        return Arrays.asList(body);
    }
}
