package org.ntrloc.graph.db.partition.process;

import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.DataSpec;
import org.flowable.bpmn.model.IOSpecification;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.ntrloc.graph.db.partition.process.persistence.NtrlocPrincipalVariableType;
import org.ntrloc.graph.db.partition.security.NtrlocPrincipal;
import org.ntrloc.graph.db.partition.security.PrincipalResolver;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/process")
public class ProcessAdminController {

    public record ProcessDefinitionView(String id, String key, String name, int version, String deploymentId) {}

    public record DeployErrorResponse(String message) {}

    public record ProcessInstanceView(String id, boolean ended) {}

    // variables is @Nullable, not just an empty-default map: a process with no ioSpecification at
    // all should still start with a body-less POST exactly as it always could, not force every
    // caller to send "variables": {}.
    public record StartProcessRequest(@Nullable Map<String, Object> variables) {}

    private final RepositoryService repositoryService;
    private final RuntimeService runtimeService;
    private final PrincipalResolver principalResolver;

    public ProcessAdminController(RepositoryService repositoryService, RuntimeService runtimeService, PrincipalResolver principalResolver) {
        this.repositoryService = repositoryService;
        this.runtimeService = runtimeService;
        this.principalResolver = principalResolver;
    }

    @GetMapping("/definitions")
    ResponseEntity<List<ProcessDefinitionView>> getDefinitions() {
        List<ProcessDefinitionView> definitions = repositoryService.createProcessDefinitionQuery()
                .list().stream()
                .map(this::toView)
                .toList();
        return ResponseEntity.ok(definitions);
    }

    // id is a query param, not a path variable: Flowable's own definition ids follow the format
    // "<key>:<version>:<generatedId>" (e.g. "helloWorld:1:1"), and a literal colon in a path
    // *segment* 404s here even URL-encoded (Reactor Netty's URI parsing, ahead of Spring's own
    // routing -- confirmed empirically, not a Spring PathPattern quirk specific to this route).
    @GetMapping("/definitions/xml")
    ResponseEntity<String> getDefinitionXml(@RequestParam String id) throws IOException {
        try (InputStream in = repositoryService.getProcessModel(id)) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xml);
        }
    }

    // Flowable deployments are immutable and versioned: this always creates a brand-new
    // deployment (never overwrites one in place). Flowable auto-detects that the deployed BPMN
    // reuses an existing process key and assigns the next version number itself -- see
    // ProcessDefinitionDataManagerImpl.findLatestProcessDefinitionByKey().
    //
    // Broad RuntimeException catch is deliberate: BPMN parse failures during deploy() surface as
    // org.flowable.bpmn.exceptions.XMLException (a plain RuntimeException, not a
    // FlowableException), while other deploy-time validation failures use FlowableException --
    // both, and anything else deploy() might throw, should reach the client as a clean 400 with
    // the underlying message, not a bare 500.
    @PostMapping("/definitions/{key}/versions")
    ResponseEntity<?> deployNewVersion(@PathVariable String key, @RequestBody String xml) {
        try {
            Deployment deployment = repositoryService.createDeployment()
                    .name(key)
                    .addString(key + ".bpmn20.xml", xml)
                    .deploy();
            ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                    .deploymentId(deployment.getId())
                    .processDefinitionKey(key)
                    .singleResult();
            return ResponseEntity.ok(toView(definition));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DeployErrorResponse("Failed to deploy process: " + e.getMessage()));
        }
    }

    // Same query-param id convention as getDefinitionXml above, for the same reason (a literal
    // colon in a path segment 404s). Runs the specific version the caller names, not just
    // whatever's latest -- the admin editor uses this to run exactly the version currently open.
    // history is "none" (ProcessEngineConfig), so there's no historic API to confirm completion
    // after the fact -- startProcessInstanceById runs the whole thing synchronously in this same
    // call (this example process has no wait states), and returns the very entity Flowable
    // updates in place as it runs, so isEnded() on it already reflects the final state.
    @PostMapping("/definitions/start")
    ResponseEntity<?> startProcessInstance(@RequestParam String id, @RequestBody(required = false) StartProcessRequest request,
                                            ServerHttpRequest httpRequest, Authentication authentication) {
        Map<String, Object> variables = request != null && request.variables() != null
                ? new HashMap<>(request.variables())
                : new HashMap<>();
        List<String> missing = missingRequiredVariables(id, variables);
        if (!missing.isEmpty()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DeployErrorResponse("Missing required variable(s): " + String.join(", ", missing)));
        }
        // Set after copying the caller-supplied map, not merged into it -- this always overwrites
        // whatever the caller sent under this key with the real authenticated principal, so
        // nothing reachable over the API can spoof "who ran this" by including their own
        // "principal" in the request body.
        NtrlocPrincipal principal = principalResolver.resolve(httpRequest, authentication);
        variables.put(NtrlocPrincipalVariableType.PRINCIPAL_VARIABLE, principal);
        try {
            ProcessInstance instance = runtimeService.startProcessInstanceById(id, variables);
            return ResponseEntity.ok(new ProcessInstanceView(instance.getId(), instance.isEnded()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DeployErrorResponse("Failed to run process: " + e.getMessage()));
        }
    }

    // ioSpecification.dataInputRefs is Flowable's own already-flattened view of what the BPMN XML
    // spells as <inputSet><dataInputRefs>id</dataInputRefs></inputSet> -- an InputSet is a real,
    // separately-addressable element in the spec, but this parsed model collapses straight to a
    // List<String> of required DataSpec ids directly on IOSpecification itself (confirmed against
    // IOSpecification.java: no InputSet class exists in this model at all), matching this
    // codebase's one-inputSet-means-"the required ones" usage rather than the spec's fuller
    // multi-inputSet "alternative valid combinations" machinery. Presence-only: a required
    // variable's *value* isn't type-checked against its declared itemSubjectRef here -- the
    // declared type drives the admin UI's Run-dialog input widgets, not a server-side type gate.
    private List<String> missingRequiredVariables(String processDefinitionId, Map<String, Object> variables) {
        BpmnModel model = repositoryService.getBpmnModel(processDefinitionId);
        IOSpecification ioSpecification = model.getMainProcess().getIoSpecification();
        if (ioSpecification == null) {
            return List.of();
        }
        List<String> missing = new ArrayList<>();
        for (DataSpec dataInput : ioSpecification.getDataInputs()) {
            boolean required = ioSpecification.getDataInputRefs().contains(dataInput.getId());
            if (required && !variables.containsKey(dataInput.getName())) {
                missing.add(dataInput.getName());
            }
        }
        return missing;
    }

    private ProcessDefinitionView toView(ProcessDefinition definition) {
        return new ProcessDefinitionView(definition.getId(), definition.getKey(), definition.getName(),
                definition.getVersion(), definition.getDeploymentId());
    }
}
