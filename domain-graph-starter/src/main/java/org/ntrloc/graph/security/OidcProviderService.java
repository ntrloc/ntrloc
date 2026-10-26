package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.repository.OidcProviderRepository;
import org.ntrloc.graph.db.partition.security.repository.OidcProviderRepository.OidcProviderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// The OpenID Connect providers people can sign in with, stored in the database and edited by admins
// (OidcProviderController), so a change applies without a restart. Also the ClientRegistrationRepository
// Spring's OAuth login asks for a provider's endpoints, built from the issuer's discovery document.
// Confidential clients only: the client secret is required, encrypted on save (SecretCipher), and
// never returned.
@Service
public class OidcProviderService implements ClientRegistrationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(OidcProviderService.class);

    private static final Set<String> REQUIRED_SCOPES = Set.of("openid", "email");
    private static final String ISSUER_URI_SCHEME = "The issuer URI must start with http:// or https://";

    // Everything but the client secret. scopes is space-separated.
    public record OidcProvider(String registrationId, String displayName, String issuerUri, String clientId,
                               String scopes, boolean enabled) {

        OidcProvider normalized() {
            return new OidcProvider(trim(registrationId), trim(displayName), trim(issuerUri), trim(clientId),
                    String.join(" ", scopeSet(scopes)), enabled);
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record StoredOidcProvider(OidcProvider provider, OffsetDateTime updatedAt) {}

    public record TestResult(boolean ok, String message) {}

    private record CachedRegistration(long version, ClientRegistration registration) {}

    private final OidcProviderRepository repository;
    private final SecretCipher cipher;
    private final Map<String, CachedRegistration> registrations = new ConcurrentHashMap<>();

    public OidcProviderService(OidcProviderRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public List<StoredOidcProvider> list() {
        return repository.findAll().stream().map(OidcProviderService::stored).toList();
    }

    public List<OidcProvider> enabledProviders() {
        return repository.findAll().stream().filter(OidcProviderRow::enabled).map(OidcProviderService::providerOf).toList();
    }

    public StoredOidcProvider create(OidcProvider requested, String clientSecret, UUID updatedBy) {
        OidcProvider provider = requested.normalized();
        validate(provider);
        ProviderRegistrationIds.validate(provider.registrationId());
        if (clientSecret == null || clientSecret.isEmpty()) {
            throw new IllegalArgumentException("A client secret is required");
        }
        try {
            repository.insert(rowOf(provider, encrypt(clientSecret)), updatedBy);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A provider with the registration ID " + provider.registrationId()
                    + " already exists");
        }
        LOG.info("OIDC provider {} created by user {}: {}", provider.registrationId(), updatedBy, provider.issuerUri());
        return stored(repository.find(provider.registrationId()).orElseThrow());
    }

    // A null or blank clientSecret keeps the stored one. The registration ID can't change.
    public StoredOidcProvider update(String registrationId, OidcProvider requested, String clientSecret, UUID updatedBy) {
        OidcProviderRow existing = repository.find(registrationId)
                .orElseThrow(() -> new NoSuchProviderException(registrationId));
        OidcProvider provider = new OidcProvider(registrationId, requested.displayName(), requested.issuerUri(),
                requested.clientId(), requested.scopes(), requested.enabled()).normalized();
        validate(provider);
        String secret = (clientSecret == null || clientSecret.isEmpty())
                ? existing.clientSecretEncrypted() : encrypt(clientSecret);
        repository.update(rowOf(provider, secret), updatedBy);
        LOG.info("OIDC provider {} updated by user {}: {} {}", registrationId, updatedBy,
                provider.enabled() ? "enabled," : "disabled,", provider.issuerUri());
        return stored(repository.find(registrationId).orElseThrow());
    }

    public void delete(String registrationId, UUID deletedBy) {
        if (!repository.delete(registrationId)) {
            throw new NoSuchProviderException(registrationId);
        }
        registrations.remove(registrationId);
        LOG.info("OIDC provider {} deleted by user {}", registrationId, deletedBy);
    }

    // Fetches the issuer's discovery document, as a login would. Client ID and secret can only be
    // checked by a real sign-in. Nothing is saved.
    public TestResult test(String issuerUri) {
        String issuer = issuerUri == null ? "" : issuerUri.trim();
        if (!issuer.startsWith("http://") && !issuer.startsWith("https://")) {
            return new TestResult(false, ISSUER_URI_SCHEME);
        }
        try {
            ClientRegistration discovered = ClientRegistrations.fromIssuerLocation(issuer).clientId("test").build();
            return new TestResult(true, "Found " + issuer + " (signs in at "
                    + discovered.getProviderDetails().getAuthorizationUri() + ").");
        } catch (RuntimeException e) {
            return new TestResult(false, "Couldn't read " + issuer + "/.well-known/openid-configuration: "
                    + e.getMessage());
        }
    }

    // Spring's OAuth login calls this at both ends of a sign-in: the redirect to the provider, and
    // the callback. The discovery document is fetched once per saved version of a provider, on
    // whichever node first needs it -- which is how a save reaches every node in a cluster. A
    // disabled, deleted, or unreachable provider has no registration, so no one signs in through it.
    @Override
    public ClientRegistration findByRegistrationId(String registrationId) {
        OidcProviderRow row = repository.find(registrationId).filter(OidcProviderRow::enabled).orElse(null);
        if (row == null) {
            registrations.remove(registrationId);
            return null;
        }
        CachedRegistration cached = registrations.get(registrationId);
        if (cached != null && cached.version() == row.version()) {
            return cached.registration();
        }
        try {
            ClientRegistration registration = ClientRegistrations.fromIssuerLocation(row.issuerUri())
                    .registrationId(row.registrationId())
                    .clientName(row.displayName())
                    .clientId(row.clientId())
                    .clientSecret(cipher.decrypt(row.clientSecretEncrypted()))
                    .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .scope(scopeSet(row.scopes()))
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .build();
            registrations.put(registrationId, new CachedRegistration(row.version(), registration));
            return registration;
        } catch (RuntimeException e) {
            LOG.warn("OIDC provider {} can't be used: {}", registrationId, e.getMessage());
            return null;
        }
    }

    private String encrypt(String clientSecret) {
        if (!cipher.isConfigured()) {
            throw new IllegalArgumentException("The client secret can't be saved: this server has no "
                    + "graph.security.secret-key to encrypt it with");
        }
        return cipher.encrypt(clientSecret);
    }

    private static void validate(OidcProvider provider) {
        if (provider.displayName().isEmpty()) {
            throw new IllegalArgumentException("A display name is required");
        }
        if (!provider.issuerUri().startsWith("http://") && !provider.issuerUri().startsWith("https://")) {
            throw new IllegalArgumentException(ISSUER_URI_SCHEME);
        }
        if (provider.clientId().isEmpty()) {
            throw new IllegalArgumentException("A client ID is required");
        }
        if (!scopeSet(provider.scopes()).containsAll(REQUIRED_SCOPES)) {
            throw new IllegalArgumentException("The scopes must include openid and email");
        }
    }

    private static Set<String> scopeSet(String scopes) {
        Set<String> set = new LinkedHashSet<>();
        if (scopes != null) {
            Arrays.stream(scopes.split("[\\s,]+")).filter(scope -> !scope.isEmpty()).forEach(set::add);
        }
        return set;
    }

    private static OidcProviderRow rowOf(OidcProvider provider, String clientSecretEncrypted) {
        return new OidcProviderRow(provider.registrationId(), provider.displayName(), provider.issuerUri(),
                provider.clientId(), clientSecretEncrypted, provider.scopes(), provider.enabled(), 0, null);
    }

    private static OidcProvider providerOf(OidcProviderRow row) {
        return new OidcProvider(row.registrationId(), row.displayName(), row.issuerUri(), row.clientId(),
                row.scopes(), row.enabled());
    }

    private static StoredOidcProvider stored(OidcProviderRow row) {
        return new StoredOidcProvider(providerOf(row), row.updatedAt());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NoSuchProviderException extends RuntimeException {
        NoSuchProviderException(String registrationId) {
            super("No OIDC provider " + registrationId);
        }
    }
}
