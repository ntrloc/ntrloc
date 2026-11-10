package org.ntrloc.graph.draft;

import org.ntrloc.graph.draft.DraftService.DraftNotFoundException;
import org.ntrloc.graph.principal.ExternalPrincipalResolver;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;
import java.util.UUID;

// Resolves a DraftScope parameter from the request's {draftId}: the draft must be open and the user an
// admin or one of its participants. Anyone else gets a 404, the same as for a draft that doesn't exist.
@Component
public class DraftScopeResolver implements HandlerMethodArgumentResolver {

    private final DraftService draftService;
    private final ExternalPrincipalResolver principalResolver;

    public DraftScopeResolver(DraftService draftService, ExternalPrincipalResolver principalResolver) {
        this.draftService = draftService;
        this.principalResolver = principalResolver;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == DraftScope.class;
    }

    @Override
    public DraftScope resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        @SuppressWarnings("unchecked")
        var pathVariables = (Map<String, String>) webRequest.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        String draftId = pathVariables == null ? null : pathVariables.get("draftId");
        if (draftId == null) {
            throw new IllegalStateException(parameter.getMethod() + " takes a DraftScope but its path has no {draftId}");
        }
        UUID id;
        try {
            id = UUID.fromString(draftId);
        } catch (IllegalArgumentException e) {
            throw new DraftNotFoundException();
        }
        var user = principalResolver.resolve(SecurityContextHolder.getContext().getAuthentication());
        return new DraftScope(draftService.openDraft(id, user), user);
    }
}
