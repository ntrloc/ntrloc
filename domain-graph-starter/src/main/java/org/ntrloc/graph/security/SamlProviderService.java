package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository;
import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository.KeyRole;
import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository.SamlKeyRow;
import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository.SamlProviderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.saml2.core.Saml2X509Credential;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistration;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// The SAML identity providers people can sign in with, stored in the database and edited by admins
// (SamlProviderController), so a change applies without a restart. Also the
// RelyingPartyRegistrationRepository Spring's SAML login asks for a provider's settings, built from
// the IdP's metadata. Each provider gets its own key pair when it's created (SamlCertificates),
// for signing and decryption: the private key encrypted (SecretCipher) and never returned, the
// certificate published in ntrloc's metadata.
@Service
public class SamlProviderService implements RelyingPartyRegistrationRepository {

    private static final Logger LOG = LoggerFactory.getLogger(SamlProviderService.class);

    // ntrloc's own URLs for a provider. Its entity ID is its metadata URL, a common convention. The
    // single logout URL is shared: the signed-in user's session says which provider it's for.
    static final String METADATA_PATH = "/saml2/metadata/{registrationId}";
    static final String ACS_PATH = "/login/saml2/sso/{registrationId}";
    static final String SLO_PATH = "/logout/saml2/slo";
    private static final String METADATA_URI_SCHEME = "The metadata URI must start with http:// or https://";

    // emailAttribute blank: the NameID is the email. displayNameAttribute blank: no display name.
    public record SamlProvider(String registrationId, String displayName, String metadataUri, String emailAttribute,
                               String displayNameAttribute, boolean enabled) {

        SamlProvider trimmed() {
            return new SamlProvider(trim(registrationId), trim(displayName), trim(metadataUri), trim(emailAttribute),
                    trim(displayNameAttribute), enabled);
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }
    }

    // One of ntrloc's key pairs for a provider, as an admin sees it: never the private key.
    public record StoredKey(KeyRole role, Instant certificateExpires, String certificateSubject, OffsetDateTime createdAt) {}

    public record StoredSamlProvider(SamlProvider provider, List<StoredKey> keys, OffsetDateTime updatedAt) {}

    public record TestResult(boolean ok, String message) {}

    private record CachedRegistration(long version, RelyingPartyRegistration registration) {}

    private final SamlProviderRepository repository;
    private final SecretCipher cipher;
    private final Map<String, CachedRegistration> registrations = new ConcurrentHashMap<>();

    public SamlProviderService(SamlProviderRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public List<StoredSamlProvider> list() {
        return repository.findAll().stream().map(this::stored).toList();
    }

    public List<SamlProvider> enabledProviders() {
        return repository.findAll().stream().filter(SamlProviderRow::enabled).map(SamlProviderService::providerOf).toList();
    }

    // Whether signing out of ntrloc can also sign this provider's user out of the IdP: the IdP's
    // metadata has a single logout endpoint.
    public boolean supportsSingleLogout(String registrationId) {
        RelyingPartyRegistration registration = findByRegistrationId(registrationId);
        return registration != null && registration.getAssertingPartyMetadata().getSingleLogoutServiceLocation() != null;
    }

    // How a sign-in through this provider finds the person's email and name; empty if it's
    // disabled or gone.
    public Optional<SamlProvider> enabledProvider(String registrationId) {
        return repository.find(registrationId).filter(SamlProviderRow::enabled).map(SamlProviderService::providerOf);
    }

    @Transactional
    public StoredSamlProvider create(SamlProvider requested, UUID updatedBy) {
        SamlProvider provider = requested.trimmed();
        ProviderRegistrationIds.validate(provider.registrationId());
        validate(provider);
        if (!cipher.isConfigured()) {
            throw new IllegalArgumentException("The provider can't be saved: this server has no "
                    + "graph.security.secret-key to encrypt its signing key with");
        }
        try {
            repository.insert(rowOf(provider), updatedBy);
            insertKey(provider.registrationId(), KeyRole.ACTIVE, generated(provider.registrationId()));
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("A provider with the registration ID " + provider.registrationId()
                    + " already exists");
        }
        LOG.info("SAML provider {} created by user {}: {}", provider.registrationId(), updatedBy, provider.metadataUri());
        return stored(repository.find(provider.registrationId()).orElseThrow());
    }

    // The registration ID can't change; key pairs change through the key methods below.
    public StoredSamlProvider update(String registrationId, SamlProvider requested, UUID updatedBy) {
        repository.find(registrationId).orElseThrow(() -> new NoSuchProviderException(registrationId));
        SamlProvider provider = new SamlProvider(registrationId, requested.displayName(), requested.metadataUri(),
                requested.emailAttribute(), requested.displayNameAttribute(), requested.enabled()).trimmed();
        validate(provider);
        repository.update(rowOf(provider), updatedBy);
        LOG.info("SAML provider {} updated by user {}: {} {}", registrationId, updatedBy,
                provider.enabled() ? "enabled," : "disabled,", provider.metadataUri());
        return stored(repository.find(registrationId).orElseThrow());
    }

    // --- Key rotation: add a NEXT key, give the IdP ntrloc's updated metadata, activate it (the old
    // ACTIVE key becomes RETIRING, still decrypting), then retire the old one. ---

    // Generated when both PEMs are blank, otherwise the admin's own key pair.
    @Transactional
    public StoredSamlProvider addNextKey(String registrationId, String privateKeyPem, String certificatePem, UUID updatedBy) {
        requireProvider(registrationId);
        if (keyRow(registrationId, KeyRole.NEXT).isPresent()) {
            throw new IllegalArgumentException("There's already a next key: activate or cancel it first");
        }
        boolean upload = !isBlank(privateKeyPem) || !isBlank(certificatePem);
        SamlCertificates.SigningPair pair = upload
                ? SamlCertificates.uploaded(trimToEmpty(privateKeyPem), trimToEmpty(certificatePem))
                : generated(registrationId);
        insertKey(registrationId, KeyRole.NEXT, pair);
        repository.touch(registrationId, updatedBy);
        LOG.info("SAML provider {}: next key {} by user {}", registrationId, upload ? "uploaded" : "generated", updatedBy);
        return stored(requireProvider(registrationId));
    }

    @Transactional
    public StoredSamlProvider activateNextKey(String registrationId, UUID updatedBy) {
        requireProvider(registrationId);
        if (keyRow(registrationId, KeyRole.NEXT).isEmpty()) {
            throw new IllegalArgumentException("There's no next key to activate");
        }
        repository.deleteKey(registrationId, KeyRole.RETIRING);
        repository.moveKey(registrationId, KeyRole.ACTIVE, KeyRole.RETIRING);
        repository.moveKey(registrationId, KeyRole.NEXT, KeyRole.ACTIVE);
        repository.touch(registrationId, updatedBy);
        LOG.info("SAML provider {}: next key activated by user {}", registrationId, updatedBy);
        return stored(requireProvider(registrationId));
    }

    // Cancels a NEXT key, or retires a RETIRING one. The ACTIVE key is only ever replaced.
    @Transactional
    public StoredSamlProvider removeKey(String registrationId, KeyRole role, UUID updatedBy) {
        requireProvider(registrationId);
        if (role == KeyRole.ACTIVE) {
            throw new IllegalArgumentException("The active key can't be removed: activate a next key to replace it");
        }
        if (!repository.deleteKey(registrationId, role)) {
            throw new IllegalArgumentException("There's no " + role.name().toLowerCase() + " key");
        }
        repository.touch(registrationId, updatedBy);
        LOG.info("SAML provider {}: {} key removed by user {}", registrationId, role.name().toLowerCase(), updatedBy);
        return stored(requireProvider(registrationId));
    }

    public void delete(String registrationId, UUID deletedBy) {
        if (!repository.delete(registrationId)) {
            throw new NoSuchProviderException(registrationId);
        }
        registrations.remove(registrationId);
        LOG.info("SAML provider {} deleted by user {}", registrationId, deletedBy);
    }

    // Fetches the IdP's metadata, as a sign-in would. Nothing is saved.
    public TestResult test(String metadataUri) {
        String uri = metadataUri == null ? "" : metadataUri.trim();
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            return new TestResult(false, METADATA_URI_SCHEME);
        }
        try {
            RelyingPartyRegistration discovered = RelyingPartyRegistrations.fromMetadataLocation(uri)
                    .registrationId("test").build();
            return new TestResult(true, "Found " + discovered.getAssertingPartyMetadata().getEntityId()
                    + " (signs in at " + discovered.getAssertingPartyMetadata().getSingleSignOnServiceLocation() + ").");
        } catch (RuntimeException e) {
            return new TestResult(false, "Couldn't read SAML metadata from " + uri + ": " + e.getMessage());
        }
    }

    // Spring's SAML login calls this when a sign-in starts, when the IdP's response comes back, and
    // for ntrloc's metadata. The IdP's metadata is fetched once per saved version of a provider, on
    // whichever node first needs it -- which is how a save reaches every node in a cluster. A
    // disabled, deleted, or unreachable provider has no registration, so no one signs in through it.
    @Override
    public RelyingPartyRegistration findByRegistrationId(String registrationId) {
        SamlProviderRow row = repository.find(registrationId).filter(SamlProviderRow::enabled).orElse(null);
        if (row == null) {
            registrations.remove(registrationId);
            return null;
        }
        CachedRegistration cached = registrations.get(registrationId);
        if (cached != null && cached.version() == row.version()) {
            return cached.registration();
        }
        try {
            // ntrloc signs with its ACTIVE key -- the first signing credential, keys sorting ACTIVE,
            // NEXT, RETIRING. Its metadata also offers the NEXT key, so an IdP can learn it before
            // it's activated, and it decrypts with every key it has, since the IdP may encrypt to
            // any certificate it was given. Encryption is the IdP's choice.
            List<Saml2X509Credential> signing = new ArrayList<>();
            List<Saml2X509Credential> decryption = new ArrayList<>();
            List<SamlKeyRow> keys = repository.findKeys(registrationId).stream()
                    .sorted(Comparator.comparing(SamlKeyRow::role))
                    .toList();
            for (SamlKeyRow key : keys) {
                PrivateKey privateKey = SamlCertificates.privateKey(cipher.decrypt(key.privateKeyEncrypted()));
                X509Certificate certificate = SamlCertificates.certificate(key.certificate());
                if (key.role() != KeyRole.RETIRING) {
                    signing.add(Saml2X509Credential.signing(privateKey, certificate));
                }
                decryption.add(Saml2X509Credential.decryption(privateKey, certificate));
            }
            RelyingPartyRegistration registration = RelyingPartyRegistrations.fromMetadataLocation(row.metadataUri())
                    .registrationId(row.registrationId())
                    .entityId("{baseUrl}" + METADATA_PATH)
                    .assertionConsumerServiceLocation("{baseUrl}" + ACS_PATH)
                    .singleLogoutServiceLocation("{baseUrl}" + SLO_PATH)
                    .singleLogoutServiceResponseLocation("{baseUrl}" + SLO_PATH)
                    .signingX509Credentials(credentials -> credentials.addAll(signing))
                    .decryptionX509Credentials(credentials -> credentials.addAll(decryption))
                    .build();
            registrations.put(registrationId, new CachedRegistration(row.version(), registration));
            return registration;
        } catch (RuntimeException e) {
            LOG.warn("SAML provider {} can't be used: {}", registrationId, e.getMessage());
            return null;
        }
    }

    private static void validate(SamlProvider provider) {
        if (provider.displayName().isEmpty()) {
            throw new IllegalArgumentException("A display name is required");
        }
        if (!provider.metadataUri().startsWith("http://") && !provider.metadataUri().startsWith("https://")) {
            throw new IllegalArgumentException(METADATA_URI_SCHEME);
        }
    }

    private SamlProviderRow requireProvider(String registrationId) {
        return repository.find(registrationId).orElseThrow(() -> new NoSuchProviderException(registrationId));
    }

    private Optional<SamlKeyRow> keyRow(String registrationId, KeyRole role) {
        return repository.findKeys(registrationId).stream().filter(key -> key.role() == role).findFirst();
    }

    private static SamlCertificates.SigningPair generated(String registrationId) {
        return SamlCertificates.generate("ntrloc SAML " + registrationId);
    }

    private void insertKey(String registrationId, KeyRole role, SamlCertificates.SigningPair pair) {
        if (!cipher.isConfigured()) {
            throw new IllegalArgumentException("The key can't be saved: this server has no "
                    + "graph.security.secret-key to encrypt it with");
        }
        repository.insertKey(registrationId, role, cipher.encrypt(pair.privateKeyPem()), pair.certificatePem());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static SamlProviderRow rowOf(SamlProvider provider) {
        return new SamlProviderRow(provider.registrationId(), provider.displayName(), provider.metadataUri(),
                provider.emailAttribute(), provider.displayNameAttribute(), provider.enabled(), 0, null);
    }

    private static SamlProvider providerOf(SamlProviderRow row) {
        return new SamlProvider(row.registrationId(), row.displayName(), row.metadataUri(), row.emailAttribute(),
                row.displayNameAttribute(), row.enabled());
    }

    private StoredSamlProvider stored(SamlProviderRow row) {
        List<StoredKey> keys = repository.findKeys(row.registrationId()).stream()
                .map(key -> {
                    X509Certificate certificate = SamlCertificates.certificate(key.certificate());
                    return new StoredKey(key.role(), certificate.getNotAfter().toInstant(),
                            certificate.getSubjectX500Principal().getName(), key.createdAt());
                })
                .toList();
        return new StoredSamlProvider(providerOf(row), keys, row.updatedAt());
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    public static class NoSuchProviderException extends RuntimeException {
        NoSuchProviderException(String registrationId) {
            super("No SAML provider " + registrationId);
        }
    }
}
