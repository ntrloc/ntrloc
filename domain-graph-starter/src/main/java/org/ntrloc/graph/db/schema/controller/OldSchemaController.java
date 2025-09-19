package org.ntrloc.graph.db.schema.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/schema/old")
public class OldSchemaController {

    private static final Logger LOG = LogManager.getLogger(OldSchemaController.class);

    private final SchemaManager schemaManager;

    public OldSchemaController(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
    }

    @PutMapping("/entity")
    void upsertEntityDefinition(@RequestBody ItemDefinition definition) {
        LOG.info("Upserting definition {}", definition);
        schemaManager.createItemDefinition(definition);
    }

    @GetMapping("/entity")
    Set<ItemDefinition> getEntityDefinitions() {
        return schemaManager.retrieveItemDefinitions();
    }

}
