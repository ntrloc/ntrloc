package org.ntrloc.graph.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

// Deliberately NOT on SecurityConfig, and deliberately NOT @ConditionalOnProperty: SecurityConfig
// is conditional on graph.security.enabled, which is false in tests and in permissive/local mode.
// Method-level principal enforcement must hold regardless of whether the *web* filter chain is
// active -- a service demanding a principal is an invariant of the service, not a deployment
// option. Putting this on SecurityConfig would silently drop every guard in exactly the
// configurations where a bypass is easiest.
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
