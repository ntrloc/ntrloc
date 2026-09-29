package org.ntrloc.ui.hosting;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.config.ResourceHandlerRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.server.WebFilter;

@Configuration
@EnableConfigurationProperties(UiHostingProperties.class)
public class UiHostingConfiguration implements WebFluxConfigurer {

    private final UiHostingProperties properties;

    public UiHostingConfiguration(UiHostingProperties properties) {
        this.properties = properties;
    }

    @Bean
    WebFilter mountWelcomePageFilter() {
        return new MountWelcomePageFilter(properties);
    }

    @Bean
    WebFilter staticResourceOffloadFilter() {
        return new StaticResourceOffloadFilter(properties);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        for (UiHostingProperties.Mount mount : properties.mounts()) {
            String path = mount.path().endsWith("/") ? mount.path() : mount.path() + "/";
            String location = mount.location().endsWith("/") ? mount.location() : mount.location() + "/";
            registry.addResourceHandler(path + "**")
                    .addResourceLocations(location)
                    .resourceChain(true)
                    .addResolver(new SpaFallbackResourceResolver());
        }

        // Plain static serving for AdminUiExtensionScanner's discoveries -- no SPA fallback here,
        // unlike the mounts above: an extension bundle is a single file loaded into the core admin
        // UI's own page, never its own standalone page with client-side sub-routes of its own.
        // classpath: (not classpath*:) still aggregates across every JAR for an ordinary resource
        // lookup like this -- only a wildcard *pattern* scan (AdminUiExtensionScanner's own) needs
        // the classpath*: form.
        registry.addResourceHandler("/admin-ui-extensions/**")
                .addResourceLocations("classpath:/static/admin-ui-extensions/");
    }
}
