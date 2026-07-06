package org.ntrloc.graph.db.partition.security;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ntrloc.security", name = "seed-local-accounts", havingValue = "true")
@DependsOn("securityInitializer")
public class LocalAccountSeeder {

    private final SecurityRepository repo;

    public LocalAccountSeeder(SecurityRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void init() {
        seedAccount("admin", "Local Admin", "admin", "ADMIN");
        seedAccount("localuser", "Local User", "password", "USER");
    }

    private void seedAccount(String externalId, String displayName, String rawPassword, String role) {
        var user = repo.createUser(externalId, displayName);
        String passwordHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(rawPassword);
        repo.createLocalCredentials(user.id(), externalId, passwordHash, role);
    }
}
