package org.ntrloc.graph.db.partition.process.dmn;

import org.flowable.dmn.api.DmnDecision;
import org.flowable.dmn.api.DmnDeployment;
import org.flowable.dmn.api.DmnRepositoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import java.util.List;

// Mirrors ProcessAdminController exactly, DMN-side.
@RestController
@RequestMapping("/api/admin/dmn")
public class DecisionAdminController {

    public record DecisionDefinitionView(String id, String key, String name, int version, String deploymentId) {}

    public record DeployErrorResponse(String message) {}

    private final DmnRepositoryService dmnRepositoryService;

    public DecisionAdminController(DmnRepositoryService dmnRepositoryService) {
        this.dmnRepositoryService = dmnRepositoryService;
    }

    @GetMapping("/decisions")
    ResponseEntity<List<DecisionDefinitionView>> getDecisions() {
        List<DecisionDefinitionView> decisions = dmnRepositoryService.createDecisionQuery()
                .list().stream()
                .map(this::toView)
                .toList();
        return ResponseEntity.ok(decisions);
    }

    // id is a query param, not a path variable -- same reasoning as ProcessAdminController's
    // getDefinitionXml: decision ids follow the same "<key>:<version>:<generatedId>" shape, and a
    // literal colon in a path segment 404s (Reactor Netty's URI parsing, ahead of Spring routing).
    @GetMapping("/decisions/xml")
    ResponseEntity<String> getDecisionXml(@RequestParam String id) throws IOException {
        try (InputStream in = dmnRepositoryService.getDmnResource(id)) {
            String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_XML)
                    .body(xml);
        }
    }

    // Same "always a brand-new deployment, Flowable assigns the next version by key" behavior as
    // ProcessAdminController.deployNewVersion, and the same broad RuntimeException catch -- DMN
    // XML parse failures surface as org.flowable.dmn.xml.exceptions.DmnXMLException (a
    // RuntimeException, not necessarily a FlowableException), so a specific catch type would risk
    // missing some failure modes.
    @PostMapping("/decisions/{key}/versions")
    ResponseEntity<?> deployNewVersion(@PathVariable String key, @RequestBody String xml) {
        try {
            DmnDeployment deployment = dmnRepositoryService.createDeployment()
                    .name(key)
                    .addString(key + ".dmn", xml)
                    .deploy();
            DmnDecision decision = dmnRepositoryService.createDecisionQuery()
                    .deploymentId(deployment.getId())
                    .decisionKey(key)
                    .singleResult();
            return ResponseEntity.ok(toView(decision));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DeployErrorResponse("Failed to deploy decision table: " + e.getMessage()));
        }
    }

    private DecisionDefinitionView toView(DmnDecision decision) {
        return new DecisionDefinitionView(decision.getId(), decision.getKey(), decision.getName(),
                decision.getVersion(), decision.getDeploymentId());
    }
}
