package org.ntrloc.graph.ai;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerSseProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcSseServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import tools.jackson.databind.json.JsonMapper;

// Overrides McpServerSseWebMvcAutoConfiguration's own transport-provider bean -- see
// McpStreamableTransportContextConfig's own comment for why this needs to be a separate class
// from that one (condition-phase mismatch if combined) and McpTransportContextConfig for the
// shared extraction logic. SSE, not Streamable, is what's actually active by default in this app
// today: confirmed by reading EnabledSseServerCondition/EnabledStreamableServerCondition
// directly -- SSE's own protocol check is matchIfMissing=true, Streamable's is
// matchIfMissing=false, and the application.yml never sets spring.ai.mcp.server.protocol.
// (McpServerSseWebMvcAutoConfiguration is itself @Deprecated(forRemoval=true) upstream, same as
// its WebFlux predecessor was, but still the one actually wired up until that default changes or
// the app opts into STREAMABLE explicitly -- this class needs to keep working for whichever one
// actually is active.)
//
// Also defines the router-function bean the original autoconfiguration would otherwise have
// provided -- same reasoning as the WebFlux version of this class had: its own
// @ConditionalOnMissingBean(McpServerTransportProvider.class) sits at the *class* level, so once
// this class's own transport-provider bean exists, Spring skips that original class *entirely*,
// including the router-function bean that actually mounts /sse and /mcp/message. Streamable's own
// autoconfiguration doesn't have this problem (no class-level @ConditionalOnMissingBean there,
// only on its own transport-provider bean method), so McpStreamableTransportContextConfig doesn't
// need the equivalent.
//
// One API difference from the WebFlux builder, confirmed by decompiling both: this builder's
// base-path setter is named baseUrl(String), not basePath(String) -- the field underneath is
// named baseUrl on both, but only the WebFlux builder exposes it as basePath().
@Configuration
@ConditionalOnClass(WebMvcSseServerTransportProvider.class)
@ConditionalOnMissingBean(McpServerTransportProvider.class)
@EnableConfigurationProperties(McpServerSseProperties.class)
@Conditional({ McpServerStdioDisabledCondition.class, McpServerAutoConfiguration.EnabledSseServerCondition.class })
public class McpSseTransportContextConfig {

    @Bean
    @ConditionalOnMissingBean
    public WebMvcSseServerTransportProvider webMvcTransport(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerSseProperties serverProperties) {
        return WebMvcSseServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .baseUrl(serverProperties.getBaseUrl())
                .messageEndpoint(serverProperties.getSseMessageEndpoint())
                .sseEndpoint(serverProperties.getSseEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .contextExtractor(McpTransportContextConfig::extractAuthorizationHeader)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(name = "webMvcSseServerRouterFunction")
    public RouterFunction<?> webMvcSseServerRouterFunction(WebMvcSseServerTransportProvider webMvcProvider) {
        return webMvcProvider.getRouterFunction();
    }
}
