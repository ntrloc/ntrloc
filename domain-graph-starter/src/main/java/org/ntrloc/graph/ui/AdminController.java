package org.ntrloc.graph.ui;

import org.ntrloc.graph.db.schema.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

@RestController
@RequestMapping(AdminController.ADMIN_PATH)
public class AdminController {

    private static final String ADMIN_PREFIX = "/admin";
    public static final String ADMIN_PATH = ADMIN_PREFIX + "/**";

    private static final Logger LOG = LoggerFactory.getLogger(AdminController.class);

    private String resourceLocation;
    private TemplateEngine templateEngine;
    private ResourceLoader resourceLoader;
    private SchemaManager schemaManager;

    private AdminController(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                            @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                            ResourceLoader resourceLoader,
                            SchemaManager schemaManager) {
        this.resourceLocation = resourceLocation;
        this.templateEngine = templateEngine;
        this.resourceLoader = resourceLoader;
        this.schemaManager = schemaManager;
    }

    @GetMapping
    public ResponseEntity<Resource> index(ServerWebExchange exchange) {
        var requestPath = exchange.getRequest().getPath();
        String path = requestPath.toString().substring(ADMIN_PREFIX.length());
        if (path.isEmpty()) {
            var headers = new HttpHeaders();
            headers.add("Location", String.format("%s/index.html", ADMIN_PREFIX)); // The URL to redirect to
            return new ResponseEntity<>(headers, HttpStatus.MOVED_PERMANENTLY);
        }

        try {
            if (path.endsWith(".html")) {
                Context context = new Context();
                context.setVariable("base", String.format("%s/", ADMIN_PREFIX));
                context.setVariable("schemaManager", schemaManager);
                var result = templateEngine.process(path, context);
                return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
            } else {
                return ResponseEntity.ok(resourceLoader.getResource(resourceLocation + path));
            }
        } catch (Exception er) {
            return ResponseEntity.ok(resourceLoader.getResource(resourceLocation + path));
        }

    }

}
