package org.ntrloc.graph.ui;

import org.ntrloc.graph.db.ItemManager;
import org.ntrloc.graph.db.language.projection.ItemProjection;
import org.ntrloc.graph.db.language.projection.SelectableItemProjectionSpec;
import org.ntrloc.graph.db.language.selectors.IdSelector;
import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(ItemController.ITEM_PREFIX)
public class ItemController extends AbstractController {

    private static final Logger LOG = LoggerFactory.getLogger(SchemaController.class);

    static final String ITEM_PREFIX = "/item";

    private ItemManager itemManager;

    ItemController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                   @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                   ResourceLoader resourceLoader,
                   SchemaManager schemaManager,
                   ItemManager itemManager) {
        super("item", resourceLocation, templateEngine, resourceLoader, schemaManager);
        this.itemManager = itemManager;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Resource> getItem(@PathVariable("id") String id, ServerWebExchange exchange) {
        var selector = new IdSelector(id, IdSelector.Type.GLOBAL);
        SelectableItemProjectionSpec query = new SelectableItemProjectionSpec(selector);
        URI requestUri = exchange.getRequest().getURI().resolve("/binary/download");
        List<ItemProjection> projectionList = itemManager.executeProjection(query, requestUri);

        Context context = new Context();
        context.setVariable("item", projectionList.get(0));
        return processTemplate("/item/index.html", Map.of("item", projectionList.get(0)));
    }

}
