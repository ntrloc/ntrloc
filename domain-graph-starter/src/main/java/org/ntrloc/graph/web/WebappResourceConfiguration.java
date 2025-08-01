package org.ntrloc.graph.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;

@Configuration
public class WebappResourceConfiguration {

    @Bean
    public WebFluxConfigurer configurer() {
        return new WebFluxConfigurer() {
            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
               registry.addResourceHandler("/webapp/**")
                       .resourceChain(true)
                       .addResolver(new WebappResourceResolver());
            }
        };

    }

}
