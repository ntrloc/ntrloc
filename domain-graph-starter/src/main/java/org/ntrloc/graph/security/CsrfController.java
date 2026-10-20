package org.ntrloc.graph.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class CsrfController {

    private static final Logger LOG = LoggerFactory.getLogger(CsrfController.class);

    @GetMapping("/api/csrf")
    public Map<String, String> csrf(HttpServletRequest request) {
        try {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token == null) {
                return Map.of();
            }
            return Map.of(
                    "token", token.getToken(),
                    "header", token.getHeaderName(),
                    "parameter", token.getParameterName()
            );
        } catch (Exception e) {
            LOG.warn("Failed to resolve CSRF token", e);
            return Map.of();
        }
    }
}
