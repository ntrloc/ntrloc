package org.ntrloc.graph.db.partition.binary;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

// BinaryController.upload reads the raw, unconsumed HttpServletRequest body itself (via
// JakartaServletFileUpload's streaming API -- see that dependency's own comment in pom.xml),
// which is fundamentally incompatible with Spring's own multipart resolution also trying to
// parse the same body: whichever runs first consumes the stream, leaving nothing for the other,
// and with multipart resolution left enabled (Spring Boot's own default), an upload larger than
// its default size limits (1MB file / 10MB request) gets rejected with a 413 before this
// controller ever sees it. Every application built on domain-graph-starter needs
// spring.servlet.multipart.enabled=false for binary upload to work AT ALL -- not a preference a
// consuming application might reasonably want to override, a hard requirement of how this is
// implemented here. Confirmed via a real production gap: ntrloc-dam's own uploads 413'ing because
// nothing told it to set this, since it's a per-application Spring Boot property that doesn't
// travel with this jar dependency on its own.
//
// Runs as an EnvironmentPostProcessor (registered in META-INF/spring.factories), same mechanism
// and same reasoning as NtrlocTelemetryEnvironmentPostProcessor: this has to win *before* Spring
// Boot's own MultipartAutoConfiguration reads this property while deciding whether to create a
// MultipartResolver bean at all -- an ordinary @Configuration bean (even one this module already
// registers) runs far too late in the startup sequence to influence that decision. addFirst gives
// this property source the highest precedence, so a consuming application can't silently break
// upload by setting this to true somewhere in its own configuration, by accident or otherwise.
public class BinaryUploadMultipartEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        environment.getPropertySources().addFirst(
                new MapPropertySource("domainGraphStarterBinaryUploadRequirement",
                        Map.of("spring.servlet.multipart.enabled", false)));
    }
}
