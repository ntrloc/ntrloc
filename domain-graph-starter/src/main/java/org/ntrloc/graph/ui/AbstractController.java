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
import org.springframework.web.server.ServerWebExchange;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

import static org.ntrloc.graph.ui.AdminConfiguration.ADMIN_TEMPLATE;

public abstract class AbstractController {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractController.class);

    protected String requestPrefix;
    protected String resourceLocation;
    protected TemplateEngine templateEngine;
    protected ResourceLoader resourceLoader;
    protected SchemaManager schemaManager;

    protected AbstractController(String requestPrefix,
                                 @Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                                 @Qualifier(ADMIN_TEMPLATE) TemplateEngine templateEngine,
                                 ResourceLoader resourceLoader,
                                 SchemaManager schemaManager) {
        this.requestPrefix = requestPrefix;
        this.resourceLocation = resourceLocation;
        this.templateEngine = templateEngine;
        this.resourceLoader = resourceLoader;
        this.schemaManager = schemaManager;
    }

    @GetMapping("/**")
    public ResponseEntity<Resource> getResource(ServerWebExchange exchange) {
        var requestPath = exchange.getRequest().getPath().toString();
        var relPath = requestPath.substring(requestPrefix.length());
        if (relPath.isEmpty() || relPath.substring(1).isEmpty()) {
            return processTemplate(String.format("/%s/index.html", requestPrefix));
        } else {
            try {
                if (relPath.contains(".html")) { // html requests are processed as templates
                    return processTemplate(relPath);
                } else { // other files are returned as-is
                    return getResource(resourceLocation + relPath);
                }
            } catch (Exception er) {
                return ResponseEntity.notFound().build();
            }
        }
    }

    protected ResponseEntity<Resource> processTemplate(String templatePath, Map<String, Object> contextVariables) {
        Context context = new Context();
        context.setVariable("schemaManager", schemaManager);
        contextVariables.forEach(context::setVariable);
        var result = templateEngine.process(templatePath, context);
        return ResponseEntity.ok(new ByteArrayResource(result.getBytes()));
    }

    protected ResponseEntity<Resource> processTemplate(String templatePath) {
        return processTemplate(templatePath, Map.of());
    }

    protected ResponseEntity<Resource> getResource(String resourcePath) {
        return ResponseEntity.ok(resourceLoader.getResource(resourcePath));
    }

}
