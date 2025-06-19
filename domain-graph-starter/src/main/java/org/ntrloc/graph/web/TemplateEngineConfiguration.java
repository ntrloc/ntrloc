package org.ntrloc.graph.web;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.ResourceCodeResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class TemplateEngineConfiguration {

    @Bean
    @Primary
    public TemplateEngine templateEngine() {
        CodeResolver codeResolver = new ResourceCodeResolver("templates");
        return TemplateEngine.create(codeResolver, ContentType.Html); // Two choices: Plain or Html
    }

}
