package org.ntrloc.graph.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// Method-level principal enforcement is an invariant of each service, independent of the web
// filter chain -- it also guards calls that never pass through an HTTP request (process scripts,
// MCP tools), so it lives here rather than on SecurityConfig.
//
// The servlet variant, not the reactive one: confirmed empirically (see
// docs/ntrloc-principal-enforcement-plan.md, Part C) that @EnableReactiveMethodSecurity hard-fails
// on any method that doesn't return a Publisher, which every method in scope here does not. This
// app is also now fully on Spring MVC (see docs/webflux-to-mvc-migration-plan.md), so there is no
// longer any tension between "which annotation matches the web stack" and "which annotation
// actually works" the way there briefly was mid-migration.
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {
}
