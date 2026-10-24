package org.ntrloc.graph.db.partition.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pat")
public class PersonalAccessTokenController {

    public record CreateTokenRequest(String name, Integer expiresInDays) {}

    public record CreatedTokenResponse(UUID id, String name, String token, OffsetDateTime expiresAt) {}

    public record TokenSummaryResponse(UUID id, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    private final PersonalAccessTokenService tokenService;
    private final ExternalPrincipalResolver principalResolver;

    public PersonalAccessTokenController(PersonalAccessTokenService tokenService, ExternalPrincipalResolver principalResolver) {
        this.tokenService = tokenService;
        this.principalResolver = principalResolver;
    }

    @PostMapping
    public ResponseEntity<CreatedTokenResponse> create(@RequestBody CreateTokenRequest body, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        var issued = tokenService.issue(principal, body.name(), body.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedTokenResponse(issued.id(), issued.name(), issued.rawToken(), issued.expiresAt()));
    }

    @GetMapping
    public List<TokenSummaryResponse> list(Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        return tokenService.list(principal).stream()
                .map(t -> new TokenSummaryResponse(t.id(), t.name(), t.createdAt(), t.expiresAt()))
                .toList();
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> revoke(@PathVariable("tokenId") UUID tokenId, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        tokenService.revoke(principal, tokenId);
        return ResponseEntity.noContent().build();
    }
}
