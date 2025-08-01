package org.ntrloc.graph.web;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.junit.jupiter.api.Test;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.cache.StandardCacheManager;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.templateresource.ITemplateResource;
import org.thymeleaf.templateresource.StringTemplateResource;

import java.util.List;
import java.util.Map;

class ThymeleafTest {

    @Test
    void testCachedTemplate() {

        String layoutTemplateText = """
          
                <html>
                  <head>
                    <!--/*  Each token will be replaced by their respective titles in the resulting page. */-->
                    <title layout:title-pattern="$LAYOUT_TITLE - $CONTENT_TITLE">Task List</title>
                    ...
                  </head>
                  <body>
                    <!--/* Standard layout can be mixed with Layout Dialect */-->
                    <div>
                      ...
                    </div>
                    <div class="container">
                      <div layout:fragment="content"></div>
                    </div>
                  </body>
                </html>
                """;

        String templateText = """
            <html layout:decorate="~{layout}">
                <div layout:fragment="content">
                I'm content!
                </div>
            </html>
            """;

        StringTemplateResolver resolver = new StringTemplateResolver() {
            @Override
            protected ITemplateResource computeTemplateResource(IEngineConfiguration configuration, String ownerTemplate, String template, Map<String, Object> templateResolutionAttributes) {
                if (template.equals("test")) {
                    return new StringTemplateResource(templateText);
                } else if (template.equals("layout")) {
                    return new StringTemplateResource(layoutTemplateText);
                } else {
                    return null;
                }
            }
        };
        resolver.setCacheable(true);

        StandardCacheManager cacheManager = new StandardCacheManager();
        cacheManager.setTemplateCacheMaxSize(100);

        TemplateEngine engine = new TemplateEngine();
        engine.addDialect(new LayoutDialect());
        engine.setTemplateResolver(resolver);
        engine.setCacheManager(cacheManager);

        Context context = new Context();
        context.setVariable("name", "Thymeleaf User");
        context.setVariable("items", List.of("Apple", "Banana", "Orange"));

        var result = engine.process("test", context);
        System.out.println(result);


    }

}
