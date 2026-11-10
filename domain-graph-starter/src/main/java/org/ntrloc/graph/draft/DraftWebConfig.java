package org.ntrloc.graph.draft;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class DraftWebConfig implements WebMvcConfigurer {

    private final DraftScopeResolver draftScopeResolver;

    public DraftWebConfig(DraftScopeResolver draftScopeResolver) {
        this.draftScopeResolver = draftScopeResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(draftScopeResolver);
    }
}
