package org.ntrloc.graph.draft;

import org.ntrloc.graph.db.partition.schema.SchemaService;
import org.ntrloc.graph.db.partition.schema.definition.view.calculated.SchemaView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// The schema API inside a draft: live's schema, filtered by the user's draft grants.
@RestController
@RequestMapping("/api/drafts/{draftId}/schema")
public class DraftSchemaController {

    private final SchemaService schemaService;
    private final DraftAccessService draftAccessService;

    public DraftSchemaController(SchemaService schemaService, DraftAccessService draftAccessService) {
        this.schemaService = schemaService;
        this.draftAccessService = draftAccessService;
    }

    @GetMapping
    SchemaView getSchema(DraftScope scope) {
        return schemaService.getSchema(draftAccessService.getUserGrants(scope));
    }
}
