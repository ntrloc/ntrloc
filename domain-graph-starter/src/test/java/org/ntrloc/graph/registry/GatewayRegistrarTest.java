package org.ntrloc.graph.registry;

import com.hazelcast.cluster.Address;
import com.hazelcast.cluster.Member;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ntrloc.graph.cluster.ClusterService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("A gateway registrar")
class GatewayRegistrarTest {

    @Test
    @DisplayName("should register a domain instance with a gateway")
    void testRegisterDomainInstance() {
        Member clusterMember = mock(Member.class);
        ClusterService clusterService = mock(ClusterService.class);
        doReturn(clusterMember).when(clusterService).getLocalMember();
        Address address = Address.createUnresolvedAddress("127.0.0.1", 20000);
        doReturn(address).when(clusterMember).getAddress();
        doReturn(UUID.randomUUID()).when(clusterMember).getUuid();
        GatewayConfiguration configuration = new GatewayConfiguration();
        configuration.setRegistrationPath("/register");

        WebClient client = mock(WebClient.class);
        WebClient.RequestBodyUriSpec requestBodyUriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec requestHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        ClientResponse clientResponse = mock(ClientResponse.class);
        when(clientResponse.statusCode()).thenReturn(HttpStatusCode.valueOf(200));

        when(client.put()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.bodyValue(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.exchangeToMono(any())).thenAnswer(invocation -> {
            // Get the function passed to exchangeToMono
            Function<ClientResponse, Mono<String>> function = invocation.getArgument(0);
            return function.apply(clientResponse);
        });

        GatewayRegistrar registrar = new GatewayRegistrar("someDomain", 20000, clusterService, configuration, Optional.of(client));
        registrar.register();
        verify(client, atLeastOnce()).put();
    }

}
