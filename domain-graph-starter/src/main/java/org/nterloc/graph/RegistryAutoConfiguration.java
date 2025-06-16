package org.nterloc.graph;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ComponentScan("org.nterloc.graph.registry")
@EnableScheduling
public class RegistryAutoConfiguration {
}
