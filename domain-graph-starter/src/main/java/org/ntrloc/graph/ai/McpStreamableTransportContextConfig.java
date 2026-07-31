package org.ntrloc.graph.ai;

import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration;
import org.springframework.ai.mcp.server.common.autoconfigure.McpServerStdioDisabledCondition;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

// Overrides McpServerStreamableHttpWebFluxAutoConfiguration's own transport-provider bean (that
// one is @ConditionalOnMissingBean, exactly for this kind of extension) purely to add a
// contextExtractor -- see McpTransportContextConfig's own comment for why, and
// MutationService.executeMutation for where it actually gets used.
//
// A whole separate @Configuration class per transport (this one, and
// McpSseTransportContextConfig), not two @Bean methods on one shared class: each condition class
// here (McpServerStdioDisabledCondition, EnabledStreamableServerCondition) extends
// AllNestedConditions(ConfigurationPhase.PARSE_CONFIGURATION) -- confirmed by reading both
// directly -- which Spring only evaluates correctly as a *class-level* @Conditional (that's the
// phase at which a whole @Configuration class is decided as a parse candidate). Applying the same
// annotation at the @Bean *method* level instead runs it in the wrong phase and doesn't reliably
// exclude the other transport -- tried it, watched both this bean and the SSE one get created
// simultaneously in the exact same context, `NoUniqueBeanDefinitionException` for
// McpServerTransportProviderBase downstream. Splitting into separate classes, each conditional at
// the class level exactly like Spring AI's own two autoconfiguration classes, is what SSE's own
// belt-and-suspenders @ConditionalOnMissingBean(McpServerTransportProvider.class) additionally
// checks against (see McpSseTransportContextConfig) -- but getting the phase right here is what
// actually makes that check see the right thing.
@Configuration
@ConditionalOnClass(McpSchema.class)
@EnableConfigurationProperties({ McpServerProperties.class, McpServerStreamableHttpProperties.class })
@Conditional({ McpServerStdioDisabledCondition.class, McpServerAutoConfiguration.EnabledStreamableServerCondition.class })
public class McpStreamableTransportContextConfig {

    @Bean
    @ConditionalOnMissingBean
    public WebFluxStreamableServerTransportProvider webFluxStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") JsonMapper jsonMapper,
            McpServerStreamableHttpProperties serverProperties) {
        return WebFluxStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .messageEndpoint(serverProperties.getMcpEndpoint())
                .keepAliveInterval(serverProperties.getKeepAliveInterval())
                .disallowDelete(serverProperties.isDisallowDelete())
                .contextExtractor(McpTransportContextConfig::extractAuthorizationHeader)
                .build();
    }
}
