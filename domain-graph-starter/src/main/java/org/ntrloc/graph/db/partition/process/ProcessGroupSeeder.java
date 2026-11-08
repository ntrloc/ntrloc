package org.ntrloc.graph.db.partition.process;

import jakarta.annotation.PostConstruct;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "graph.security", name = "seed-local-accounts", havingValue = "true")
@DependsOn("localAccountSeeder")
@DependsOnDatabaseInitialization
public class ProcessGroupSeeder {

    private final ProcessGroupStore groupStore;
    private final UserStore userStore;
    private final JdbcClient jdbcClient;

    public ProcessGroupSeeder(ProcessGroupStore groupStore, UserStore userStore, JdbcClient jdbcClient) {
        this.groupStore = groupStore;
        this.userStore = userStore;
        this.jdbcClient = jdbcClient;
    }

    @PostConstruct
    void init() {
        boolean exists = jdbcClient.sql("SELECT COUNT(*) FROM process_group WHERE name = :name")
                .param("name", "reviewers")
                .query(Integer.class).single() > 0;
        if (exists) return;

        var reviewers = groupStore.createGroup("reviewers");
        userStore.findUserByEmail("localuser@local")
                .ifPresent(user -> groupStore.addUserToGroup(user.id(), reviewers.id()));
    }
}
