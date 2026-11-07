package org.ntrloc.graph.db.partition.security;

import org.ntrloc.graph.principal.NtrlocPrincipal;

import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserStore.PersonalAccessTokenRow;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class PersonalAccessTokenService {

    private static final String TOKEN_PREFIX = "";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserStore store;

    public PersonalAccessTokenService(UserStore store) {
        this.store = store;
    }

    /** The raw token is included here and nowhere else — it's never stored, only its hash is. */
    public record IssuedToken(UUID id, String name, String rawToken, OffsetDateTime expiresAt) {}

    public IssuedToken issue(NtrlocPrincipal principal, String name, Integer expiresInDays) {
        String rawToken = generateToken();
        OffsetDateTime expiresAt = expiresInDays == null ? null : OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays);
        UUID id = store.createPersonalAccessToken(principal.id(), hash(rawToken), name, expiresAt);
        return new IssuedToken(id, name, rawToken, expiresAt);
    }

    public List<PersonalAccessTokenRow> list(NtrlocPrincipal principal) {
        return store.listTokensForUser(principal.id());
    }

    public List<PersonalAccessTokenRow> listForUser(UUID userId) {
        return store.listTokensForUser(userId);
    }

    public IssuedToken issueForUser(UUID userId, String name, Integer expiresInDays) {
        String rawToken = generateToken();
        OffsetDateTime expiresAt = expiresInDays == null ? null : OffsetDateTime.now(ZoneOffset.UTC).plusDays(expiresInDays);
        UUID id = store.createPersonalAccessToken(userId, hash(rawToken), name, expiresAt);
        return new IssuedToken(id, name, rawToken, expiresAt);
    }

    public void revokeForUser(UUID userId, UUID tokenId) {
        store.revokeToken(tokenId, userId);
    }

    public void revoke(NtrlocPrincipal principal, UUID tokenId) {
        store.revokeToken(tokenId, principal.id());
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    // Package-private: ExternalPrincipalResolver.byToken looks a presented token up by this hash.
    public static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
