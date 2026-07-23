package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository.PersonalAccessTokenRow;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class PersonalAccessTokenService {

    private static final String TOKEN_PREFIX = "ntrloc_pat_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecurityRepository repo;

    public PersonalAccessTokenService(SecurityRepository repo) {
        this.repo = repo;
    }

    /** The raw token is included here and nowhere else — it's never stored, only its hash is. */
    public record IssuedToken(UUID id, String name, String rawToken, OffsetDateTime expiresAt) {}

    public IssuedToken issue(NtrlocPrincipal principal, String name, Integer expiresInDays) {
        String rawToken = generateToken();
        OffsetDateTime expiresAt = expiresInDays == null ? null : OffsetDateTime.now().plusDays(expiresInDays);
        UUID id = repo.createPersonalAccessToken(principal.id(), hash(rawToken), name, expiresAt);
        return new IssuedToken(id, name, rawToken, expiresAt);
    }

    public List<PersonalAccessTokenRow> list(NtrlocPrincipal principal) {
        return repo.listTokensForUser(principal.id());
    }

    public void revoke(NtrlocPrincipal principal, UUID tokenId) {
        repo.revokeToken(tokenId, principal.id());
    }

    // Returns the full NtrlocPrincipal, not just the UserRow -- SecurityConfig's PAT
    // authentication manager sets this directly as the resulting Authentication's principal
    // object, the same "already-resolved, no second SecurityRepository round-trip needed" shape
    // NtrlocUserDetails gives local-credential logins (see that class's own comment, and
    // PrincipalResolver.resolveFromAuthenticatedSession's fast path).
    /** Resolves a presented raw token to its owning user's full principal, if the token is valid and hasn't expired. */
    public Optional<NtrlocPrincipal> authenticate(String rawToken) {
        return repo.findUserByValidTokenHash(hash(rawToken))
                .map(user -> new ResolvedPrincipal(
                        user.id(), user.externalId(), user.displayName(),
                        repo.getGroupIdsForUser(user.id()), user.isSuperuser()));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
