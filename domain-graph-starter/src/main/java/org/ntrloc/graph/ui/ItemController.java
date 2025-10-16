package org.ntrloc.graph.ui;

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

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(ItemController.ITEM_PREFIX)
public class ItemController extends AbstractController {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaController.class);

    static final String ITEM_PREFIX = "/item";

    ItemController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                     @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                     ResourceLoader resourceLoader,
                     SchemaManager schemaManager) {
        super("item", resourceLocation, templateEngine, resourceLoader, schemaManager);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getItem(@PathVariable("id") String id) {
        //var selector = new IdSelector(id, IdSelector.Type.GLOBAL);
        //SelectableItemProjectionSpec query = new SelectableItemProjectionSpec(selector);
        //query.setItemSelector();

        Context context = new Context();
        var result = templateEngine.process("/item/index.html", context);
        return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
    }

}
