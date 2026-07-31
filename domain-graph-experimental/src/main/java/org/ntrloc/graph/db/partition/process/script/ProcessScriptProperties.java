package org.ntrloc.graph.db.partition.process.script;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// Packages a process script (Groovy or JavaScript, scriptFormat="groovy"/"javascript") can
// reference a class from by simple name alone -- new SingleItemProjectionSpec(...) instead of
// new org.ntrloc.graph.db.projection.SingleItemProjectionSpec(...). See
// ProcessScriptEngineFactory for how each language actually resolves against this list.
// Registered via @EnableConfigurationProperties on ProcessEngineConfig (its only consumer),
// not a bare @Component -- matching UiHostingProperties, this codebase's other record-shaped
// (as opposed to plain-class, like SecurityProperties) properties type. A record has no default
// constructor for ordinary component-scan instantiation to call before the binding
// post-processor gets a turn, so it needs the explicit constructor-binding registration path.
@ConfigurationProperties(prefix = "graph.process.script")
public record ProcessScriptProperties(List<String> importPackages) {

    public ProcessScriptProperties {
        importPackages = importPackages == null ? List.of() : importPackages;
    }
}
