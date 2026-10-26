package org.ntrloc.graph.security;

import org.ntrloc.graph.db.partition.security.repository.LdapConfigurationRepository;
import org.ntrloc.graph.db.partition.security.repository.LdapConfigurationRepository.LdapConfigurationRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.CommunicationException;
import org.springframework.ldap.NameNotFoundException;
import org.springframework.ldap.NamingException;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.security.ldap.authentication.BindAuthenticator;
import org.springframework.security.ldap.authentication.LdapAuthenticator;
import org.springframework.security.ldap.search.FilterBasedLdapUserSearch;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

// The LDAP directory ntrloc signs people in against, stored in the database and edited by admins
// (LdapConfigurationController), so a change applies without a restart. The bind password is
// write-only: it's encrypted on save (SecretCipher), and nothing here ever returns it.
@Service
public class LdapConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(LdapConfigurationService.class);

    // A directory that doesn't answer must fail a login quickly, not hang it: an admin can save a
    // wrong URL, and local login (tried after LDAP) is the way back in.
    private static final Map<String, Object> TIMEOUTS = Map.of(
            "com.sun.jndi.ldap.connect.timeout", "5000",
            "com.sun.jndi.ldap.read.timeout", "10000");

    // Everything but the bind password.
    public record LdapSettings(boolean enabled, String url, String baseDn, String bindDn, String userSearchBase,
                               String userSearchFilter, String emailAttribute, String displayNameAttribute) {

        static final LdapSettings DEFAULTS =
                new LdapSettings(false, "", "", "", "ou=people", "(uid={0})", "mail", "displayName");

        LdapSettings trimmed() {
            return new LdapSettings(enabled, trim(url), trim(baseDn), trim(bindDn), trim(userSearchBase),
                    trim(userSearchFilter), trim(emailAttribute), trim(displayNameAttribute));
        }

        boolean anonymous() {
            return bindDn.isEmpty();
        }

        private static String trim(String value) {
            return value == null ? "" : value.trim();
        }
    }

    public record StoredLdapSettings(LdapSettings settings, boolean bindPasswordSet, OffsetDateTime updatedAt) {}

    public record TestResult(boolean ok, String message) {}

    // The directory logins currently go to, ready to authenticate against.
    public record Directory(LdapSettings settings, LdapAuthenticator authenticator) {}

    private record CachedDirectory(long version, Optional<Directory> directory) {}

    private final LdapConfigurationRepository repository;
    private final SecretCipher cipher;
    private final AtomicReference<CachedDirectory> cached = new AtomicReference<>();

    public LdapConfigurationService(LdapConfigurationRepository repository, SecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    public StoredLdapSettings stored() {
        return repository.find()
                .map(row -> new StoredLdapSettings(settingsOf(row), row.bindPasswordEncrypted() != null, row.updatedAt()))
                .orElse(new StoredLdapSettings(LdapSettings.DEFAULTS, false, null));
    }

    // A null or blank newPassword keeps the stored one; an anonymous (blank) bind DN clears it.
    // Disabled settings are saved without validation, so an admin can save a half-finished
    // configuration and come back to it.
    public StoredLdapSettings save(LdapSettings requested, String newPassword, UUID updatedBy) {
        LdapSettings settings = requested.trimmed();
        if (settings.enabled()) {
            validate(settings);
        }
        String encryptedPassword;
        if (settings.anonymous()) {
            encryptedPassword = null;
        } else if (newPassword == null || newPassword.isEmpty()) {
            encryptedPassword = repository.find().map(LdapConfigurationRow::bindPasswordEncrypted).orElse(null);
        } else if (!cipher.isConfigured()) {
            throw new IllegalArgumentException("The bind password can't be saved: this server has no "
                    + "graph.security.secret-key to encrypt it with");
        } else {
            encryptedPassword = cipher.encrypt(newPassword);
        }
        if (settings.enabled() && !settings.anonymous() && encryptedPassword == null) {
            throw new IllegalArgumentException("A bind DN needs a password (or clear the bind DN to search anonymously)");
        }

        repository.save(new LdapConfigurationRow(settings.enabled(), settings.url(), settings.baseDn(),
                settings.bindDn(), encryptedPassword, settings.userSearchBase(), settings.userSearchFilter(),
                settings.emailAttribute(), settings.displayNameAttribute(), 0, null, null), updatedBy);
        LOG.info("LDAP configuration saved by user {}: {} {}", updatedBy, settings.enabled() ? "enabled," : "disabled,",
                settings.url());
        return stored();
    }

    // Binds with the given settings (the stored password, unless a new one is given) and looks up the
    // user search base -- what every login needs to work. Nothing is saved.
    public TestResult test(LdapSettings requested, String newPassword) {
        LdapSettings settings = requested.trimmed();
        try {
            validate(settings);
        } catch (IllegalArgumentException e) {
            return new TestResult(false, e.getMessage());
        }
        String password = null;
        if (!settings.anonymous()) {
            try {
                password = (newPassword == null || newPassword.isEmpty()) ? storedPassword().orElse(null) : newPassword;
            } catch (IllegalStateException e) {
                return new TestResult(false, e.getMessage());
            }
            if (password == null) {
                return new TestResult(false, "A bind DN needs a password (or clear the bind DN to search anonymously)");
            }
        }
        String searchBase = settings.userSearchBase().isEmpty()
                ? settings.baseDn() : settings.userSearchBase() + "," + settings.baseDn();
        try {
            new LdapTemplate(contextSource(settings, password)).lookup(settings.userSearchBase());
            return new TestResult(true, "Connected to " + settings.url() + " and found " + searchBase + ".");
        } catch (org.springframework.ldap.AuthenticationException e) {
            return new TestResult(false, "The directory rejected the bind DN or password.");
        } catch (CommunicationException e) {
            return new TestResult(false, "Couldn't reach " + settings.url() + ": " + rootMessage(e));
        } catch (NameNotFoundException e) {
            return new TestResult(false, "Connected, but " + searchBase + " wasn't found in the directory.");
        } catch (NamingException e) {
            return new TestResult(false, rootMessage(e));
        }
    }

    // One cheap version read per login; the directory connection is rebuilt only after a save, on
    // whichever node next needs it -- which is how a save reaches every node in a cluster.
    public Optional<Directory> directory() {
        Optional<Long> version = repository.findVersion();
        if (version.isEmpty()) {
            return Optional.empty();
        }
        CachedDirectory current = cached.get();
        if (current != null && current.version() == version.get()) {
            return current.directory();
        }
        synchronized (this) {
            LdapConfigurationRow row = repository.find().orElseThrow();
            CachedDirectory rebuilt = new CachedDirectory(row.version(), buildDirectory(row));
            cached.set(rebuilt);
            return rebuilt.directory();
        }
    }

    public boolean isEnabled() {
        return repository.find().map(LdapConfigurationRow::enabled).orElse(false);
    }

    private Optional<Directory> buildDirectory(LdapConfigurationRow row) {
        LdapSettings settings = settingsOf(row);
        if (!settings.enabled()) {
            return Optional.empty();
        }
        String password = null;
        if (!settings.anonymous()) {
            try {
                password = cipher.decrypt(row.bindPasswordEncrypted());
            } catch (IllegalStateException e) {
                LOG.error("LDAP is enabled but can't be used: {}", e.getMessage());
                return Optional.empty();
            }
        }
        LdapContextSource contextSource = contextSource(settings, password);
        BindAuthenticator authenticator = new BindAuthenticator(contextSource);
        authenticator.setUserSearch(new FilterBasedLdapUserSearch(settings.userSearchBase(),
                settings.userSearchFilter(), contextSource));
        return Optional.of(new Directory(settings, authenticator));
    }

    private Optional<String> storedPassword() {
        return repository.find().map(LdapConfigurationRow::bindPasswordEncrypted).map(cipher::decrypt);
    }

    private static LdapContextSource contextSource(LdapSettings settings, String password) {
        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(settings.url());
        contextSource.setBase(settings.baseDn());
        if (settings.anonymous()) {
            contextSource.setAnonymousReadOnly(true);
        } else {
            contextSource.setUserDn(settings.bindDn());
            contextSource.setPassword(password);
        }
        contextSource.setBaseEnvironmentProperties(TIMEOUTS);
        contextSource.afterPropertiesSet();
        return contextSource;
    }

    private static void validate(LdapSettings settings) {
        if (!settings.url().startsWith("ldap://") && !settings.url().startsWith("ldaps://")) {
            throw new IllegalArgumentException("The URL must start with ldap:// or ldaps://");
        }
        if (settings.baseDn().isEmpty()) {
            throw new IllegalArgumentException("A base DN is required");
        }
        if (!settings.userSearchFilter().contains("{0}")) {
            throw new IllegalArgumentException("The user search filter must contain {0}, where the login name goes");
        }
        if (settings.emailAttribute().isEmpty()) {
            throw new IllegalArgumentException("An email attribute is required");
        }
    }

    private static LdapSettings settingsOf(LdapConfigurationRow row) {
        return new LdapSettings(row.enabled(), row.url(), row.baseDn(), row.bindDn(), row.userSearchBase(),
                row.userSearchFilter(), row.emailAttribute(), row.displayNameAttribute());
    }

    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
