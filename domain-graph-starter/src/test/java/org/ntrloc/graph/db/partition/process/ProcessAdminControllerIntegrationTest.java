package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.TaskService;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.process.ProcessAdminController.ProcessDefinitionView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tests share one running context/database across the whole test run (AbstractIntegrationTest's
// "singleton container" pattern), so assertions here tolerate other definitions/versions already
// existing by the time a given test method runs -- never assume exact list contents or order.
class ProcessAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TaskService taskService;

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
    void deployingModifiedXmlReplacesTheVisibleDefinitionForThatKey() {
        deploy(TEST_KEY, "Admin Editor Test", "v1");

        ProcessDefinitionView deployed = deploy(TEST_KEY, "Admin Editor Test, Edited", "v2");

        assertThat(deployed.key()).isEqualTo(TEST_KEY);
        assertThat(deployed.name()).isEqualTo("Admin Editor Test, Edited");

        // getDefinitions() now filters to latestVersion() -- deploying v2 replaces v1 in this
        // list, it doesn't add alongside it. Full history is a separate concern (see
        // versionsEndpoint_returnsFullHistoryOldestToNewest below).
        List<ProcessDefinitionView> visible = fetchDefinitions().stream()
                .filter(d -> d.key().equals(TEST_KEY))
                .toList();
        assertThat(visible).hasSize(1);
        assertThat(visible.get(0).id()).isEqualTo(deployed.id());
    }

    private static final String VERSIONS_ENDPOINT_TEST_KEY = "adminEditorVersionsEndpointTestProcess";

    @Test
    void versionsEndpoint_returnsFullHistoryOldestToNewest() {
        ProcessDefinitionView v1 = deploy(VERSIONS_ENDPOINT_TEST_KEY, "Versions Endpoint Test", "v1");
        ProcessDefinitionView v2 = deploy(VERSIONS_ENDPOINT_TEST_KEY, "Versions Endpoint Test, Edited", "v2");

        ProcessDefinitionView[] versions = webTestClient.get()
                .uri("/api/admin/process/definitions/{key}/versions", VERSIONS_ENDPOINT_TEST_KEY)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProcessDefinitionView[].class)
                .returnResult().getResponseBody();

        assertThat(versions).extracting(ProcessDefinitionView::id).containsExactly(v1.id(), v2.id());
    }

    // --- Start process instance ---

    @Test
    void startProcessInstance_runsTheProcessAndReturnsItsInstanceView() {
        ProcessDefinitionView helloWorld = fetchDefinitions().stream()
                .filter(d -> d.key().equals("helloWorld"))
                .findFirst()
                .orElseThrow();

        // helloWorld has no ioSpecification (missingRequiredVariables short-circuits to
        // List.of()) and pauses at its own "reviewGreeting" user task, so this exercises the
        // success path without also completing the process.
        var instance = webTestClient.post().uri(uriBuilder -> uriBuilder.path("/api/admin/process/definitions/start")
                        .queryParam("id", helloWorld.id()).build())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProcessAdminController.ProcessInstanceView.class)
                .returnResult().getResponseBody();

        assertThat(instance.id()).isNotBlank();
        assertThat(instance.ended()).isFalse();

        // The instance this leaves paused at "reviewGreeting" must be completed before this test
        // ends: an uncompleted candidate-group task here breaks other test classes' own
        // .taskCandidateGroup()/.singleResult() assumptions (confirmed live -- see
        // TaskAdminControllerIntegrationTest.completeTask's own comment for the full story).
        // Filtered by processInstanceId in memory, not on the query itself, for the same reason
        // as that class's startProcessAndGetReviewTask(): TaskDataManagerImpl's whereClause()
        // doesn't support that filter.
        var task = taskService.createTaskQuery().taskCandidateGroup("reviewers").list().stream()
                .filter(t -> t.getProcessInstanceId().equals(instance.id()))
                .findFirst()
                .orElseThrow();
        taskService.complete(task.getId());
    }

    private static final String IO_SPEC_TEST_KEY = "adminEditorTestIoSpecProcess";

    private ProcessDefinitionView deployWithRequiredVariable() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="org.ntrloc.workflow">
                  <process id="%s" name="Io Spec Test" isExecutable="true">
                    <ioSpecification>
                      <dataInput id="input1" name="requiredVar"/>
                      <inputSet>
                        <dataInputRefs>input1</dataInputRefs>
                      </inputSet>
                      <outputSet/>
                    </ioSpecification>
                    <startEvent id="start"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """.formatted(IO_SPEC_TEST_KEY);

        return webTestClient.post().uri("/api/admin/process/definitions/{key}/versions", IO_SPEC_TEST_KEY)
                .contentType(MediaType.APPLICATION_XML)
                .bodyValue(xml)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ProcessDefinitionView.class)
                .returnResult().getResponseBody();
    }

    @Test
    void startProcessInstance_missingARequiredVariable_returnsBadRequestWithAMessage() {
        ProcessDefinitionView definition = deployWithRequiredVariable();

        webTestClient.post().uri(uriBuilder -> uriBuilder.path("/api/admin/process/definitions/start")
                        .queryParam("id", definition.id()).build())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").isEqualTo("Missing required variable(s): requiredVar");
    }

    @Test
    void startProcessInstance_withTheRequiredVariableProvided_runsToCompletion() {
        ProcessDefinitionView definition = deployWithRequiredVariable();

        webTestClient.post().uri(uriBuilder -> uriBuilder.path("/api/admin/process/definitions/start")
                        .queryParam("id", definition.id()).build())
                .header("X-Ntrloc-User", "root")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("variables", Map.of("requiredVar", "some value")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.ended").isEqualTo(true);
    }

    @Test
    void startProcessInstance_forAnUnknownDefinitionId_returnsBadRequestWithAMessage() {
        webTestClient.post().uri(uriBuilder -> uriBuilder.path("/api/admin/process/definitions/start")
                        .queryParam("id", "no-such-definition:1:" + UUID.randomUUID()).build())
                .header("X-Ntrloc-User", "root")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").exists();
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
