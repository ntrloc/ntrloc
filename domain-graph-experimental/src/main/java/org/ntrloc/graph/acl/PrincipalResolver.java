package org.ntrloc.graph.acl;

import org.ntrloc.graph.acl.repository.AclRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class PrincipalResolver {

    private final AclRepository repo;
    private final AclProperties properties;

    public PrincipalResolver(AclRepository repo, AclProperties properties) {
        this.repo = repo;
        this.properties = properties;
    }

    public NtrlocPrincipal resolve(ServerHttpRequest request) {
        String externalId = request.getHeaders().getFirst(properties.getHeaderName());
        if (externalId == null || externalId.isBlank()) {
            List<String> params = request.getQueryParams().get(properties.getParamName());
            externalId = (params == null || params.isEmpty()) ? null : params.get(0);
        }
        if (externalId == null || externalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Missing stand-in principal: supply '%s' header or '%s' query param"
                            .formatted(properties.getHeaderName(), properties.getParamName()));
        }
        String resolvedId = externalId;
        var user = repo.findUserByExternalId(resolvedId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Unknown stand-in principal: " + resolvedId));
        Set<UUID> groupIds = repo.getGroupIdsForUser(user.id());
        return new ResolvedPrincipal(user.id(), user.externalId(), user.displayName(), groupIds);
    }
}
