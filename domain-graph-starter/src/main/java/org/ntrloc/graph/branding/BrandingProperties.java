package org.ntrloc.graph.branding;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "graph.branding")
public record BrandingProperties(@DefaultValue("domain-graph") String displayName) {
}
