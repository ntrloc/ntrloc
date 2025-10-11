package org.ntrloc.graph.ui;

import org.ntrloc.graph.db.schema.ItemDefinition;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Optional;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(SchemaController.SCHEMA_PREFIX)
public class SchemaController extends AbstractController {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaController.class);

    static final String SCHEMA_PREFIX = "/schema";

    SchemaController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                     @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                     ResourceLoader resourceLoader,
                     SchemaManager schemaManager) {
        super("schema", resourceLocation, templateEngine, resourceLoader, schemaManager);
    }

    @GetMapping("/item/{name}/fields")
    public ResponseEntity<Resource> test(@PathVariable("name") String name) {
        Context context = new Context();
        Optional<ItemDefinition> opt = schemaManager.retrieveItemDefinition(name);
        if (opt.isPresent()) {
            ItemDefinition itemDefinition = opt.get();
            context.setVariable("itemDefinition", itemDefinition);
            var result = templateEngine.process("/fragments/itemDefinitionFields.html", context);
            return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

}
