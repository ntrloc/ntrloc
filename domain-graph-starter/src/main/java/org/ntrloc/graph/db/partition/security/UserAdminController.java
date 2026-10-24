package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
public class UserAdminController {

    public record UserView(UUID id, String displayName, String email, boolean isSuperuser) {}

    public record CreateUserRequest(String displayName, String email, String password, String role) {}

    public record UpdateUserRequest(String displayName, String email, String role) {}

    public record ResetPasswordRequest(String newPassword) {}

    public record CreateTokenRequest(String name, Integer expiresInDays) {}

    public record CreatedTokenResponse(UUID id, String name, String token, OffsetDateTime expiresAt) {}

    public record TokenSummaryResponse(UUID id, String name, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    private final SecurityRepository repo;
    private final PersonalAccessTokenService patService;
    private final PrincipalResolver principalResolver;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public UserAdminController(SecurityRepository repo, PersonalAccessTokenService patService,
                               PrincipalResolver principalResolver, DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.repo = repo;
        this.patService = patService;
        this.principalResolver = principalResolver;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    @GetMapping
    List<UserView> listUsers() {
        return repo.listUsers().stream()
                .map(u -> new UserView(u.id(), u.displayName(), u.email(), u.isSuperuser()))
                .toList();
    }

    @PostMapping
    UserView createUser(@RequestBody CreateUserRequest body, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can create users");
        }
        requireIdentityFields(body.displayName(), body.email());
        if (isBlank(body.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password is required");
        }
        if (repo.findUserByEmail(body.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use: " + body.email());
        }

        String role = body.role() == null ? "USER" : body.role().toUpperCase();
        boolean isSuperuser = "ADMIN".equals(role);
        var user = repo.createUser(body.displayName(), body.email(), isSuperuser);
        String passwordHash = "{bcrypt}" + encoder.encode(body.password());
        repo.createLocalCredentials(user.id(), passwordHash, role);
        defaultUserGroupInitializer.addUserToDefaultUserGroup(user.id());
        return new UserView(user.id(), user.displayName(), user.email(), user.isSuperuser());
    }

    // @Transactional: security_user and security_local_credentials' role must land together.
    @Transactional
    @PutMapping("/{userId}")
    UserView updateUser(@PathVariable("userId") UUID userId, @RequestBody UpdateUserRequest body,
                        Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can update users");
        }
        requireIdentityFields(body.displayName(), body.email());
        repo.findUserByEmail(body.email())
                .filter(existing -> !existing.id().equals(userId))
                .ifPresent(existing -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already in use: " + body.email());
                });
        String role = body.role() == null ? "USER" : body.role().toUpperCase();
        boolean isSuperuser = "ADMIN".equals(role);
        repo.updateUser(userId, body.displayName(), body.email(), isSuperuser);
        repo.updateLocalCredentialsRole(userId, role);
        return new UserView(userId, body.displayName(), body.email(), isSuperuser);
    }

    @DeleteMapping("/{userId}")
    ResponseEntity<Void> deleteUser(@PathVariable("userId") UUID userId, Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can delete users");
        }
        if (principal.id().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot delete your own account");
        }
        repo.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{userId}/password")
    ResponseEntity<Void> resetPassword(@PathVariable("userId") UUID userId, @RequestBody ResetPasswordRequest body,
                                        Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can reset passwords");
        }
        String passwordHash = "{bcrypt}" + encoder.encode(body.newPassword());
        repo.updatePasswordHash(userId, passwordHash);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{userId}/tokens")
    List<TokenSummaryResponse> listTokens(@PathVariable("userId") UUID userId,
                                           Authentication authentication) {
        requireAdmin(authentication);
        return patService.listForUser(userId).stream()
                .map(t -> new TokenSummaryResponse(t.id(), t.name(), t.createdAt(), t.expiresAt()))
                .toList();
    }

    @PostMapping("/{userId}/tokens")
    ResponseEntity<CreatedTokenResponse> createToken(@PathVariable("userId") UUID userId,
                                                      @RequestBody CreateTokenRequest body,
                                                      Authentication authentication) {
        requireAdmin(authentication);
        var issued = patService.issueForUser(userId, body.name(), body.expiresInDays());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new CreatedTokenResponse(issued.id(), issued.name(), issued.rawToken(), issued.expiresAt()));
    }

    @DeleteMapping("/{userId}/tokens/{tokenId}")
    ResponseEntity<Void> revokeToken(@PathVariable("userId") UUID userId, @PathVariable("tokenId") UUID tokenId,
                                      Authentication authentication) {
        requireAdmin(authentication);
        patService.revokeForUser(userId, tokenId);
        return ResponseEntity.noContent().build();
    }

    private static void requireIdentityFields(String displayName, String email) {
        if (isBlank(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email is required");
        }
        if (isBlank(displayName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Display name is required");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void requireAdmin(Authentication authentication) {
        var principal = principalResolver.resolve(authentication);
        if (!principal.isSuperuser()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins can manage tokens");
        }
    }
}
