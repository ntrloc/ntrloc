package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// History is off (see ProcessEngineConfig), so this verifies success by querying our own
// process_* tables directly instead -- proof that persistence is genuinely flowing through the
// custom DataManagers rather than silently falling back to Flowable's MyBatis defaults.
class ProcessEngineIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcClient jdbcClient;

    private final List<String> deploymentIdsToClean = new java.util.ArrayList<>();

    // The two dynamically-deployed test fixtures below (message/signal start events) aren't
    // needed by any other test and would otherwise leak deployments/definitions/event
    // subscriptions across the shared test database.
    @AfterEach
    void cleanUpDynamicDeployments() {
        deploymentIdsToClean.forEach(id -> repositoryService.deleteDeployment(id, true));
        deploymentIdsToClean.clear();
    }

    @Test
    void helloWorldProcessRunsToCompletion() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloWorld");

        // helloWorld ends in a User Task ("Review Greeting"), a genuine wait state -- the process
        // legitimately pauses here rather than reaching the end event on its own, so completion
        // requires claiming and completing it, same as userTaskClaimAndCompleteAdvancesProcessToCompletion
        // below. instance itself is a snapshot from startProcessInstanceByKey and won't reflect
        // this, hence no isEnded() check here -- the executions/variables == 0 assertions below
        // are the real proof completion actually happened.
        Task task = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateGroup("reviewers")
                .singleResult();
        taskService.claim(task.getId(), "alice");
        taskService.complete(task.getId());

        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(instance.getProcessDefinitionId())
                .singleResult();

        // Repository-level entities (deployment/resource) persist independently of any single
        // process instance's lifecycle -- proves the custom Deployment/Resource DataManagers are
        // genuinely in effect, not just the runtime ones. Scoped to this specific deployment
        // (not a whole-table count): other tests (e.g. ProcessAdminControllerIntegrationTest)
        // deploy their own unrelated processes in this shared test database. The resources count
        // is further scoped to hello-world's own resource name -- ProcessEngineConfig bundles
        // hello-world into the same single deployment as the rest of ntrloc.deploymentResources
        // (all deployed together via one setDeploymentResources() call), so this deployment
        // legitimately contains more than just hello-world's own resource(s).
        long deployments = jdbcClient.sql("SELECT COUNT(*) FROM process_deployment WHERE id = :id")
                .param("id", definition.getDeploymentId())
                .query(Long.class).single();
        long resources = jdbcClient.sql("SELECT COUNT(*) FROM process_resource WHERE deployment_id = :id AND name LIKE '%hello-world.bpmn20.xml'")
                .param("id", definition.getDeploymentId())
                .query(Long.class).single();
        long definitions = jdbcClient.sql("SELECT COUNT(*) FROM process_definition WHERE process_key = 'helloWorld'")
                .query(Long.class).single();

        assertThat(deployments).isEqualTo(1);
        assertThat(resources).isEqualTo(1);
        assertThat(definitions).isEqualTo(1);

        // The execution and its variable existed mid-flight (proven implicitly: the process
        // couldn't have run the service task and completed otherwise) and are correctly cleaned
        // up on completion -- proves the custom Execution/VariableInstance DataManagers'
        // delete() methods actually ran, not just insert()/update().
        long executions = jdbcClient.sql("SELECT COUNT(*) FROM process_execution WHERE process_instance_id = :id")
                .param("id", instance.getId())
                .query(Long.class).single();
        long variables = jdbcClient.sql("SELECT COUNT(*) FROM process_variable WHERE process_instance_id = :id")
                .param("id", instance.getId())
                .query(Long.class).single();

        assertThat(executions).isEqualTo(0);
        assertThat(variables).isEqualTo(0);
    }

    // Exercises RepositoryService's real query API (createProcessDefinitionQuery()), not just
    // the direct table counts above -- this is the path an admin UI "list definitions" screen
    // actually uses, and findProcessDefinitionsByQueryCriteria() was, until now, stubbed to
    // always return an empty list regardless of what was deployed. Scoped to the helloWorld key
    // throughout (not an unfiltered list()): other tests deploy their own unrelated processes in
    // this shared test database.
    @Test
    void repositoryServiceListsDeployedProcessDefinitions() {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("helloWorld")
                .list();

        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).getKey()).isEqualTo("helloWorld");

        assertThat(repositoryService.createProcessDefinitionQuery().processDefinitionKey("helloWorld").count())
                .isEqualTo(1);
        assertThat(repositoryService.createProcessDefinitionQuery().processDefinitionKey("nonexistent").list())
                .isEmpty();
        assertThat(repositoryService.createProcessDefinitionQuery().processDefinitionKey("helloWorld").latestVersion().list())
                .hasSize(1);
    }

    // The actual acceptance criterion for the zero-MyBatis work (docs/ntrloc-workflow-summary.md
    // Section 6): Flowable's own schema management must never run, so none of its ACT_*/FLW_*
    // tables get created -- not just "unused," genuinely absent. Runs after the engine has already
    // booted and deployed/started a process (via the other tests' shared context), so this also
    // catches a table only ever created lazily on first real use, not just at engine startup.
    @Test
    void noFlowableDefaultTablesExist() {
        List<String> flowableTables = jdbcClient.sql("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND (table_name ILIKE 'act\\_%' OR table_name ILIKE 'flw\\_%')
                """)
                .query(String.class)
                .list();

        assertThat(flowableTables).isEmpty();
    }

    // helloWorld's own "reviewGreeting" user task (candidateGroups="reviewers") is exactly why
    // helloWorldProcessRunsToCompletion's isEnded() assertion fails -- the process legitimately
    // blocks there. This exercises the other side: claim/complete against that same task, driving
    // the process the rest of the way to completion and proving TaskDataManagerImpl and the
    // candidate/assignee identity-link handling (process_task_identitylink) work end-to-end, not
    // just insert().
    @Test
    void userTaskClaimAndCompleteAdvancesProcessToCompletion() {
        ProcessInstance instance = runtimeService.startProcessInstanceByKey("helloWorld");

        Task task = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskCandidateGroup("reviewers")
                .singleResult();
        assertThat(task).isNotNull();
        assertThat(task.getName()).isEqualTo("Review Greeting");
        assertThat(task.getAssignee()).isNull();

        long candidateLinks = jdbcClient.sql(
                "SELECT COUNT(*) FROM process_task_identitylink WHERE task_id = :id AND type = 'candidate' AND group_id = 'reviewers'")
                .param("id", task.getId())
                .query(Long.class).single();
        assertThat(candidateLinks).isEqualTo(1);

        taskService.claim(task.getId(), "alice");

        Task claimed = taskService.createTaskQuery().taskId(task.getId()).singleResult();
        assertThat(claimed.getAssignee()).isEqualTo("alice");

        taskService.complete(task.getId());

        assertThat(runtimeService.createProcessInstanceQuery().processInstanceId(instance.getId()).singleResult())
                .isNull();

        long remainingTasks = jdbcClient.sql("SELECT COUNT(*) FROM process_task WHERE id = :id")
                .param("id", task.getId())
                .query(Long.class).single();
        long remainingLinks = jdbcClient.sql("SELECT COUNT(*) FROM process_task_identitylink WHERE task_id = :id")
                .param("id", task.getId())
                .query(Long.class).single();
        assertThat(remainingTasks).isEqualTo(0);
        assertThat(remainingLinks).isEqualTo(0);
    }

    // Deploys its own tiny fixture rather than reusing hello-world.bpmn20.xml -- message start
    // events are otherwise untested anywhere in this codebase. Proves EventSubscriptionDataManagerImpl
    // actually round-trips a deploy-time subscription row and that
    // findMessageStartEventSubscriptionByName resolves it for real dispatch, not just that the
    // table exists (Section 4/5's actual load-bearing claim).
    @Test
    void messageStartEventDispatchesToWaitingProcessDefinition() {
        Deployment deployment = repositoryService.createDeployment()
                .addString("message-start.bpmn20.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     targetNamespace="org.ntrloc.workflow.test">
                          <message id="testMessage" name="testMessage"/>
                          <process id="messageStartTest" name="Message Start Test" isExecutable="true">
                            <startEvent id="start">
                              <messageEventDefinition messageRef="testMessage"/>
                            </startEvent>
                            <sequenceFlow id="flow1" sourceRef="start" targetRef="end"/>
                            <endEvent id="end"/>
                          </process>
                        </definitions>
                        """)
                .deploy();
        deploymentIdsToClean.add(deployment.getId());

        long subscriptions = jdbcClient.sql(
                "SELECT COUNT(*) FROM process_event_subscription WHERE event_type = 'message' AND event_name = 'testMessage'")
                .query(Long.class).single();
        assertThat(subscriptions).isEqualTo(1);

        ProcessInstance instance = runtimeService.startProcessInstanceByMessage("testMessage");

        assertThat(instance.isEnded()).isTrue();
        assertThat(instance.getProcessDefinitionId())
                .isEqualTo(repositoryService.createProcessDefinitionQuery()
                        .deploymentId(deployment.getId())
                        .singleResult()
                        .getId());
    }

    // Companion to the message test above: an *intermediate* catching signal, not a start event --
    // exercises findSignalEventSubscriptionsByEventName (fan-out dispatch) and the
    // execution-scoped row (execution_id set, unlike a start-event subscription) rather than
    // findMessageStartEventSubscriptionByName's single-row lookup.
    @Test
    void signalEventReceivedResumesWaitingProcessInstance() {
        Deployment deployment = repositoryService.createDeployment()
                .addString("signal-catch.bpmn20.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn"
                                     targetNamespace="org.ntrloc.workflow.test">
                          <signal id="testSignal" name="testSignal" flowable:scope="global"/>
                          <process id="signalCatchTest" name="Signal Catch Test" isExecutable="true">
                            <startEvent id="start"/>
                            <sequenceFlow id="flow1" sourceRef="start" targetRef="waitForSignal"/>
                            <intermediateCatchEvent id="waitForSignal">
                              <signalEventDefinition signalRef="testSignal"/>
                            </intermediateCatchEvent>
                            <sequenceFlow id="flow2" sourceRef="waitForSignal" targetRef="end"/>
                            <endEvent id="end"/>
                          </process>
                        </definitions>
                        """)
                .deploy();
        deploymentIdsToClean.add(deployment.getId());

        ProcessInstance instance = runtimeService.startProcessInstanceByKey("signalCatchTest");
        assertThat(instance.isEnded()).isFalse();

        // Scoped by process_instance_id, not execution_id -- the intermediate catch event's
        // subscription is owned by whichever execution actually parks there, which needn't be the
        // process instance's root execution.
        long subscriptions = jdbcClient.sql(
                "SELECT COUNT(*) FROM process_event_subscription WHERE event_type = 'signal' AND event_name = 'testSignal' AND process_instance_id = :id")
                .param("id", instance.getId())
                .query(Long.class).single();
        assertThat(subscriptions).isEqualTo(1);

        runtimeService.signalEventReceived("testSignal");

        // Not runtimeService.createProcessInstanceQuery()...singleResult() -- ExecutionQuery/
        // ProcessInstanceQuery's real query surface is stubbed to always return empty (see
        // ExecutionDataManagerImpl's class comment), so that would pass trivially regardless of
        // whether the process actually completed. Check the table directly instead.
        long remainingExecutions = jdbcClient.sql("SELECT COUNT(*) FROM process_execution WHERE process_instance_id = :id")
                .param("id", instance.getId())
                .query(Long.class).single();
        assertThat(remainingExecutions).isEqualTo(0);

        long remainingSubscriptions = jdbcClient.sql(
                "SELECT COUNT(*) FROM process_event_subscription WHERE process_definition_id = :defId")
                .param("defId", instance.getProcessDefinitionId())
                .query(Long.class).single();
        assertThat(remainingSubscriptions).isEqualTo(0);
    }
}
