package org.ntrloc.gateway.registry.impl;

import com.netflix.graphql.dgs.client.GraphQLResponse;
import com.netflix.graphql.dgs.client.MonoGraphQLClient;
import com.netflix.graphql.dgs.client.WebClientGraphQLClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ntrloc.gateway.api.DomainInstanceInfo;
import org.ntrloc.gateway.api.GraphQlSchemaData;
import org.ntrloc.gateway.registry.DomainRegistry;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class DomainRegistryImpl implements DomainRegistry {

    private static final Logger LOG = LogManager.getLogger(DomainRegistryImpl.class);

    @Override
    public void register(DomainInstanceInfo info) {
        LOG.info("Registering {}", info);
        loadDomainSchema(info);
    }

    private void loadDomainSchema(DomainInstanceInfo info) {
        String requestPath = String.format("http://%s:%d/%s", info.getHost(), info.getPort(), info.getSchemaUri());
        LOG.info("Loading domain schema from {}", requestPath);
        WebClient webClient = WebClient.create(requestPath);
        WebClientGraphQLClient client = MonoGraphQLClient.createWithWebClient(webClient);

        var query = """
                    query IntrospectionQuery {
                       __schema {
                         queryType { name }
                         mutationType { name }
                         subscriptionType { name }
                         types { ...FullType }
                         directives {
                           name
                           description
                           locations
                           args { ...InputValue }
                         }
                       }
                     }
                
                     fragment FullType on __Type {
                       kind
                       name
                       description
                       fields(includeDeprecated: true) {
                         name
                         description
                         args { ...InputValue }
                         type { ...TypeRef }
                         isDeprecated
                         deprecationReason
                       }
                       inputFields { ...InputValue }
                       interfaces { ...TypeRef }
                       enumValues(includeDeprecated: true) {
                         name
                         description
                         isDeprecated
                         deprecationReason
                       }
                       possibleTypes { ...TypeRef }
                     }
                
                     fragment InputValue on __InputValue {
                       name
                       description
                       type { ...TypeRef }
                       defaultValue
                     }
                
                     fragment TypeRef on __Type {
                       kind
                       name
                       ofType {
                         kind
                         name
                         ofType {
                           kind
                           name
                           ofType {
                             kind
                             name
                             ofType {
                               kind
                               name
                               ofType {
                                 kind
                                 name
                                 ofType {
                                   kind
                                   name
                                   ofType {
                                     kind
                                     name
                                     ofType {
                                       kind
                                       name
                                       ofType {
                                         kind
                                         name
                                       }
                                     }
                                   }
                                 }
                               }
                             }
                           }
                         }
                       }
                     }
                """;

        //The GraphQLResponse contains data and errors.
        Mono<GraphQLResponse> graphQLResponseMono = client.reactiveExecuteQuery(query);
        graphQLResponseMono.subscribe(response -> {
            LOG.info("Got introspection response {}", response);
            GraphQlSchemaData schemaData = response.dataAsObject(GraphQlSchemaData.class);
            LOG.info("Got schema data {}", schemaData);
        });
    }

}
