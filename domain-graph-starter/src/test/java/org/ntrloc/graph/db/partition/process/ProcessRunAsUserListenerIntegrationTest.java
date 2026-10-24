package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// flowable:runAsUser holds a user's id (UUID), never their email. Each process here waits at a
// user task so its principal variable can still be read after start; the deployments (and with
// them, the waiting instances) are deleted after each test.
class ProcessRunAsUserListenerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private SecurityRepository securityRepo;

    private final List<String> deploymentIds = new ArrayList<>();

    @AfterEach
    void deleteDeployments() {
        deploymentIds.forEach(id -> repositoryService.deleteDeployment(id, true));
        deploymentIds.clear();
    }

    private String deployRunningAs(String runAsUser) {
        String key = "runAsUserTest" + UUID.randomUUID().toString().replace("-", "");
        deploymentIds.add(repositoryService.createDeployment()
                .addString(key + ".bpmn20.xml", """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                     xmlns:flowable="http://flowable.org/bpmn"
                                     targetNamespace="org.ntrloc.workflow.test">
                          <process id="%s" isExecutable="true" flowable:runAsUser="%s">
                            <startEvent id="start"/>
                            <sequenceFlow id="flow1" sourceRef="start" targetRef="wait"/>
                            <userTask id="wait" name="Wait"/>
                            <sequenceFlow id="flow2" sourceRef="wait" targetRef="end"/>
                            <endEvent id="end"/>
                          </process>
                        </definitions>
                        """.formatted(key, runAsUser))
                .deploy().getId());
        return key;
    }

    @Test
    void aProcessStartedWithNoCaller_runsAsTheDeclaredUserId() {
        var user = securityRepo.createUser("Run As User", "run-as-" + UUID.randomUUID() + "@example.com", false);
        String key = deployRunningAs(user.id().toString());

        ProcessInstance instance = runtimeService.startProcessInstanceByKey(key);

        assertThat(runtimeService.getVariable(instance.getId(), PrincipalResolver.PRINCIPAL_VARIABLE))
                .isEqualTo(user.id().toString());
    }

    @Test
    void aRunAsUserGivenAsAnEmail_failsTheStart() {
        var user = securityRepo.createUser("Run As User", "run-as-" + UUID.randomUUID() + "@example.com", false);
        String key = deployRunningAs(user.email());

        assertThatThrownBy(() -> runtimeService.startProcessInstanceByKey(key))
                .hasStackTraceContaining("which does not resolve to a known user");
    }
}
