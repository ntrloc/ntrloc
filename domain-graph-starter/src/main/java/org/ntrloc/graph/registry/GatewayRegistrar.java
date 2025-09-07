package org.ntrloc.graph.registry;

import com.hazelcast.cluster.Member;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.gateway.api.DomainInstanceInfo;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Service
@ConditionalOnBean(GatewayConfiguration.class)
public class GatewayRegistrar {

    private static final Logger LOG = LogManager.getLogger(GatewayRegistrar.class);

    private final String domainName;

    private final int serverPort;

    private final ClusterService clusterService;

    private final GatewayConfiguration gatewayConfiguration;

    private boolean registrationEnabled = false;

    private WebClient webClient;

    public GatewayRegistrar(
            @Value("${graph.domainName}") String domainName,
            @Value("${server.port}") int port,
            ClusterService clusterService,
            GatewayConfiguration gatewayConfiguration,
            Optional<WebClient> webClientOptional) {
        this.domainName = domainName;
        this.serverPort = port;
        this.clusterService = clusterService;
        this.gatewayConfiguration = gatewayConfiguration;
        webClientOptional.ifPresent(client -> this.webClient = client);
    }

    @Scheduled(initialDelayString = "#{@gatewayConfiguration.renewalRateMilliseconds}", fixedRateString = "#{@gatewayConfiguration.renewalRateMilliseconds}")
    public void executeScheduledRenewal() {
        register();
    }

    public void register() {
        registrationEnabled = true;
        renewRegistration();
    }

    private void renewRegistration() {
        if (registrationEnabled) {
            Member member = clusterService.getLocalMember();

            DomainInstanceInfo info = new DomainInstanceInfo();
            info.setDomainName(domainName);
            info.setHost(member.getAddress().getHost());
            info.setPort(serverPort);
            info.setInstanceId(member.getUuid().toString());

            LOG.info("Registering domain instance {} using gateway configuration {}", info, gatewayConfiguration);

            if (webClient == null) {
                webClient = WebClient.create(String.format("%s://%s:%d/%s",
                        gatewayConfiguration.getScheme(), gatewayConfiguration.getHostname(), gatewayConfiguration.getPort(),
                        gatewayConfiguration.getRegistrationPath()));
            }
            webClient.put().bodyValue(info).exchangeToMono(response -> {
                LOG.info("Received response {} from gateway", response.statusCode());
                return Mono.empty();
            }).subscribe();
        }
    }

}
