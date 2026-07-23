package org.ntrloc.graph.db.partition.process;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Deliberately trivial -- proves a service task can resolve and execute a Spring-managed
// delegate bean (via delegateExpression, not flowable:class reflection), which is the mechanism
// real service tasks will need in order to call back into EntityManager later. @ProcessAccessible
// is required here now, not optional -- ProcessAccessibleBeansMap closes ${...} resolution to
// only annotated beans, so this delegate stopped resolving the moment that landed (caught live: it
// broke this test-only process's own ProcessEngineIntegrationTest coverage, exactly as intended).
@Component("helloWorldDelegate")
@ProcessAccessible
public class HelloWorldDelegate implements JavaDelegate {

    private static final Logger LOG = LoggerFactory.getLogger(HelloWorldDelegate.class);

    // History is off (ProcessEngineConfig) and this task has no wait states, so the runtime
    // execution/variable rows are already gone by the time startProcessInstanceById() returns to
    // the caller -- this log line is the only trace left that the task actually ran, since there's
    // no historic API left to query it after the fact.
    @Override
    public void execute(DelegateExecution execution) {
        execution.setVariable("greeting", "Hello from Flowable");
        LOG.info("HelloWorldDelegate ran for process instance {}", execution.getProcessInstanceId());
    }
}
