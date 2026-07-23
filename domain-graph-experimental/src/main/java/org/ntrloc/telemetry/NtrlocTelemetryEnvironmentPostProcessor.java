package org.ntrloc.telemetry;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

// opentelemetry-spring-boot-starter's own kill switch is otel.sdk.disabled -- inverted here so the
// project has one explicit, opt-in flag instead: telemetry is only ever transmitted when
// ntrloc.telemetry.enabled=true is set somewhere in the environment (a profile's application.yml,
// an env var, etc.), defaulting to off otherwise. Runs as an EnvironmentPostProcessor rather than a
// @Bean because it has to win *before* the OTel SDK autoconfiguration reads otel.sdk.disabled while
// building the SDK -- ordinary @Configuration beans run too late in the startup sequence for that.
// addFirst gives this property source the highest precedence, so this flag is the single source of
// truth for otel.sdk.disabled even if something else in the environment also sets it directly.
public class NtrlocTelemetryEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean enabled = environment.getProperty("ntrloc.telemetry.enabled", Boolean.class, false);
        environment.getPropertySources().addFirst(
                new MapPropertySource("ntrlocTelemetryGate", Map.of("otel.sdk.disabled", !enabled)));
    }
}
