package org.ntrloc.graph.db.partition.security;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.authorization.DefaultUserGroupInitializer;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.changelog.ChangeLog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-local-accounts", havingValue = "true")
@DependsOnDatabaseInitialization
public class LocalAccountSeeder {

    private final UserStore store;
    private final UserService userService;
    private final DefaultUserGroupInitializer defaultUserGroupInitializer;

    // Constructor-injecting DefaultUserGroupInitializer (rather than just calling a static constant)
    // is what guarantees ensureUserGroupExists()'s @PostConstruct has already run by the time init()
    // below fires -- see addUserToDefaultUserGroup's own comment.
    public LocalAccountSeeder(UserStore store, UserService userService, DefaultUserGroupInitializer defaultUserGroupInitializer) {
        this.store = store;
        this.userService = userService;
        this.defaultUserGroupInitializer = defaultUserGroupInitializer;
    }

    @PostConstruct
    void init() {
        ChangeLog.withSource("local account seeding", () -> {
            seedAccount("Local Admin", "admin@local", "admin", true);
            seedAccount("Local User", "localuser@local", "password", false);
        });
    }

    private void seedAccount(String displayName, String email, String rawPassword, boolean isAdmin) {
        if (store.findUserByEmail(email).isPresent()) return;
        var user = userService.createUser(displayName, email, isAdmin);
        String passwordHash = "{bcrypt}" + new BCryptPasswordEncoder().encode(rawPassword);
        store.createLocalCredentials(user.id(), passwordHash);
        defaultUserGroupInitializer.addUserToDefaultUserGroup(user.id());
    }
}
