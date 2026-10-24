package org.ntrloc.graph.db.partition.security;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-local-accounts", havingValue = "true")
@DependsOnDatabaseInitialization
public class LocalAccountSeeder {

    private final SecurityRepository repo;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;

    // Constructor-injecting DefaultUserGroupInitializer (rather than just calling a static constant)
    // is what guarantees ensureUserGroupExists()'s @PostConstruct has already run by the time init()
    // below fires -- see addUserToDefaultUserGroup's own comment.
    public LocalAccountSeeder(SecurityRepository repo, DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.repo = repo;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    @PostConstruct
    void init() {
        seedAccount("Local Admin", "admin@local", "admin", true);
        seedAccount("Local User", "localuser@local", "password", false);
    }

    private void seedAccount(String displayName, String email, String rawPassword, boolean isAdmin) {
        if (repo.findUserByEmail(email).isPresent()) return;
        var user = repo.createUser(displayName, email, isAdmin);
        String passwordHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(rawPassword);
        repo.createLocalCredentials(user.id(), passwordHash);
        defaultUserGroupInitializer.addUserToDefaultUserGroup(user.id());
    }
}
