package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.ManagementService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// An async job that always throws, run by the real async executor: each failure is recorded (message
// and stack trace) and retried, and once its retries are spent it lands in the dead-letter jobs. The
// stack trace lives in process_bytearray, so this also covers ByteArrayDataManagerImpl. Jobs are found
// in process_job directly: Flowable's timer and dead-letter job queries aren't implemented over it yet.
class FailingAsyncJobIntegrationTest extends AbstractIntegrationTest {

    private static final String PROCESS_KEY = "failingAsyncJob";
    private static final String FAILURE = "The always-failing task failed";

    // Two retries, a second apart, rather than Flowable's default three, ten seconds apart.
    private static final String BPMN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                         xmlns:flowable="http://flowable.org/bpmn"
                         targetNamespace="http://ntrloc.org/test">
              <process id="%s" isExecutable="true">
                <startEvent id="start"/>
                <sequenceFlow id="toFail" sourceRef="start" targetRef="fail"/>
                <serviceTask id="fail" name="Always fails" flowable:async="true"
                             flowable:class="%s">
                  <extensionElements>
                    <flowable:failedJobRetryTimeCycle>R2/PT1S</flowable:failedJobRetryTimeCycle>
                  </extensionElements>
                </serviceTask>
                <sequenceFlow id="toEnd" sourceRef="fail" targetRef="end"/>
                <endEvent id="end"/>
              </process>
            </definitions>
            """.formatted(PROCESS_KEY, AlwaysFails.class.getName());

    public static class AlwaysFails implements JavaDelegate {
        @Override
        public void execute(DelegateExecution execution) {
            throw new IllegalStateException(FAILURE);
        }
    }

    private record JobRow(String id, int retries, String exceptionMessage, String exceptionStackId) {}

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private ManagementService managementService;

    @Autowired
    private JdbcClient jdbcClient;

    private final List<String> deploymentIds = new ArrayList<>();

    @AfterEach
    void removeDeployments() {
        deploymentIds.forEach(id -> repositoryService.deleteDeployment(id, true));
    }

    private String startProcess() {
        deploymentIds.add(repositoryService.createDeployment().addString("failing-async-job.bpmn20.xml", BPMN).deploy().getId());
        return runtimeService.startProcessInstanceByKey(PROCESS_KEY).getId();
    }

    // The process instance's job of this kind (TIMER while it waits to retry, DEADLETTER once out of
    // retries), or null while there's none.
    private JobRow job(String instance, String kind) {
        return jdbcClient.sql("SELECT id, retries, exception_message, exception_stack_id FROM process_job"
                        + " WHERE process_instance_id = :instance AND job_kind = :kind")
                .param("instance", instance).param("kind", kind)
                .query((rs, _) -> new JobRow(rs.getString("id"), rs.getInt("retries"), rs.getString("exception_message"),
                        rs.getString("exception_stack_id")))
                .optional().orElse(null);
    }

    private long storedByteArrays(String id) {
        return jdbcClient.sql("SELECT COUNT(*) FROM process_bytearray WHERE id = :id").param("id", id).query(Long.class).single();
    }

    private static <T> T await(Supplier<T> probe) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            T value = probe.get();
            if (value != null) return value;
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting");
    }

    @Test
    void aFailedJobIsRetried_withItsFailureRecorded() {
        String instance = startProcess();

        JobRow retrying = await(() -> job(instance, "TIMER"));

        assertThat(retrying.retries()).isPositive();
        assertThat(retrying.exceptionMessage()).isEqualTo(FAILURE);
        assertThat(managementService.getTimerJobExceptionStacktrace(retrying.id()))
                .contains("IllegalStateException: " + FAILURE)
                .contains(AlwaysFails.class.getName());
    }

    @Test
    void aJobOutOfRetries_isDeadLettered_withItsStackTrace_whichGoesWithIt() {
        String instance = startProcess();

        JobRow dead = await(() -> job(instance, "DEADLETTER"));

        assertThat(dead.retries()).isZero();
        assertThat(dead.exceptionMessage()).isEqualTo(FAILURE);
        assertThat(managementService.getDeadLetterJobExceptionStacktrace(dead.id())).contains(AlwaysFails.class.getName());
        assertThat(storedByteArrays(dead.exceptionStackId())).isOne();

        managementService.deleteDeadLetterJob(dead.id());

        assertThat(storedByteArrays(dead.exceptionStackId())).isZero();
    }
}
