package org.ntrloc.graph.ui;

import nz.net.ultraq.thymeleaf.layoutdialect.LayoutDialect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

@Configuration
public class AdminConfiguration {

    static final String ADMIN_TEMPLATE = "adminTemplate";

    private final String resourceLocation;
    private final ApplicationContext applicationContext;

    AdminConfiguration(@Value("${graph.management.resource.location:classpath:/org/ntrloc/graph/ui/}") String resourceLocation,
                       ApplicationContext applicationContext) {
        this.resourceLocation = resourceLocation;
        this.applicationContext = applicationContext;
    }

    @Bean(ADMIN_TEMPLATE)
    SpringTemplateEngine customTemplateEngine() {
        SpringResourceTemplateResolver resourceTemplateResolver = new SpringResourceTemplateResolver();
        resourceTemplateResolver.setApplicationContext(applicationContext);
        resourceTemplateResolver.setPrefix(resourceLocation);
        resourceTemplateResolver.setTemplateMode("HTML5");
        resourceTemplateResolver.setCharacterEncoding("UTF-8");
        resourceTemplateResolver.setCacheable(resourceLocation.startsWith("classpath:"));

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(resourceTemplateResolver);
        templateEngine.setEnableSpringELCompiler(true);
        templateEngine.addDialect(new LayoutDialect());
        return templateEngine;
    }



}
