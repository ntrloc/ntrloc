package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/account")
public class AccountController {

    public record MeResponse(String username, String displayName, String email, boolean isSuperuser) {}

    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    private final SecurityRepository repo;
    private final PrincipalResolver principalResolver;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AccountController(SecurityRepository repo, PrincipalResolver principalResolver) {
        this.repo = repo;
        this.principalResolver = principalResolver;
    }

    @GetMapping("/me")
    public MeResponse me(ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);
        return new MeResponse(principal.externalId(), principal.displayName(), principal.email(), principal.isSuperuser());
    }

    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@RequestBody ChangePasswordRequest body,
                                                ServerHttpRequest request, Authentication authentication) {
        var principal = principalResolver.resolve(request, authentication);

        var credentials = repo.findCredentialsByEmail(principal.externalId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No local credentials for this account"));

        if (!encoder.matches(body.currentPassword(), credentials.passwordHash().replace("{bcrypt}", ""))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Current password is incorrect");
        }

        String newHash = "{bcrypt}" + encoder.encode(body.newPassword());
        repo.updatePasswordHash(principal.id(), newHash);
        return ResponseEntity.noContent().build();
    }
}
