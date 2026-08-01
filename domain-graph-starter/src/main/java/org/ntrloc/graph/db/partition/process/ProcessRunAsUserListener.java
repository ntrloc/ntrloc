package org.ntrloc.graph.db.partition.process;

import org.flowable.bpmn.constants.BpmnXMLConstants;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.api.delegate.event.AbstractFlowableEventListener;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEventType;
import org.flowable.common.engine.api.delegate.event.FlowableEvent;
import org.flowable.common.engine.api.delegate.event.FlowableEventType;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.delegate.event.FlowableProcessStartedEvent;
import org.flowable.engine.impl.persistence.entity.ExecutionEntity;
import org.ntrloc.graph.db.partition.process.persistence.NtrlocPrincipalVariableType;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.EnumSet;

// Registered on SpringProcessEngineConfiguration (see ProcessEngineConfig) alongside
// TaskEventListener -- same "engine calls onEvent() as things happen" mechanism, different event.
//
// Closes a real gap: a timer/signal/message start event never goes through
// ProcessAdminController.startProcessInstance, so nothing ever sets the "principal" variable a
// script might need for entityManager.project(...).
//
// PROCESS_STARTED, not PROCESS_CREATED, is what makes this safe to layer on top of a real HTTP
// caller without a race: confirmed by reading ProcessInstanceHelper directly --
// PROCESS_CREATED fires *before* the caller-supplied variables map is applied to the new
// instance, but PROCESS_STARTED fires after, so by the time this listener runs,
// event.getVariables() already reflects whatever ProcessAdminController set (if this was an
// HTTP-triggered run) -- checking for that first is what keeps a declared run-as-user from ever
// overriding a real caller, no explicit "was this HTTP-triggered" tracking needed.
//
// No implicit "system" user fallback if a process declares nothing -- prefer admins state
// this explicitly, per process (in the process editor's Run As User field), over an engine-wide
// default nobody chose.
@Component
public class ProcessRunAsUserListener extends AbstractFlowableEventListener {

    private static final Collection<FlowableEngineEventType> TYPES = EnumSet.of(FlowableEngineEventType.PROCESS_STARTED);

    private final RepositoryService repositoryService;
    private final PrincipalResolver principalResolver;

    // @Lazy on RepositoryService specifically: this bean is itself wired into
    // ProcessEngineConfig.processEngineConfiguration(...) (config.setEventListeners(...)) so its
    // constructor runs *during* that @Bean method -- but RepositoryService is
    // processEngine.getRepositoryService(), and ProcessEngine is built *from* the very
    // SpringProcessEngineConfiguration that method returns. A real circular dependency without
    // this: @Lazy defers resolving the actual RepositoryService bean behind a proxy until
    // onEvent() first uses it, by which point the whole engine is long since built.
    public ProcessRunAsUserListener(@Lazy RepositoryService repositoryService, PrincipalResolver principalResolver) {
        this.repositoryService = repositoryService;
        this.principalResolver = principalResolver;
    }

    @Override
    public Collection<? extends FlowableEventType> getTypes() {
        return TYPES;
    }

    @Override
    public void onEvent(FlowableEvent event) {
        if (!(event instanceof FlowableProcessStartedEvent startedEvent)) {
            return;
        }
        // getVariables() is null (not just missing the key) for the very common case of a start
        // with no variables supplied at all -- runtimeService.startProcessInstanceByKey(key), no
        // map -- exactly what a timer/signal/message start does, and exactly the case this
        // listener exists for.
        if (startedEvent.getVariables() != null
                && startedEvent.getVariables().containsKey(NtrlocPrincipalVariableType.PRINCIPAL_VARIABLE)) {
            return;
        }

        // Read off the entity, not FlowableProcessStartedEvent.getProcessDefinitionId() -- that
        // method belongs to FlowableEngineEvent, a sibling interface the concrete event object
        // also implements, but not one FlowableProcessStartedEvent's own declared type exposes.
        ExecutionEntity execution = (ExecutionEntity) startedEvent.getEntity();
        String processDefinitionId = execution.getProcessDefinitionId();

        BpmnModel bpmnModel = repositoryService.getBpmnModel(processDefinitionId);
        String runAsUserExternalId = bpmnModel.getMainProcess()
                .getAttributeValue(BpmnXMLConstants.FLOWABLE_EXTENSIONS_NAMESPACE, "runAsUser");
        if (runAsUserExternalId == null || runAsUserExternalId.isBlank()) {
            return;
        }

        NtrlocPrincipal principal = principalResolver.resolveByExternalId(runAsUserExternalId)
                .orElseThrow(() -> new IllegalStateException(
                        "Process '" + processDefinitionId + "' declares runAsUser '"
                                + runAsUserExternalId + "', which does not resolve to a known user"));

        execution.setVariable(NtrlocPrincipalVariableType.PRINCIPAL_VARIABLE, principal);
    }

    // A misconfigured runAsUser (declared but unresolvable) should fail the process start loudly,
    // not silently leave it running with no principal -- unlike TaskEventListener's broadcast,
    // this isn't best-effort.
    @Override
    public boolean isFailOnException() {
        return true;
    }
}
