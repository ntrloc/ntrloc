package org.ntrloc.graph.db.partition.process;

import org.junit.jupiter.api.BeforeEach;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Covers TaskAdminController's task listing/claim/complete and process-group listing endpoints,
// plus the SSE tasks/events endpoint's response headers. Reuses ProcessTestDomainInitializer's
// deployed helloWorld process (its own "reviewGreeting" user task, candidateGroups="reviewers") --
// same precedent as ProcessEngineIntegrationTest.userTaskClaimAndCompleteAdvancesProcessToCompletion.
// Every non-superuser check below uses a freshly created, UUID-suffixed throwaway user rather than
// the standing alice/bob/carol fixture, since this class also needs "reviewers" process-group
// membership (a distinct concept from security_group -- see ProcessGroupRepository's own comment)
// and doesn't want to risk perturbing another test class's assumptions about those three.
class TaskAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    // Every endpoint requires an authenticated caller. root is AuthorizationTestDataInitializer's
    // seeded superuser; a request that sets its own Authorization header still uses that one.
    @BeforeEach
    void authenticateAsRoot() {
        webTestClient = webTestClient.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, bearerTokenFor("root")).build();
    }

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private SecurityRepository securityRepo;

    @Autowired
    private ProcessGroupRepository groupRepo;

    @Autowired
    private TaskEventBroadcaster broadcaster;

    // Filters by process instance in memory, not via .processInstanceId(instance.getId()) on the
    // query itself: TaskDataManagerImpl's whereClause() doesn't support that filter (only
    // taskId/assignee/candidateUser/candidateGroups -- see that class's own test coverage notes),
    // so it would silently be ignored and return every unclaimed "reviewers"-candidate task left
    // over from other tests sharing this singleton container, not just this one.
    private Task startProcessAndGetReviewTask() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloWorld");
        return taskService.createTaskQuery()
                .taskCandidateGroup("reviewers")
                .list().stream()
                .filter(t -> t.getProcessInstanceId().equals(instance.getId()))
                .findFirst()
                .orElseThrow();
    }

    private SecurityRepository.UserRow createUser() {
        return securityRepo.createUser("user-" + UUID.randomUUID(), "Test User", null, false);
    }

    // Every test that starts a process instance must reach this by its end: the reviewGreeting
    // task it creates is a *candidate-group* task with no per-instance scoping in our own query
    // layer (see startProcessAndGetReviewTask's own comment), so an uncompleted one doesn't just
    // linger harmlessly -- it becomes a second "reviewers"-candidate row that breaks any other
    // test class's own .singleResult()/.taskCandidateGroup() call relying on there being exactly
    // one (confirmed live: this broke ProcessEngineIntegrationTest's two such assertions the first
    // time a test here left one behind).
    private void completeTask(String taskId) {
        taskService.complete(taskId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listTasksAs(String externalId) {
        return (List<Map<String, Object>>) (List<?>) webTestClient.get().uri("/api/admin/process/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor(externalId))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody();
    }

    // --- Listing, with per-principal filtering ---

    @Test
    void listTasks_asSuperuser_showsEveryTask() {
        Task task = startProcessAndGetReviewTask();

        List<Map<String, Object>> tasks = listTasksAs("root");

        assertThat(tasks).extracting(t -> t.get("id")).contains(task.getId());
        completeTask(task.getId());
    }

    @Test
    void listTasks_forAUserWithNoAssignmentOrCandidacy_doesNotShowIt() {
        Task task = startProcessAndGetReviewTask();
        var unrelatedUser = createUser();

        List<Map<String, Object>> tasks = listTasksAs(unrelatedUser.externalId());

        assertThat(tasks).extracting(t -> t.get("id")).doesNotContain(task.getId());
        completeTask(task.getId());
    }

    @Test
    void listTasks_forTheAssignedUser_showsIt() {
        Task task = startProcessAndGetReviewTask();
        var assignee = createUser();
        taskService.claim(task.getId(), assignee.externalId());

        List<Map<String, Object>> tasks = listTasksAs(assignee.externalId());

        assertThat(tasks).extracting(t -> t.get("id")).contains(task.getId());
        completeTask(task.getId());
    }

    @Test
    void listTasks_forAReviewersGroupMember_showsItViaCandidateGroup() {
        Task task = startProcessAndGetReviewTask();
        var reviewersGroup = groupRepo.listGroups().stream()
                .filter(g -> g.name().equals("reviewers"))
                .findFirst()
                .orElseGet(() -> groupRepo.createGroup("reviewers"));
        var candidate = createUser();
        groupRepo.addUserToGroup(candidate.id(), reviewersGroup.id());

        List<Map<String, Object>> tasks = listTasksAs(candidate.externalId());

        assertThat(tasks).extracting(t -> t.get("id")).contains(task.getId());
        completeTask(task.getId());
    }

    // --- Claim ---

    @Test
    void claim_asAdmin_setsTheAssignee() {
        Task task = startProcessAndGetReviewTask();

        webTestClient.post().uri("/api/admin/process/tasks/{id}/claim", task.getId())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.assignee").isEqualTo("root")
                .jsonPath("$.id").isEqualTo(task.getId());
        completeTask(task.getId());
    }

    @Test
    void claim_forAnUnknownTaskId_returnsBadRequestWithAMessage() {
        webTestClient.post().uri("/api/admin/process/tasks/{id}/claim", "no-such-task")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").exists();
    }

    // --- Complete ---

    @Test
    void complete_advancesTheProcessPastTheTask() {
        Task task = startProcessAndGetReviewTask();
        taskService.claim(task.getId(), "root");

        webTestClient.post().uri("/api/admin/process/tasks/{id}/complete", task.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isNoContent();

        assertThat(taskService.createTaskQuery().taskId(task.getId()).singleResult()).isNull();
    }

    @Test
    void complete_withNoRequestBody_isAcceptedAsNoVariables() {
        Task task = startProcessAndGetReviewTask();
        taskService.claim(task.getId(), "root");

        webTestClient.post().uri("/api/admin/process/tasks/{id}/complete", task.getId())
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void complete_forAnUnknownTaskId_returnsBadRequestWithAMessage() {
        webTestClient.post().uri("/api/admin/process/tasks/{id}/complete", "no-such-task")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.message").exists();
    }

    // --- Process groups ---

    @Test
    void listGroups_includesCreatedGroups() {
        String name = "group-" + UUID.randomUUID();
        groupRepo.createGroup(name);

        webTestClient.get().uri("/api/admin/process/groups")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.name == '" + name + "')]").exists();
    }

    // --- SSE events ---

    // Through the real HTTP endpoint now, not the controller bean directly: SseEmitter (unlike the
    // old Flux-returning version) has no public, cross-package seam for attaching a test
    // subscriber directly to the controller method's return value -- ResponseBodyEmitter.Handler
    // is package-private in Spring itself. WebTestClient's response body, read back as a
    // Flux<ServerSentEvent<String>>, gives the same "drive a Publisher with StepVerifier" shape
    // the old test used, just sourced from the real response stream instead.
    //
    // Confirmed empirically, not assumed: WebTestClient.exchange() itself never returns until the
    // server writes something -- Spring MVC doesn't commit the response (status/headers) at the
    // moment the controller method returns its SseEmitter, only once the first real write happens
    // on it. That rules out triggering the publish from the same thread *after* exchange()
    // (nothing to trigger it with -- the thread is blocked inside exchange() itself) and also
    // rules out the old test's "publish once subscribed" trick (a client-side Flux subscription
    // and the server actually registering the emitter are two different instants over a real
    // network round trip, confirmed to race and time out when tried). A short delayed publish on
    // a background thread is what breaks the deadlock: the main thread blocks inside exchange()
    // exactly until that delayed publish causes the first write, which is also the point by which
    // broadcaster.register() (synchronous, inside the controller method, well before any
    // response is committed) is already guaranteed to have run.
    @Test
    void taskEvents_wrapsBroadcasterEventsAsServerSentEvents() {
        ScheduledExecutorService delayedPublisher = Executors.newSingleThreadScheduledExecutor();
        try {
            delayedPublisher.schedule(() -> broadcaster.publish("task-changed"), 500, TimeUnit.MILLISECONDS);

            Flux<ServerSentEvent<String>> body = webTestClient.get().uri("/api/admin/process/tasks/events")
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus().isOk()
                    .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .getResponseBody();

            StepVerifier.create(body)
                    .assertNext(event -> assertThat(event.data()).isEqualTo("task-changed"))
                    .thenCancel()
                    .verify(Duration.ofSeconds(5));
        } finally {
            delayedPublisher.shutdownNow();
        }
    }
}
